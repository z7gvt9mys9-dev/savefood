package main

import (
	"context"
	"sync"
	"testing"
)

type fakeNotificationSource struct {
	mu             sync.Mutex
	initialLatest  int64
	rows           []notificationRow
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

func (s *fakeNotificationSource) insert(needyID int, id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.rows = append(s.rows, notificationRow{
		needyID: needyID,
		msg:     wsMessage{ID: id, Type: "test", Payload: "notification"},
	})
}

type recordedNotifications struct {
	mu   sync.Mutex
	rows []wsMessage
}

func recordingClient(needyID int, cursor int64, recorded *recordedNotifications) *client {
	cl := newClient(nil, needyID, cursor)
	cl.writeJSON = func(value any) error {
		if msg, ok := value.(wsMessage); ok {
			recorded.mu.Lock()
			recorded.rows = append(recorded.rows, msg)
			recorded.mu.Unlock()
		}
		return nil
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
