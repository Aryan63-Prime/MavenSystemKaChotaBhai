package admin

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/repository"
)

func TestServiceListDevicesReturnsRealtimeState(t *testing.T) {
	now := time.Date(2026, time.July, 9, 12, 0, 0, 0, time.UTC)
	source := &fakeSessionSource{
		sessions: []SessionSnapshot{
			{
				ConnectionID:  "CONN-1",
				DeviceID:      "HOST-0001",
				Authenticated: true,
				Registered:    true,
				ConnectedAt:   now.Add(-time.Minute),
				LastHeartbeat: now,
				DeviceName:    "Pixel 9",
				AppVersion:    "1.0.0",
				Model:         "Google Pixel",
			},
			{
				ConnectionID: "CONN-2",
			},
		},
	}
	locations := &fakeLocationReader{
		locations: map[string]repository.Location{
			"HOST-0001": {
				DeviceID:   "HOST-0001",
				Latitude:   28.6139,
				Longitude:  77.2090,
				Accuracy:   5.4,
				Battery:    81,
				Network:    "5G",
				RecordedAt: now,
			},
		},
	}
	service := NewService(source, locations, fakeHeartbeatPolicy{stale: false})

	devices, err := service.ListDevices(context.Background())
	if err != nil {
		t.Fatalf("ListDevices returned error: %v", err)
	}

	if len(devices) != 1 {
		t.Fatalf("expected one device, got %d", len(devices))
	}

	device := devices[0]
	if device.DeviceID != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", device.DeviceID)
	}
	if device.RegistrationState != registrationStateRegistered {
		t.Fatalf("expected registered state, got %s", device.RegistrationState)
	}
	if device.HeartbeatStatus != heartbeatStatusOnline {
		t.Fatalf("expected online heartbeat, got %s", device.HeartbeatStatus)
	}
	if device.LatestLocation == nil || device.LatestLocation.Network != "5G" {
		t.Fatalf("expected latest Redis location, got %+v", device.LatestLocation)
	}
}

func TestServiceGetDeviceReturnsStaleUnregisteredDevice(t *testing.T) {
	now := time.Date(2026, time.July, 9, 12, 0, 0, 0, time.UTC)
	source := &fakeSessionSource{
		sessions: []SessionSnapshot{
			{
				ConnectionID:  "CONN-1",
				DeviceID:      "HOST-0001",
				Authenticated: true,
				Registered:    false,
				ConnectedAt:   now.Add(-time.Minute),
				LastHeartbeat: now.Add(-2 * time.Minute),
			},
		},
	}
	service := NewService(source, &fakeLocationReader{}, fakeHeartbeatPolicy{stale: true})

	device, found, err := service.GetDevice(context.Background(), "HOST-0001")
	if err != nil {
		t.Fatalf("GetDevice returned error: %v", err)
	}
	if !found {
		t.Fatal("expected device to be found")
	}
	if device.RegistrationState != registrationStateUnregistered {
		t.Fatalf("expected unregistered state, got %s", device.RegistrationState)
	}
	if device.HeartbeatStatus != heartbeatStatusStale {
		t.Fatalf("expected stale heartbeat, got %s", device.HeartbeatStatus)
	}
}

func TestServiceGetDeviceMissing(t *testing.T) {
	service := NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{})

	_, found, err := service.GetDevice(context.Background(), "HOST-0001")
	if err != nil {
		t.Fatalf("GetDevice returned error: %v", err)
	}
	if found {
		t.Fatal("expected missing device")
	}
}

func TestServiceGetDeviceRejectsMissingDeviceID(t *testing.T) {
	service := NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{})

	_, _, err := service.GetDevice(context.Background(), " ")
	if !errors.Is(err, ErrMissingDeviceID) {
		t.Fatalf("expected ErrMissingDeviceID, got %v", err)
	}
}

func TestServiceReturnsLocationReaderError(t *testing.T) {
	expected := errors.New("redis unavailable")
	source := &fakeSessionSource{
		sessions: []SessionSnapshot{{DeviceID: "HOST-0001", Registered: true}},
	}
	service := NewService(source, &fakeLocationReader{err: expected}, fakeHeartbeatPolicy{})

	_, err := service.ListDevices(context.Background())
	if !errors.Is(err, expected) {
		t.Fatalf("expected location error, got %v", err)
	}
}

type fakeSessionSource struct {
	sessions []SessionSnapshot
}

func (s *fakeSessionSource) ListSessions() []SessionSnapshot {
	return append([]SessionSnapshot(nil), s.sessions...)
}

func (s *fakeSessionSource) GetSessionByDeviceID(deviceID string) (SessionSnapshot, bool) {
	for _, session := range s.sessions {
		if session.DeviceID == deviceID {
			return session, true
		}
	}

	return SessionSnapshot{}, false
}

type fakeLocationReader struct {
	locations map[string]repository.Location
	err       error
}

func (r *fakeLocationReader) GetLatest(ctx context.Context, deviceID string) (repository.Location, bool, error) {
	_ = ctx

	if r.err != nil {
		return repository.Location{}, false, r.err
	}

	location, ok := r.locations[deviceID]
	return location, ok, nil
}

type fakeHeartbeatPolicy struct {
	stale bool
}

func (p fakeHeartbeatPolicy) IsStale(lastHeartbeat time.Time) bool {
	_ = lastHeartbeat

	return p.stale
}

func TestFleetLocationDoesNotFallBackToSharedToken(t *testing.T) {
	locations := &fakeLocationReader{locations: map[string]repository.Location{
		"HOST-001":           {DeviceID: "HOST-001", Battery: 99},
		"HOST-VIVO-AAAA":     {DeviceID: "HOST-VIVO-AAAA", Battery: 88},
		"HOST-001-VIVO-BBBB": {DeviceID: "HOST-001-VIVO-BBBB", Battery: 42},
	}}
	service := NewService(nil, locations, nil)
	first, err := service.deviceFromSnapshot(context.Background(), SessionSnapshot{DeviceID: "HOST-001-VIVO-AAAA"})
	if err != nil || first.LatestLocation != nil {
		t.Fatalf("must not borrow shared/alias location: %+v, %v", first, err)
	}
	second, err := service.deviceFromSnapshot(context.Background(), SessionSnapshot{DeviceID: "HOST-001-VIVO-BBBB"})
	if err != nil || second.LatestLocation == nil || second.LatestLocation.Battery != 42 {
		t.Fatalf("exact location missing: %+v, %v", second, err)
	}
}

type fakeEnrolledSource struct {
	devices []repository.EnrolledDevice
}

func (f *fakeEnrolledSource) ListEnrolledDevices(ctx context.Context) ([]repository.EnrolledDevice, error) {
	return f.devices, nil
}

func (f *fakeEnrolledSource) GetEnrolledDevice(ctx context.Context, deviceID string) (*repository.EnrolledDevice, error) {
	for _, d := range f.devices {
		if d.DeviceID == deviceID {
			return &d, nil
		}
	}
	return nil, errors.New("not found")
}

func (f *fakeEnrolledSource) GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]repository.BatteryHistoryEntry, error) {
	return []repository.BatteryHistoryEntry{
		{
			DeviceID:       deviceID,
			BatteryPercent: 88,
			BatteryTempC:   29.5,
			IsCharging:     false,
			RecordedAt:     time.Now(),
		},
	}, nil
}

func TestServiceIncludesOfflineEnrolledDevices(t *testing.T) {
	service := NewService(nil, nil, nil)
	enrolled := &fakeEnrolledSource{
		devices: []repository.EnrolledDevice{
			{
				DeviceID:   "HOST-ENROLLED-1",
				DeviceName: "Enrolled Phone",
				AppVersion: "1.0.0",
				Model:      "Galaxy S24",
				LastSeen:   time.Now(),
			},
		},
	}
	service.SetEnrolledDeviceSource(enrolled)

	devices, err := service.ListDevices(context.Background())
	if err != nil {
		t.Fatalf("ListDevices failed: %v", err)
	}
	if len(devices) != 1 {
		t.Fatalf("expected 1 device, got %d", len(devices))
	}
	if devices[0].DeviceID != "HOST-ENROLLED-1" || devices[0].HeartbeatStatus != "stale" {
		t.Fatalf("unexpected offline device state: %+v", devices[0])
	}

	dev, ok, err := service.GetDevice(context.Background(), "HOST-ENROLLED-1")
	if err != nil || !ok {
		t.Fatalf("GetDevice failed: ok=%v, err=%v", ok, err)
	}
	if dev.DeviceID != "HOST-ENROLLED-1" {
		t.Fatalf("unexpected device: %+v", dev)
	}
}

func TestServiceGetBatteryHistory(t *testing.T) {
	service := NewService(nil, nil, nil)
	enrolled := &fakeEnrolledSource{}
	service.SetEnrolledDeviceSource(enrolled)

	history, err := service.GetBatteryHistory(context.Background(), "DEV-1", 10)
	if err != nil {
		t.Fatalf("GetBatteryHistory failed: %v", err)
	}
	if len(history) != 1 || history[0].BatteryPercent != 88 {
		t.Fatalf("unexpected battery history: %+v", history)
	}
}

