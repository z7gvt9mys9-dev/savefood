// geows — SaveFood's high-concurrency hot paths, extracted from the Python
// monolith (savefood.md §31):
//
//  1. GET /ws/needy/{id}        — WebSocket notification fan-out. One shared
//     DB poller serves ALL connections (O(1) queries per tick instead of the
//     Python version's query-per-connection-per-3s).
//  2. PATCH /volunteers/{id}/location — location ingest (pushed every 20 s by
//     every active volunteer — the chattiest endpoint on the platform).
//  3. GET  /volunteers/{id}/location  — live location for delivery tracking.
//
// Auth is interoperable with the FastAPI backend: same HS256 SECRET_KEY, same
// claims (sub / role / related_id), same WebSocket handshake protocol
// ({"type":"auth","token":...,"since_id":...}), so the frontend needs zero
// changes — only the reverse proxy routes these paths here.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	pool      *pgxpool.Pool
	secretKey []byte

	reLocation = regexp.MustCompile(`^/volunteers/(\d+)/location$`)
	reWS       = regexp.MustCompile(`^/ws/needy/(\d+)$`)

	upgrader = websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		// Same-origin is enforced by the reverse proxy in prod; the JWT in the
		// first message is the actual authentication.
		CheckOrigin: func(r *http.Request) bool { return true },
	}
)

const (
	maxWSPerUser = 3               // mirror of Python's MAX_WS_PER_USER
	authTimeout  = 5 * time.Second // handshake deadline
	pollInterval = 2 * time.Second // shared notification poller tick
)

// ── auth ────────────────────────────────────────────────────────────────────

type claims struct {
	Sub       string
	Role      string
	RelatedID *int
}

func parseToken(token string) (*claims, error) {
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
		// Do not select an algorithm from untrusted JWT metadata.  Java explicitly
		// mints HS256 even for long secrets, and this service accepts that one
		// cross-service contract only.
		if t.Method.Alg() != "HS256" {
			return nil, errors.New("unexpected signing method")
		}
		return secretKey, nil
	})
	if err != nil || !parsed.Valid {
		return nil, errors.New("invalid token")
	}
	mc, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return nil, errors.New("bad claims")
	}
	c := &claims{}
	c.Sub, _ = mc["sub"].(string)
	c.Role, _ = mc["role"].(string)
	if rid, ok := mc["related_id"].(float64); ok {
		i := int(rid)
		c.RelatedID = &i
	}
	return c, nil
}

func bearerClaims(r *http.Request) (*claims, error) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(strings.ToLower(h), "bearer ") {
		return nil, errors.New("no bearer token")
	}
	return parseToken(strings.TrimSpace(h[7:]))
}

// accountState distinguishes a blocked account from a deleted one.  The latter
// used to look identical to an unblocked account, leaving an erased user's
// unexpired JWT usable through the Go hot paths.
func accountState(ctx context.Context, username string) (exists bool, blocked bool) {
	var isBlocked bool
	err := pool.QueryRow(ctx, "SELECT is_blocked FROM users WHERE username = $1", username).Scan(&isBlocked)
	return err == nil, isBlocked
}

func rejectInactiveAccount(ctx context.Context, w http.ResponseWriter, username string) bool {
	exists, blocked := accountState(ctx, username)
	if !exists {
		httpError(w, http.StatusUnauthorized, "Could not validate credentials")
		return true
	}
	if blocked {
		httpError(w, http.StatusForbidden, "Аккаунт заблокирован администратором")
		return true
	}
	return false
}

func validCoordinates(lat, lon float64) bool {
	return !math.IsNaN(lat) && !math.IsInf(lat, 0) &&
		!math.IsNaN(lon) && !math.IsInf(lon, 0) &&
		lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, status int, detail string) {
	writeJSON(w, status, map[string]string{"detail": detail})
}

// ── volunteer location ──────────────────────────────────────────────────────

type locationUpdate struct {
	Lat *float64 `json:"lat"`
	Lon *float64 `json:"lon"`
}

func locationHandler(w http.ResponseWriter, r *http.Request, volunteerID int) {
	ctx := r.Context()
	c, err := bearerClaims(r)
	if err != nil {
		httpError(w, http.StatusUnauthorized, "Could not validate credentials")
		return
	}
	if rejectInactiveAccount(ctx, w, c.Sub) {
		return
	}

	isSelf := c.Role == "volunteer" && c.RelatedID != nil && *c.RelatedID == volunteerID

	switch r.Method {
	case http.MethodPatch:
		if c.Role != "admin" && !isSelf {
			httpError(w, http.StatusForbidden, "Forbidden")
			return
		}
		var body locationUpdate
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Lat == nil || body.Lon == nil {
			httpError(w, http.StatusUnprocessableEntity, "lat and lon are required")
			return
		}
		if !validCoordinates(*body.Lat, *body.Lon) {
			httpError(w, http.StatusUnprocessableEntity,
				"lat must be between -90 and 90 and lon between -180 and 180")
			return
		}
		// CONTRACT: the FastAPI backend keeps a write-through Redis cache of
		// volunteer location (key vol:loc:{id}, TTL_LOCATION=60s) — its PATCH
		// writes the cache and its GET reads it. This Go service has no Redis
		// client and writes Postgres only. The reverse proxy MUST therefore
		// route the GET and PATCH of /volunteers/{id}/location to the SAME
		// service (both here, or both to FastAPI). Split routing
		// (writes→geows, reads→FastAPI) would serve location up to 60s stale
		// from the Python cache. If geows ever needs to own writes while
		// FastAPI owns reads, add a Redis DEL of vol:loc:{id} here.
		_, err := pool.Exec(ctx,
			"UPDATE volunteers SET lat = $1, lon = $2, updated_at = NOW() WHERE id = $3",
			*body.Lat, *body.Lon, volunteerID)
		if err != nil {
			httpError(w, http.StatusInternalServerError, "db error")
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})

	case http.MethodGet:
		allowed := c.Role == "admin" || isSelf
		if !allowed && c.Role == "needy" && c.RelatedID != nil {
			// A recipient may track the volunteer only while a ticket is
			// actively assigned (privacy — same rule as the Python service).
			var one int
			err := pool.QueryRow(ctx,
				"SELECT 1 FROM tickets WHERE needy_id = $1 AND assigned_volunteer_id = $2 AND status = 'assigned' LIMIT 1",
				*c.RelatedID, volunteerID).Scan(&one)
			allowed = err == nil
		}
		if !allowed {
			httpError(w, http.StatusForbidden, "Forbidden")
			return
		}
		var lat, lon *float64
		var updatedAt *time.Time
		err := pool.QueryRow(ctx,
			"SELECT lat, lon, updated_at FROM volunteers WHERE id = $1", volunteerID).
			Scan(&lat, &lon, &updatedAt)
		if err != nil {
			httpError(w, http.StatusNotFound, "Volunteer not found")
			return
		}
		// Historic/corrupt rows must not propagate NaN/Infinity to clients. New
		// writes are checked above; a null location remains a valid "not shared" state.
		if lat != nil && lon != nil && !validCoordinates(*lat, *lon) {
			httpError(w, http.StatusNotFound, "Volunteer location unavailable")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"lat": lat, "lon": lon, "updated_at": updatedAt})

	default:
		httpError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// ── notification hub (shared poller, per-needy fan-out) ─────────────────────

type wsMessage struct {
	ID      int64  `json:"id"`
	Type    string `json:"type"`
	Payload string `json:"payload"`
}

type client struct {
	conn    *websocket.Conn
	needyID int
	mu      sync.Mutex // gorilla/websocket forbids concurrent writers
}

func (cl *client) send(v any) error {
	cl.mu.Lock()
	defer cl.mu.Unlock()
	cl.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return cl.conn.WriteJSON(v)
}

type hub struct {
	mu      sync.Mutex
	clients map[int]map[*client]struct{} // needyID → connections
	lastID  int64
}

func newHub(ctx context.Context) *hub {
	h := &hub{clients: make(map[int]map[*client]struct{})}
	_ = pool.QueryRow(ctx, "SELECT COALESCE(MAX(id), 0) FROM notifications").Scan(&h.lastID)
	return h
}

func (h *hub) add(cl *client) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.clients[cl.needyID]) >= maxWSPerUser {
		return false
	}
	if h.clients[cl.needyID] == nil {
		h.clients[cl.needyID] = make(map[*client]struct{})
	}
	h.clients[cl.needyID][cl] = struct{}{}
	return true
}

func (h *hub) remove(cl *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients[cl.needyID], cl)
	if len(h.clients[cl.needyID]) == 0 {
		delete(h.clients, cl.needyID)
	}
}

// run is the single shared poller: one query per tick feeds every connection.
func (h *hub) run(ctx context.Context) {
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}

		h.mu.Lock()
		idle := len(h.clients) == 0
		last := h.lastID
		h.mu.Unlock()
		if idle {
			continue // nobody connected — skip the query entirely
		}

		rows, err := pool.Query(ctx,
			"SELECT id, needy_id, COALESCE(type, ''), COALESCE(payload, '') FROM notifications WHERE id > $1 AND needy_id IS NOT NULL ORDER BY id ASC",
			last)
		if err != nil {
			log.Printf("[hub] poll error: %v", err)
			continue
		}
		type row struct {
			id      int64
			needyID int
			msg     wsMessage
		}
		var batch []row
		for rows.Next() {
			var r row
			if err := rows.Scan(&r.id, &r.needyID, &r.msg.Type, &r.msg.Payload); err == nil {
				r.msg.ID = r.id
				batch = append(batch, r)
			}
		}
		rows.Close()

		for _, r := range batch {
			h.mu.Lock()
			if r.id > h.lastID {
				h.lastID = r.id
			}
			targets := make([]*client, 0, len(h.clients[r.needyID]))
			for cl := range h.clients[r.needyID] {
				targets = append(targets, cl)
			}
			h.mu.Unlock()
			for _, cl := range targets {
				if err := cl.send(r.msg); err != nil {
					h.remove(cl)
					cl.conn.Close()
				}
			}
		}
	}
}

// ── websocket handler ───────────────────────────────────────────────────────

type authFrame struct {
	Type    string `json:"type"`
	Token   string `json:"token"`
	SinceID *int64 `json:"since_id"`
}

func wsHandler(h *hub) func(http.ResponseWriter, *http.Request, int) {
	return func(w http.ResponseWriter, r *http.Request, needyID int) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		closeWith := func(code int) {
			_ = conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(code, ""), time.Now().Add(time.Second))
			conn.Close()
		}

		// Handshake: first frame must be {"type":"auth","token":...} within 5s
		// — same protocol as the Python endpoint, so the SPA works unchanged.
		conn.SetReadDeadline(time.Now().Add(authTimeout))
		var frame authFrame
		if err := conn.ReadJSON(&frame); err != nil || frame.Type != "auth" {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		c, err := parseToken(frame.Token)
		if err != nil {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		owner := c.Role == "needy" && c.RelatedID != nil && *c.RelatedID == needyID
		if c.Role != "admin" && !owner {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		ctx := r.Context()
		exists, blocked := accountState(ctx, c.Sub)
		if !exists || blocked {
			closeWith(websocket.ClosePolicyViolation)
			return
		}

		cl := &client{conn: conn, needyID: needyID}
		if !h.add(cl) {
			closeWith(websocket.ClosePolicyViolation) // connection cap reached
			return
		}
		defer func() { h.remove(cl); conn.Close() }()

		// Register BEFORE catch-up: the shared poller may race us, but the
		// frontend dedupes by id, so a duplicate beats a lost notification.
		since := int64(-1)
		if frame.SinceID != nil && *frame.SinceID >= 0 {
			since = *frame.SinceID
		}
		if since >= 0 {
			rows, err := pool.Query(ctx,
				"SELECT id, COALESCE(type, ''), COALESCE(payload, '') FROM notifications WHERE needy_id = $1 AND id > $2 ORDER BY id ASC",
				needyID, since)
			if err == nil {
				for rows.Next() {
					var m wsMessage
					if rows.Scan(&m.ID, &m.Type, &m.Payload) == nil {
						_ = cl.send(m)
					}
				}
				rows.Close()
			}
		}
		h.mu.Lock()
		ready := h.lastID
		h.mu.Unlock()
		_ = cl.send(map[string]any{"type": "ready", "last_id": ready})

		// Reader loop: we never expect more frames, but reading is what
		// detects the disconnect instantly (the Python version learnt this
		// the hard way — write-only loops leak dead connections).
		conn.SetReadDeadline(time.Time{})
		conn.SetPongHandler(func(string) error { return nil })
		go func() {
			ticker := time.NewTicker(30 * time.Second)
			defer ticker.Stop()
			for range ticker.C {
				cl.mu.Lock()
				err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
				cl.mu.Unlock()
				if err != nil {
					return
				}
			}
		}()
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}
}

// ── wiring ──────────────────────────────────────────────────────────────────

func main() {
	secret := os.Getenv("SECRET_KEY")
	if len(secret) < 32 {
		log.Fatal("SECRET_KEY env var must be set (≥32 chars), same value as the FastAPI backend")
	}
	secretKey = []byte(secret)

	dsn := fmt.Sprintf("host=%s port=%s dbname=%s user=%s password=%s",
		envOr("DB_HOST", "localhost"), envOr("DB_PORT", "5432"),
		envOr("DB_NAME", "savefood"), envOr("DB_USER", "postgres"), envOr("DB_PASS", "postgres"))

	ctx := context.Background()
	var err error
	pool, err = pgxpool.New(ctx, dsn)
	if err != nil {
		log.Fatalf("db pool: %v", err)
	}
	if err := pool.Ping(ctx); err != nil {
		log.Fatalf("db ping: %v", err)
	}

	h := newHub(ctx)
	go h.run(ctx)
	ws := wsHandler(h)

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "service": "geows"})
			return
		}
		if m := reLocation.FindStringSubmatch(r.URL.Path); m != nil {
			id, _ := strconv.Atoi(m[1])
			locationHandler(w, r, id)
			return
		}
		if m := reWS.FindStringSubmatch(r.URL.Path); m != nil {
			id, _ := strconv.Atoi(m[1])
			ws(w, r, id)
			return
		}
		httpError(w, http.StatusNotFound, "not found")
	})

	addr := ":" + envOr("PORT", "8001")
	log.Printf("[geows] listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
