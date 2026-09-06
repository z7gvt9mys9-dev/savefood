package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5/pgconn"
	"math"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type recordedLocationExec struct {
	sql  string
	args []any
}
type fakeLocationRouteTx struct {
	execs      []recordedLocationExec
	failRoute  bool
	committed  bool
	rolledBack bool
}

func (t *fakeLocationRouteTx) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	t.execs = append(t.execs, recordedLocationExec{sql: sql, args: args})
	if t.failRoute && strings.Contains(sql, "UPDATE volunteer_routes") {
		return pgconn.NewCommandTag("UPDATE 0"), errors.New("route write failed")
	}
	return pgconn.NewCommandTag("UPDATE 1"), nil
}
func (t *fakeLocationRouteTx) Commit(context.Context) error {
	t.committed = true
	return nil
}
func (t *fakeLocationRouteTx) Rollback(context.Context) error {
	t.rolledBack = true
	return nil
}

type fakeLocationRouteStore struct {
	tx *fakeLocationRouteTx
}

func (s fakeLocationRouteStore) BeginRouteActivityTx(context.Context) (locationRouteTx, error) {
	return s.tx, nil
}
func TestParseTokenAcceptsOnlyHS256(t *testing.T) {
	secretKey = []byte("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	claims := jwt.MapClaims{"sub": "123", "username": "alice", "role": "volunteer", "related_id": 7}
	hs256, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := parseToken(hs256)
	if err != nil || parsed.UserID != 123 || parsed.Role != "volunteer" || parsed.RelatedID == nil || *parsed.RelatedID != 7 {
		t.Fatalf("HS256 token was not parsed correctly: claims=%+v err=%v", parsed, err)
	}
	hs512, err := jwt.NewWithClaims(jwt.SigningMethodHS512, claims).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseToken(hs512); err == nil {
		t.Fatal("HS512 token must be rejected")
	}
	legacy, err := jwt.NewWithClaims(jwt.SigningMethodHS256,
		jwt.MapClaims{"sub": "alice", "role": "volunteer", "related_id": 7}).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseToken(legacy); err == nil {
		t.Fatal("reusable username subject must be rejected")
	}
}
func TestValidCoordinatesRejectsWrappedAndNonFiniteValues(t *testing.T) {
	for _, c := range []struct {
		lat, lon float64
		want     bool
	}{
		{43.238, 76.889, true},
		{90, 180, true},
		{91, 76, false},
		{43, 181, false},
		{360, 76, false},
		{math.NaN(), 76, false},
	} {
		if got := validCoordinates(c.lat, c.lon); got != c.want {
			t.Errorf("validCoordinates(%v, %v) = %v, want %v", c.lat, c.lon, got, c.want)
		}
	}
}
func TestValidVolunteerGPSHeartbeatUpdatesLocationAndActiveRouteAtomically(t *testing.T) {
	tx := &fakeLocationRouteTx{}
	err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{tx}, 7, 43.238, 76.889, true)
	if err != nil {
		t.Fatal(err)
	}
	if !tx.committed || len(tx.execs) != 2 {
		t.Fatalf("want one committed location and route transaction, got committed=%v execs=%+v", tx.committed, tx.execs)
	}
	if !strings.Contains(tx.execs[0].sql, "updated_at = NOW()") || !strings.Contains(tx.execs[1].sql, "last_activity_at = NOW()") {
		t.Fatalf("timestamps must come from Postgres NOW(): %+v", tx.execs)
	}
	if !strings.Contains(tx.execs[1].sql, "volunteer_id = $1") || tx.execs[1].args[0] != 7 {
		t.Fatalf("route heartbeat must be limited to the sending volunteer: %+v", tx.execs[1])
	}
}
func TestRegularGPSHeartbeatsAlwaysRefreshActivityWithDatabaseTime(t *testing.T) {
	for i := 0; i < 3; i++ {
		tx := &fakeLocationRouteTx{}
		if err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{tx}, 7, 43.238, 76.889, true); err != nil {
			t.Fatal(err)
		}
		if len(tx.execs) != 2 || !strings.Contains(tx.execs[1].sql, "last_activity_at = NOW()") {
			t.Fatalf("heartbeat %d did not refresh route activity from the database clock: %+v", i, tx.execs)
		}
	}
}
func TestWrongVolunteerOrAdminLocationUpdateDoesNotHeartbeatRoute(t *testing.T) {
	for _, c := range []*claims{
		{Role: "volunteer", RelatedID: intPtr(8)},
		{Role: "admin"},
	} {
		tx := &fakeLocationRouteTx{}
		if err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{tx}, 7, 43.238, 76.889,
			shouldRefreshRouteActivity(c, 7)); err != nil {
			t.Fatal(err)
		}
		if len(tx.execs) != 1 || strings.Contains(tx.execs[0].sql, "volunteer_routes") {
			t.Fatalf("non-owner location update must not touch a route: %+v", tx.execs)
		}
	}
}
func TestHeartbeatOnlyTargetsInProgressRoutesAndNoRouteIsHarmless(t *testing.T) {
	tx := &fakeLocationRouteTx{}
	if err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{tx}, 7, 43.238, 76.889, true); err != nil {
		t.Fatal(err)
	}
	routeSQL := tx.execs[1].sql
	if !strings.Contains(routeSQL, "status = 'in_progress'") {
		t.Fatalf("terminal routes must be excluded from heartbeats: %s", routeSQL)
	}
	noRouteTx := &fakeLocationRouteTx{}
	if err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{noRouteTx}, 7, 43.238, 76.889, false); err != nil {
		t.Fatal(err)
	}
	if len(noRouteTx.execs) != 1 || !noRouteTx.committed {
		t.Fatalf("a volunteer without an active route must still persist location only: %+v", noRouteTx)
	}
}
func TestRouteHeartbeatFailureRollsBackLocationWrite(t *testing.T) {
	tx := &fakeLocationRouteTx{failRoute: true}
	err := writeLocationAndRouteActivity(context.Background(), fakeLocationRouteStore{tx}, 7, 43.238, 76.889, true)
	if err == nil || tx.committed || !tx.rolledBack {
		t.Fatalf("a failed route heartbeat must roll back the paired location write: err=%v committed=%v rolledBack=%v", err, tx.committed, tx.rolledBack)
	}
}

type fakeWSNotificationSource struct{}

func (fakeWSNotificationSource) latestID(context.Context) (int64, error) { return 0, nil }
func (fakeWSNotificationSource) after(context.Context, int64) ([]notificationRow, error) {
	return nil, nil
}
func (fakeWSNotificationSource) afterForNeedy(context.Context, int, int64) ([]wsMessage, error) {
	return nil, nil
}
func (fakeWSNotificationSource) activeUserIDs(_ context.Context, userIDs []int) (map[int]struct{}, error) {
	active := make(map[int]struct{}, len(userIDs))
	for _, id := range userIDs {
		active[id] = struct{}{}
	}
	return active, nil
}

func TestWebSocketReadLimitAcceptsNormalAndBoundaryAuth(t *testing.T) {
	const limit int64 = defaultMaxWSMessageBytes
	token := signedTestToken(t, 41, "needy", 7)
	for _, tc := range []struct {
		name    string
		payload []byte
	}{
		{name: "normal", payload: []byte(fmt.Sprintf(`{"type":"auth","token":%q}`, token))},
		{name: "just below limit", payload: paddedAuthFrame(t, token, int(limit)-1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHubWithSource(context.Background(), fakeWSNotificationSource{})
			server, wsURL := websocketTestServer(t, h, limit, activeTestAccount)
			defer server.Close()
			conn := dialTestWebSocket(t, wsURL)
			defer conn.Close()
			if err := conn.WriteMessage(websocket.TextMessage, tc.payload); err != nil {
				t.Fatal(err)
			}
			assertReadyFrame(t, conn)
		})
	}
}

func TestOversizedWebSocketAuthNeverRegistersClient(t *testing.T) {
	const limit int64 = 512
	h := newHubWithSource(context.Background(), fakeWSNotificationSource{})
	var lookups atomic.Int32
	lookup := func(context.Context, *claims) (*claims, bool, bool) {
		lookups.Add(1)
		return &claims{UserID: 41, Role: "needy", RelatedID: intPtr(7)}, true, false
	}
	server, wsURL := websocketTestServer(t, h, limit, lookup)
	defer server.Close()
	conn := dialTestWebSocket(t, wsURL)
	defer conn.Close()
	token := signedTestToken(t, 41, "needy", 7)
	_ = conn.WriteMessage(websocket.TextMessage, paddedAuthFrame(t, token, int(limit)+1))
	assertConnectionClosed(t, conn)
	if lookups.Load() != 0 {
		t.Fatalf("oversized auth reached account lookup %d times", lookups.Load())
	}
	h.mu.Lock()
	registered := len(h.clients)
	h.mu.Unlock()
	if registered != 0 {
		t.Fatalf("oversized auth registered a hub client: %d", registered)
	}
}

func TestOversizedPostAuthFrameClosesOnlyThatConnection(t *testing.T) {
	const limit int64 = 512
	h := newHubWithSource(context.Background(), fakeWSNotificationSource{})
	server, wsURL := websocketTestServer(t, h, limit, activeTestAccount)
	defer server.Close()
	token := signedTestToken(t, 41, "needy", 7)
	first := dialTestWebSocket(t, wsURL)
	if err := first.WriteJSON(authFrame{Type: "auth", Token: token}); err != nil {
		t.Fatal(err)
	}
	assertReadyFrame(t, first)
	if err := first.WriteMessage(websocket.TextMessage, []byte(`{"type":"noop"}`)); err != nil {
		t.Fatalf("normal post-auth message failed: %v", err)
	}
	_ = first.WriteMessage(websocket.TextMessage, []byte(strings.Repeat("x", int(limit)+1)))
	assertConnectionClosed(t, first)
	_ = first.Close()
	awaitHubClientCount(t, h, 0)

	second := dialTestWebSocket(t, wsURL)
	defer second.Close()
	if err := second.WriteJSON(authFrame{Type: "auth", Token: token}); err != nil {
		t.Fatal(err)
	}
	assertReadyFrame(t, second)
}

func TestPositiveEnvInt64(t *testing.T) {
	t.Setenv("WS_MAX_MESSAGE_BYTES", "8192")
	if got, err := positiveEnvInt64("WS_MAX_MESSAGE_BYTES", 4096); err != nil || got != 8192 {
		t.Fatalf("got %d, %v", got, err)
	}
	t.Setenv("WS_MAX_MESSAGE_BYTES", "0")
	if _, err := positiveEnvInt64("WS_MAX_MESSAGE_BYTES", 4096); err == nil {
		t.Fatal("non-positive limit must be rejected")
	}
}

func signedTestToken(t *testing.T, userID int, role string, relatedID int) string {
	t.Helper()
	secretKey = []byte("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub": fmt.Sprint(userID), "role": role, "related_id": relatedID,
	}).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func paddedAuthFrame(t *testing.T, token string, size int) []byte {
	t.Helper()
	prefix := fmt.Sprintf(`{"type":"auth","token":%q,"padding":"`, token)
	suffix := `"}`
	padding := size - len(prefix) - len(suffix)
	if padding < 0 {
		t.Fatalf("auth frame base size %d exceeds target %d", len(prefix)+len(suffix), size)
	}
	return []byte(prefix + strings.Repeat("a", padding) + suffix)
}

func activeTestAccount(_ context.Context, tokenClaims *claims) (*claims, bool, bool) {
	return tokenClaims, true, false
}

func websocketTestServer(t *testing.T, h *hub, limit int64,
	lookup wsAccountLookup) (*http.Server, string) {
	t.Helper()
	handler := wsHandlerWithAccountLookup(h, limit, lookup)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handler(w, r, 7)
	})}
	go func() { _ = server.Serve(listener) }()
	return server, "ws://" + listener.Addr().String()
}

func dialTestWebSocket(t *testing.T, url string) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func assertReadyFrame(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	var ready map[string]any
	if err := conn.ReadJSON(&ready); err != nil {
		t.Fatalf("expected ready frame: %v", err)
	}
	if ready["type"] != "ready" {
		t.Fatalf("unexpected ready frame: %+v", ready)
	}
	_ = conn.SetReadDeadline(time.Time{})
}

func assertConnectionClosed(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, _, err := conn.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(err, &closeErr) {
		t.Fatalf("expected WebSocket close error, got %v", err)
	}
	if closeErr.Code != websocket.CloseMessageTooBig {
		t.Fatalf("expected close code %d, got %d", websocket.CloseMessageTooBig, closeErr.Code)
	}
	_ = conn.SetReadDeadline(time.Time{})
}

func awaitHubClientCount(t *testing.T, h *hub, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		h.mu.Lock()
		count := 0
		for _, clients := range h.clients {
			count += len(clients)
		}
		h.mu.Unlock()
		if count == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("hub client count did not reach %d", want)
}

func intPtr(v int) *int { return &v }
