package main
import (
	"context"
	"errors"
	"math"
	"strings"
	"testing"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgconn"
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
func intPtr(v int) *int { return &v }
