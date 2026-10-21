package location

import (
	"context"
	"testing"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

type testRepo struct {
	saved []Update
}

func (r *testRepo) SaveLatest(ctx context.Context, loc Update) error {
	r.saved = append(r.saved, loc)
	return nil
}

type mockSession struct {
	deviceID     string
	authDeviceID string
	lastLocation protocol.LocationMessage
}

func (s *mockSession) DeviceID() string {
	return s.deviceID
}

func (s *mockSession) AuthenticatedDeviceID() string {
	return s.authDeviceID
}

func (s *mockSession) SetLocation(message protocol.LocationMessage) {
	s.lastLocation = message
}

func TestHandler_StoresLocationWithDeviceIDAndAliases(t *testing.T) {
	repo := &testRepo{}
	svc := NewServiceWithDependencies(repo, nil, time.Now)
	h := NewHandler(svc)

	sess := &mockSession{
		deviceID:     "HOST-001-VIVO-2D2C",
		authDeviceID: "HOST-001",
	}

	payload := protocol.LocationMessage{
		Latitude:  28.5,
		Longitude: 77.2,
		Accuracy:  10,
		Battery:   80,
		Network:   "WIFI",
	}
	msg, err := protocol.NewMessage(protocol.TypeLocation, 1, payload)
	if err != nil {
		t.Fatalf("failed to create msg: %v", err)
	}

	_, err = h.Handle(context.Background(), sess, *msg)
	if err != nil {
		t.Fatalf("Handle failed: %v", err)
	}

	if sess.lastLocation.Battery != 80 {
		t.Fatalf("expected lastLocation to be set")
	}

	deviceIDs := make(map[string]bool)
	for _, s := range repo.saved {
		deviceIDs[s.DeviceID] = true
	}

	if !deviceIDs["HOST-001-VIVO-2D2C"] {
		t.Errorf("expected HOST-001-VIVO-2D2C in saved updates, got %+v", repo.saved)
	}
	if !deviceIDs["HOST-VIVO-2D2C"] {
		t.Errorf("expected HOST-VIVO-2D2C alias in saved updates, got %+v", repo.saved)
	}
	if deviceIDs["HOST-001"] {
		t.Errorf("expected HOST-001 to NOT be overwritten in multi-device fleet, got %+v", repo.saved)
	}
}

func TestHandler_FallbackToAuthenticatedDeviceIDWhenDeviceIDEmpty(t *testing.T) {
	repo := &testRepo{}
	svc := NewServiceWithDependencies(repo, nil, time.Now)
	h := NewHandler(svc)

	sess := &mockSession{
		deviceID:     "",
		authDeviceID: "HOST-001",
	}

	payload := protocol.LocationMessage{
		Latitude:  28.5,
		Longitude: 77.2,
		Accuracy:  10,
		Battery:   50,
		Network:   "CELL",
	}
	msg, err := protocol.NewMessage(protocol.TypeLocation, 2, payload)
	if err != nil {
		t.Fatalf("failed to create msg: %v", err)
	}

	_, err = h.Handle(context.Background(), sess, *msg)
	if err != nil {
		t.Fatalf("Handle failed: %v", err)
	}

	deviceIDs := make(map[string]bool)
	for _, s := range repo.saved {
		deviceIDs[s.DeviceID] = true
	}

	if !deviceIDs["HOST-001"] {
		t.Errorf("expected HOST-001 in saved updates, got %+v", repo.saved)
	}
}
