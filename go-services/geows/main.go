package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"log"
	"math"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	pool       *pgxpool.Pool
	secretKey  []byte
	reLocation = regexp.MustCompile(`^/volunteers/(\d+)/location$`)
	reWS       = regexp.MustCompile(`^/ws/needy/(\d+)$`)
	upgrader   = websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		CheckOrigin:     func(r *http.Request) bool { return true },
	}
)

const (
	maxWSPerUser             = 3
	authTimeout              = 5 * time.Second
	pollInterval             = 2 * time.Second
	defaultMaxWSMessageBytes = 4 * 1024
)

type claims struct {
	UserID    int
	Role      string
	RelatedID *int
}

func parseToken(token string) (*claims, error) {
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
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
	sub, _ := mc["sub"].(string)
	c.UserID, err = strconv.Atoi(sub)
	if err != nil || c.UserID <= 0 {
		return nil, errors.New("bad subject")
	}
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
func currentAccount(ctx context.Context, tokenClaims *claims) (current *claims, exists bool, blocked bool) {
	var role string
	var relatedID *int
	var isBlocked bool
	err := pool.QueryRow(ctx,
		"SELECT role, related_id, is_blocked FROM users WHERE id = $1", tokenClaims.UserID).
		Scan(&role, &relatedID, &isBlocked)
	if err != nil {
		return nil, false, false
	}
	return &claims{UserID: tokenClaims.UserID, Role: role, RelatedID: relatedID}, true, isBlocked
}
func activeClaims(ctx context.Context, w http.ResponseWriter, tokenClaims *claims) (*claims, bool) {
	current, exists, blocked := currentAccount(ctx, tokenClaims)
	if !exists {
		httpError(w, http.StatusUnauthorized, "Could not validate credentials")
		return nil, false
	}
	if blocked {
		httpError(w, http.StatusForbidden, "Аккаунт заблокирован администратором")
		return nil, false
	}
	return current, true
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

type locationUpdate struct {
	Lat *float64 `json:"lat"`
	Lon *float64 `json:"lon"`
}
type locationRouteTx interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
	Commit(context.Context) error
	Rollback(context.Context) error
}
type locationRouteStore interface {
	BeginRouteActivityTx(context.Context) (locationRouteTx, error)
}
type pgLocationRouteStore struct {
	pool *pgxpool.Pool
}

func (s pgLocationRouteStore) BeginRouteActivityTx(ctx context.Context) (locationRouteTx, error) {
	return s.pool.Begin(ctx)
}
func shouldRefreshRouteActivity(c *claims, volunteerID int) bool {
	return c.Role == "volunteer" && c.RelatedID != nil && *c.RelatedID == volunteerID
}
func writeLocationAndRouteActivity(ctx context.Context, store locationRouteStore,
	volunteerID int, lat, lon float64, refreshRouteActivity bool) error {
	tx, err := store.BeginRouteActivityTx(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err = tx.Exec(ctx,
		"UPDATE volunteers SET lat = $1, lon = $2, updated_at = NOW() WHERE id = $3",
		lat, lon, volunteerID); err != nil {
		return err
	}
	if refreshRouteActivity {
		if _, err = tx.Exec(ctx,
			"UPDATE volunteer_routes SET last_activity_at = NOW() "+
				"WHERE volunteer_id = $1 AND status = 'in_progress'",
			volunteerID); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}
func locationHandler(w http.ResponseWriter, r *http.Request, volunteerID int) {
	ctx := r.Context()
	c, err := bearerClaims(r)
	if err != nil {
		httpError(w, http.StatusUnauthorized, "Could not validate credentials")
		return
	}
	c, ok := activeClaims(ctx, w, c)
	if !ok {
		return
	}
	isSelf := shouldRefreshRouteActivity(c, volunteerID)
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
		err := writeLocationAndRouteActivity(ctx, pgLocationRouteStore{pool}, volunteerID,
			*body.Lat, *body.Lon, isSelf)
		if err != nil {
			httpError(w, http.StatusInternalServerError, "db error")
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	case http.MethodGet:
		allowed := c.Role == "admin" || isSelf
		if !allowed && c.Role == "needy" && c.RelatedID != nil {
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
		if lat != nil && lon != nil && !validCoordinates(*lat, *lon) {
			httpError(w, http.StatusNotFound, "Volunteer location unavailable")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"lat": lat, "lon": lon, "updated_at": updatedAt})
	default:
		httpError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

type wsMessage struct {
	ID      int64  `json:"id"`
	Type    string `json:"type"`
	Payload string `json:"payload"`
}
type notificationRow struct {
	needyID int
	msg     wsMessage
}
type notificationSource interface {
	latestID(context.Context) (int64, error)
	after(context.Context, int64) ([]notificationRow, error)
	afterForNeedy(context.Context, int, int64) ([]wsMessage, error)
	activeUserIDs(context.Context, []int) (map[int]struct{}, error)
}
type postgresNotificationSource struct {
	pool *pgxpool.Pool
}

func (s postgresNotificationSource) latestID(ctx context.Context) (int64, error) {
	var id int64
	err := s.pool.QueryRow(ctx,
		"SELECT COALESCE(MAX(id), 0) FROM notifications WHERE needy_id IS NOT NULL").Scan(&id)
	return id, err
}
func (s postgresNotificationSource) after(ctx context.Context, since int64) ([]notificationRow, error) {
	rows, err := s.pool.Query(ctx,
		"SELECT id, needy_id, COALESCE(type, ''), COALESCE(payload, '') FROM notifications WHERE id > $1 AND needy_id IS NOT NULL ORDER BY id ASC",
		since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []notificationRow
	for rows.Next() {
		var row notificationRow
		if err := rows.Scan(&row.msg.ID, &row.needyID, &row.msg.Type, &row.msg.Payload); err != nil {
			return nil, err
		}
		result = append(result, row)
	}
	return result, rows.Err()
}
func (s postgresNotificationSource) afterForNeedy(ctx context.Context, needyID int, since int64) ([]wsMessage, error) {
	rows, err := s.pool.Query(ctx,
		"SELECT id, COALESCE(type, ''), COALESCE(payload, '') FROM notifications WHERE needy_id = $1 AND id > $2 ORDER BY id ASC",
		needyID, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []wsMessage
	for rows.Next() {
		var msg wsMessage
		if err := rows.Scan(&msg.ID, &msg.Type, &msg.Payload); err != nil {
			return nil, err
		}
		result = append(result, msg)
	}
	return result, rows.Err()
}
func (s postgresNotificationSource) activeUserIDs(ctx context.Context, userIDs []int) (map[int]struct{}, error) {
	active := make(map[int]struct{}, len(userIDs))
	if len(userIDs) == 0 {
		return active, nil
	}
	postgresIDs := make([]int32, len(userIDs))
	for i, id := range userIDs {
		postgresIDs[i] = int32(id)
	}
	rows, err := s.pool.Query(ctx,
		"SELECT id FROM users WHERE id = ANY($1::int[]) AND NOT is_blocked", postgresIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		active[id] = struct{}{}
	}
	return active, rows.Err()
}

var errClientRevoked = errors.New("websocket account revoked")

type client struct {
	conn       *websocket.Conn
	userID     int
	needyID    int
	mu         sync.Mutex
	cursor     int64
	catchingUp bool
	pending    map[int64]wsMessage
	revoked    atomic.Bool
	writeJSON  func(any) error
	closeConn  func()
}

func newClient(conn *websocket.Conn, userID, needyID int, cursor int64) *client {
	cl := &client{
		conn: conn, userID: userID, needyID: needyID, cursor: cursor, catchingUp: true,
		pending: make(map[int64]wsMessage),
	}
	if conn != nil {
		cl.closeConn = func() {
			_ = conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.ClosePolicyViolation, ""),
				time.Now().Add(time.Second))
			_ = conn.Close()
		}
	}
	return cl
}
func (cl *client) writeLocked(v any) error {
	if cl.writeJSON != nil {
		return cl.writeJSON(v)
	}
	cl.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return cl.conn.WriteJSON(v)
}
func (cl *client) send(v any) error {
	cl.mu.Lock()
	defer cl.mu.Unlock()
	if cl.revoked.Load() {
		return errClientRevoked
	}
	return cl.writeLocked(v)
}
func (cl *client) deliver(msg wsMessage) error {
	cl.mu.Lock()
	defer cl.mu.Unlock()
	if cl.revoked.Load() {
		return errClientRevoked
	}
	if msg.ID <= cl.cursor {
		return nil
	}
	if cl.catchingUp {
		cl.pending[msg.ID] = msg
		return nil
	}
	if err := cl.writeLocked(msg); err != nil {
		return err
	}
	cl.cursor = msg.ID
	return nil
}
func (cl *client) finishCatchUp(replay []wsMessage) error {
	cl.mu.Lock()
	if cl.revoked.Load() {
		cl.pending = nil
		cl.mu.Unlock()
		return errClientRevoked
	}
	for _, msg := range replay {
		cl.pending[msg.ID] = msg
	}
	cl.mu.Unlock()
	for {
		cl.mu.Lock()
		if cl.revoked.Load() {
			cl.pending = nil
			cl.mu.Unlock()
			return errClientRevoked
		}
		ids := make([]int64, 0, len(cl.pending))
		for id := range cl.pending {
			if id > cl.cursor {
				ids = append(ids, id)
			} else {
				delete(cl.pending, id)
			}
		}
		if len(ids) == 0 {
			cl.pending = nil
			cl.catchingUp = false
			cl.mu.Unlock()
			return nil
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		id := ids[0]
		msg := cl.pending[id]
		if err := cl.writeLocked(msg); err != nil {
			cl.mu.Unlock()
			return err
		}
		cl.cursor = id
		delete(cl.pending, id)
		cl.mu.Unlock()
	}
}
func (cl *client) revoke() {
	if !cl.revoked.CompareAndSwap(false, true) {
		return
	}
	if cl.closeConn != nil {
		cl.closeConn()
	}
	cl.mu.Lock()
	cl.pending = nil
	cl.catchingUp = false
	cl.mu.Unlock()
}
func (cl *client) lastDeliveredID() int64 {
	cl.mu.Lock()
	defer cl.mu.Unlock()
	return cl.cursor
}

type hub struct {
	mu      sync.Mutex
	clients map[int]map[*client]struct{}
	lastID  int64
	source  notificationSource
}

func newHub(ctx context.Context) *hub {
	return newHubWithSource(ctx, postgresNotificationSource{pool: pool})
}
func newHubWithSource(ctx context.Context, source notificationSource) *hub {
	h := &hub{clients: make(map[int]map[*client]struct{}), source: source}
	h.lastID, _ = source.latestID(ctx)
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
func (h *hub) revoke(cl *client) {
	cl.revoke()
	h.remove(cl)
}
func (h *hub) pollOnce(ctx context.Context) error {
	h.mu.Lock()
	idle := len(h.clients) == 0
	last := h.lastID
	clients := make([]*client, 0)
	uniqueUserIDs := make(map[int]struct{})
	for _, byNeedy := range h.clients {
		for cl := range byNeedy {
			clients = append(clients, cl)
			uniqueUserIDs[cl.userID] = struct{}{}
		}
	}
	h.mu.Unlock()
	if idle {
		return nil
	}
	userIDs := make([]int, 0, len(uniqueUserIDs))
	for id := range uniqueUserIDs {
		userIDs = append(userIDs, id)
	}
	active, err := h.source.activeUserIDs(ctx, userIDs)
	if err != nil {
		return fmt.Errorf("revalidate websocket accounts: %w", err)
	}
	for _, cl := range clients {
		if _, ok := active[cl.userID]; !ok {
			h.revoke(cl)
		}
	}
	batch, err := h.source.after(ctx, last)
	if err != nil {
		return err
	}
	for _, row := range batch {
		h.mu.Lock()
		if row.msg.ID > h.lastID {
			h.lastID = row.msg.ID
		}
		targets := make([]*client, 0, len(h.clients[row.needyID]))
		for cl := range h.clients[row.needyID] {
			targets = append(targets, cl)
		}
		h.mu.Unlock()
		for _, cl := range targets {
			if err := cl.deliver(row.msg); err != nil {
				h.revoke(cl)
			}
		}
	}
	return nil
}
func (h *hub) run(ctx context.Context) {
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		if err := h.pollOnce(ctx); err != nil {
			log.Printf("[hub] poll error: %v", err)
		}
	}
}

type authFrame struct {
	Type    string `json:"type"`
	Token   string `json:"token"`
	SinceID *int64 `json:"since_id"`
}
type wsAccountLookup func(context.Context, *claims) (*claims, bool, bool)

func wsHandler(h *hub, maxMessageBytes int64) func(http.ResponseWriter, *http.Request, int) {
	return wsHandlerWithAccountLookup(h, maxMessageBytes, currentAccount)
}

func wsHandlerWithAccountLookup(h *hub, maxMessageBytes int64,
	lookup wsAccountLookup) func(http.ResponseWriter, *http.Request, int) {
	return func(w http.ResponseWriter, r *http.Request, needyID int) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		conn.SetReadLimit(maxMessageBytes)
		closeWith := func(code int) {
			_ = conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(code, ""), time.Now().Add(time.Second))
			conn.Close()
		}
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
		ctx := r.Context()
		current, exists, blocked := lookup(ctx, c)
		if !exists || blocked {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		c = current
		owner := c.Role == "needy" && c.RelatedID != nil && *c.RelatedID == needyID
		if c.Role != "admin" && !owner {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		since := int64(0)
		if frame.SinceID != nil && *frame.SinceID >= 0 {
			since = *frame.SinceID
		}
		cl := newClient(conn, c.UserID, needyID, since)
		if !h.add(cl) {
			closeWith(websocket.ClosePolicyViolation)
			return
		}
		defer func() { h.remove(cl); conn.Close() }()
		replay, err := h.source.afterForNeedy(ctx, needyID, since)
		if err != nil {
			closeWith(websocket.CloseInternalServerErr)
			return
		}
		if err := cl.finishCatchUp(replay); err != nil {
			return
		}
		if err := cl.send(map[string]any{"type": "ready", "last_id": cl.lastDeliveredID()}); err != nil {
			return
		}
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
	maxMessageBytes, err := positiveEnvInt64("WS_MAX_MESSAGE_BYTES", defaultMaxWSMessageBytes)
	if err != nil {
		log.Fatal(err)
	}
	ws := wsHandler(h, maxMessageBytes)
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

func positiveEnvInt64(key string, def int64) (int64, error) {
	value := os.Getenv(key)
	if value == "" {
		return def, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", key)
	}
	return parsed, nil
}
