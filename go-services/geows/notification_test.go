package main

import (
	"context"
	"errors"
	"sync"
	"testing"
)

type fakeNotificationSource struct {
	mu             sync.Mutex
	initialLatest  int64
	rows           []notificationRow
	activeUsers    map[int]bool
	replayStarted  chan struct{}
	releaseReplay  chan struct{}
	replayStartOne sync.Once
}

func (s *fakeNotificationSource) latestID(context.Context) (int64, error) {
	return s.initialLatest, nil
}

func (s *fakeNotificationSource) after(_ context.Context, since int64) ([]notificationRow, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var result []notificationRow
	for _, row := range s.rows {
		if row.msg.ID > since {
			result = append(result, row)
		}
	}
	return result, nil
}

func (s *fakeNotificationSource) afterForNeedy(_ context.Context, needyID int, since int64) ([]wsMessage, error) {
	s.mu.Lock()
	var result []wsMessage
	for _, row := range s.rows {
		if row.needyID == needyID && row.msg.ID > since {
			result = append(result, row.msg)
		}
	}
	s.mu.Unlock()
	if s.replayStarted != nil {
		s.replayStartOne.Do(func() { close(s.replayStarted) })
		<-s.releaseReplay
	}
	return result, nil
}

func (s *fakeNotificationSource) activeUserIDs(_ context.Context, userIDs []int) (map[int]struct{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	active := make(map[int]struct{}, len(userIDs))
	for _, id := range userIDs {
		if s.activeUsers == nil || s.activeUsers[id] {
			active[id] = struct{}{}
		}
	}
	return active, nil
}

func (s *fakeNotificationSource) setActive(userID int, active bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.activeUsers == nil {
		s.activeUsers = make(map[int]bool)
	}
	s.activeUsers[userID] = active
}

func (s *fakeNotificationSource) insert(needyID int, id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.rows = append(s.rows, notificationRow{
		needyID: needyID,
		msg:     wsMessage{ID: id, Type: "test", Payload: "notification"},
	})
}

type recordedNotifications struct {
	mu         sync.Mutex
	rows       []wsMessage
	closeCalls int
}

func recordingClient(needyID int, cursor int64, recorded *recordedNotifications) *client {
	return recordingUserClient(needyID, needyID, cursor, recorded)
}

func recordingUserClient(userID, needyID int, cursor int64, recorded *recordedNotifications) *client {
	cl := newClient(nil, userID, needyID, cursor)
	cl.writeJSON = func(value any) error {
		if msg, ok := value.(wsMessage); ok {
			recorded.mu.Lock()
			recorded.rows = append(recorded.rows, msg)
			recorded.mu.Unlock()
		}
		return nil
	}
	cl.closeConn = func() {
		recorded.mu.Lock()
		recorded.closeCalls++
		recorded.mu.Unlock()
	}
	return cl
}

func (r *recordedNotifications) ids() []int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	result := make([]int64, len(r.rows))
	for i, row := range r.rows {
		result[i] = row.ID
	}
	return result
}

func (r *recordedNotifications) closes() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.closeCalls
}

func isRegistered(h *hub, cl *client) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	_, ok := h.clients[cl.needyID][cl]
	return ok
}

func replayClient(t *testing.T, h *hub, cl *client, since int64) {
	t.Helper()
	replay, err := h.source.afterForNeedy(context.Background(), cl.needyID, since)
	if err != nil {
		t.Fatal(err)
	}
	if err := cl.finishCatchUp(replay); err != nil {
		t.Fatal(err)
	}
}

func TestNotificationInsertedBetweenRESTAndRegistrationIsReplayed(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 11}
	source.insert(4, 11) // REST boundary was id 10; this row committed afterwards.
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingClient(4, 10, recorded)
	if !h.add(cl) {
		t.Fatal("client was not registered")
	}
	replayClient(t, h, cl, 10)
	assertIDs(t, recorded.ids(), []int64{11})
}

func TestNotificationInsertedImmediatelyAfterRegistrationIsDelivered(t *testing.T) {
	source := &fakeNotificationSource{
		initialLatest: 10,
		replayStarted: make(chan struct{}),
		releaseReplay: make(chan struct{}),
	}
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingClient(4, 10, recorded)
	h.add(cl)
	done := make(chan struct{})
	go func() {
		replay, _ := source.afterForNeedy(context.Background(), 4, 10)
		_ = cl.finishCatchUp(replay)
		close(done)
	}()
	<-source.replayStarted // the replay snapshot is fixed and the client is registered
	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	close(source.releaseReplay)
	<-done
	assertIDs(t, recorded.ids(), []int64{11})
}

func TestReconnectFromLastCursorCatchesMissedNotification(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 10}
	h := newHubWithSource(context.Background(), source)
	firstRows := &recordedNotifications{}
	first := recordingClient(4, 10, firstRows)
	h.add(first)
	replayClient(t, h, first, 10)
	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	h.remove(first)
	source.insert(4, 12) // committed while this browser was disconnected

	reconnectedRows := &recordedNotifications{}
	reconnected := recordingClient(4, first.lastDeliveredID(), reconnectedRows)
	h.add(reconnected)
	replayClient(t, h, reconnected, first.lastDeliveredID())
	assertIDs(t, firstRows.ids(), []int64{11})
	assertIDs(t, reconnectedRows.ids(), []int64{12})
}

func TestClientsWithDifferentCursorsRecoverIndependently(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 8}
	source.insert(4, 6)
	source.insert(4, 8)
	h := newHubWithSource(context.Background(), source)

	olderRows, newerRows := &recordedNotifications{}, &recordedNotifications{}
	older := recordingClient(4, 5, olderRows)
	newer := recordingClient(4, 7, newerRows)
	h.add(older)
	h.add(newer)
	replayClient(t, h, older, 5)
	replayClient(t, h, newer, 7)
	assertIDs(t, olderRows.ids(), []int64{6, 8})
	assertIDs(t, newerRows.ids(), []int64{8})
}

func TestCatchUpAndLiveOverlapIsDeliveredOnce(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 10}
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingClient(4, 10, recorded)
	h.add(cl)

	// The replay query and global poller both observe id 11. The connection is
	// still catching up, so both paths merge on the same id before writing.
	source.insert(4, 11)
	replay, err := source.afterForNeedy(context.Background(), 4, 10)
	if err != nil {
		t.Fatal(err)
	}
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := cl.finishCatchUp(replay); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, recorded.ids(), []int64{11})
}

func TestReplayAndLiveDeliveryNeverCrossRecipientBoundary(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 10}
	source.insert(4, 11)
	source.insert(5, 12)
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingClient(4, 10, recorded)
	h.add(cl)
	replayClient(t, h, cl, 10)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, recorded.ids(), []int64{11})
}

func TestNormalLiveNotificationDeliveryStillWorks(t *testing.T) {
	source := &fakeNotificationSource{initialLatest: 10}
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingClient(4, 10, recorded)
	h.add(cl)
	replayClient(t, h, cl, 10)
	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, recorded.ids(), []int64{11})
}

func TestBlockedAccountRevokesAllSocketsAndLeavesUnrelatedUserActive(t *testing.T) {
	source := &fakeNotificationSource{
		initialLatest: 10,
		activeUsers:   map[int]bool{101: true, 202: true},
	}
	h := newHubWithSource(context.Background(), source)
	firstRows, secondRows := &recordedNotifications{}, &recordedNotifications{}
	unrelatedRows := &recordedNotifications{}
	first := recordingUserClient(101, 4, 10, firstRows)
	second := recordingUserClient(101, 4, 10, secondRows)
	unrelated := recordingUserClient(202, 4, 10, unrelatedRows)
	for _, cl := range []*client{first, second, unrelated} {
		if !h.add(cl) {
			t.Fatal("client was not registered")
		}
		replayClient(t, h, cl, 10)
	}

	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, firstRows.ids(), []int64{11})
	assertIDs(t, secondRows.ids(), []int64{11})
	assertIDs(t, unrelatedRows.ids(), []int64{11})

	source.setActive(101, false)
	source.insert(4, 12)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, firstRows.ids(), []int64{11})
	assertIDs(t, secondRows.ids(), []int64{11})
	assertIDs(t, unrelatedRows.ids(), []int64{11, 12})
	if isRegistered(h, first) || isRegistered(h, second) {
		t.Fatal("blocked user's sockets remain registered")
	}
	if !isRegistered(h, unrelated) {
		t.Fatal("unrelated user's socket was unregistered")
	}
	if firstRows.closes() != 1 || secondRows.closes() != 1 || unrelatedRows.closes() != 0 {
		t.Fatalf("close calls = blocked(%d, %d), unrelated(%d)",
			firstRows.closes(), secondRows.closes(), unrelatedRows.closes())
	}
}

func TestDeletedAccountIsRevokedAndPendingReplayIsDiscarded(t *testing.T) {
	source := &fakeNotificationSource{
		initialLatest: 10,
		activeUsers:   map[int]bool{303: true},
	}
	h := newHubWithSource(context.Background(), source)
	recorded := &recordedNotifications{}
	cl := recordingUserClient(303, 4, 10, recorded)
	h.add(cl)
	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}

	// The absent account row models backend account deletion. Revocation must
	// clear the live row queued during catch-up rather than replaying it later.
	source.setActive(303, false)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := cl.finishCatchUp(nil); !errors.Is(err, errClientRevoked) {
		t.Fatalf("finishCatchUp error = %v, want revoked", err)
	}
	assertIDs(t, recorded.ids(), nil)
	if isRegistered(h, cl) || recorded.closes() != 1 {
		t.Fatalf("deleted client registered=%v close calls=%d", isRegistered(h, cl), recorded.closes())
	}
}

func TestUnblockRequiresNewAuthenticatedConnection(t *testing.T) {
	source := &fakeNotificationSource{
		initialLatest: 10,
		activeUsers:   map[int]bool{404: true},
	}
	h := newHubWithSource(context.Background(), source)
	oldRows := &recordedNotifications{}
	old := recordingUserClient(404, 4, 10, oldRows)
	h.add(old)
	replayClient(t, h, old, 10)

	source.setActive(404, false)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	source.setActive(404, true)
	source.insert(4, 11)
	if err := h.pollOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertIDs(t, oldRows.ids(), nil)
	if isRegistered(h, old) {
		t.Fatal("unblocking reactivated the revoked socket")
	}

	newRows := &recordedNotifications{}
	fresh := recordingUserClient(404, 4, 10, newRows)
	if !h.add(fresh) {
		t.Fatal("new authenticated connection was not registered")
	}
	replayClient(t, h, fresh, 10)
	assertIDs(t, newRows.ids(), []int64{11})
}

func assertIDs(t *testing.T, got, want []int64) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("notification ids = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("notification ids = %v, want %v", got, want)
		}
	}
}
