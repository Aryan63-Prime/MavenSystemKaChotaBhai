package admin

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/repository"
)

const (
	heartbeatStatusOnline = "online"
	heartbeatStatusStale  = "stale"

	registrationStateRegistered   = "registered"
	registrationStateUnregistered = "unregistered"
)

var ErrMissingDeviceID = errors.New("missing device id")

// SessionSnapshot is the live session state required by the admin API.
type SessionSnapshot struct {
	ConnectionID  string
	DeviceID      string
	Authenticated bool
	Registered    bool
	ConnectedAt   time.Time
	LastHeartbeat time.Time
	DeviceName    string
	AppVersion    string
	Model         string
}

// SessionSource provides read-only access to connected device sessions.
type SessionSource interface {
	ListSessions() []SessionSnapshot
	GetSessionByDeviceID(deviceID string) (SessionSnapshot, bool)
}

// LocationReader reads the latest realtime location for a device.
type LocationReader interface {
	GetLatest(ctx context.Context, deviceID string) (repository.Location, bool, error)
}

// HeartbeatPolicy reports whether a session heartbeat is stale.
type HeartbeatPolicy interface {
	IsStale(lastHeartbeat time.Time) bool
}

// Device is the admin API representation of a connected device.
type Device struct {
	DeviceID          string               `json:"deviceId"`
	ConnectionID      string               `json:"connectionId"`
	Authenticated     bool                 `json:"authenticated"`
	Registered        bool                 `json:"registered"`
	RegistrationState string               `json:"registrationState"`
	HeartbeatStatus   string               `json:"heartbeatStatus"`
	ConnectedAt       time.Time            `json:"connectedAt"`
	LastHeartbeat     time.Time            `json:"lastHeartbeat"`
	DeviceName        string               `json:"deviceName,omitempty"`
	AppVersion        string               `json:"appVersion,omitempty"`
	Model             string               `json:"model,omitempty"`
	LatestLocation    *repository.Location `json:"latestLocation"`
}

// FcmWaker triggers push messages to wake devices.
type FcmWaker interface {
	WakeDevice(ctx context.Context, deviceID string) (string, error)
}

// EnrolledDeviceSource provides durable access to enrolled devices across server restarts.
type EnrolledDeviceSource interface {
	ListEnrolledDevices(ctx context.Context) ([]repository.EnrolledDevice, error)
	GetEnrolledDevice(ctx context.Context, deviceID string) (*repository.EnrolledDevice, error)
	GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]repository.BatteryHistoryEntry, error)
}

// Service builds admin-facing device views from live sessions and Redis state.
type Service struct {
	sessions  SessionSource
	locations LocationReader
	heartbeat HeartbeatPolicy
	fcm       FcmWaker
	enrolled  EnrolledDeviceSource
}

// NewService creates an admin API service.
func NewService(sessions SessionSource, locations LocationReader, heartbeat HeartbeatPolicy) *Service {
	return &Service{
		sessions:  sessions,
		locations: locations,
		heartbeat: heartbeat,
	}
}

// SetFcmService binds the FCM waker to the admin service.
func (s *Service) SetFcmService(f FcmWaker) {
	s.fcm = f
}

// SetEnrolledDeviceSource binds the durable enrolled device source to the admin service.
func (s *Service) SetEnrolledDeviceSource(e EnrolledDeviceSource) {
	s.enrolled = e
}

// GetBatteryHistory returns historical battery and thermal telemetry for a device.
func (s *Service) GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]repository.BatteryHistoryEntry, error) {
	if s.enrolled == nil {
		return []repository.BatteryHistoryEntry{}, nil
	}
	return s.enrolled.GetBatteryHistory(ctx, deviceID, limit)
}

// WakeDevice sends a high-priority FCM wakeup ping to the device.
func (s *Service) WakeDevice(ctx context.Context, deviceID string) (string, error) {
	if s.fcm == nil {
		return "", errors.New("fcm service not configured")
	}
	return s.fcm.WakeDevice(ctx, deviceID)
}

// ListDevices returns connected devices with their realtime state.
func (s *Service) ListDevices(ctx context.Context) ([]Device, error) {
	if s.sessions == nil && s.enrolled == nil {
		return []Device{}, nil
	}

	deviceMap := make(map[string]Device)

	if s.sessions != nil {
		snapshots := s.sessions.ListSessions()
		for _, snapshot := range snapshots {
			if strings.TrimSpace(snapshot.DeviceID) == "" || !snapshot.Registered {
				continue
			}

		device, err := s.deviceFromSnapshot(ctx, snapshot)
		if err != nil {
			return nil, err
		}

		// Key by DeviceID + Model if available, or ConnectionID, to avoid collapsing multiple devices sharing a token
		mapKey := snapshot.DeviceID
		if snapshot.Model != "" {
			mapKey = snapshot.DeviceID + "_" + snapshot.Model
		} else if snapshot.ConnectionID != "" {
			mapKey = snapshot.DeviceID + "_" + snapshot.ConnectionID
		}

		if existing, ok := deviceMap[mapKey]; ok {
			if snapshot.ConnectedAt.After(existing.ConnectedAt) {
				deviceMap[mapKey] = device
			}
		} else {
			deviceMap[mapKey] = device
		}
	}
	}

	// Merge enrolled devices from durable storage that are currently offline
	if s.enrolled != nil {
		enrolledList, err := s.enrolled.ListEnrolledDevices(ctx)
		if err == nil {
			for _, dev := range enrolledList {
				mapKey := dev.DeviceID
				if dev.Model != "" {
					mapKey = dev.DeviceID + "_" + dev.Model
				}
				if _, ok := deviceMap[mapKey]; !ok {
					// Check if any active session matches deviceID prefix
					hasActive := false
					for _, activeDev := range deviceMap {
						if activeDev.DeviceID == dev.DeviceID {
							hasActive = true
							break
						}
					}
					if !hasActive {
						deviceMap[mapKey] = Device{
							DeviceID:          dev.DeviceID,
							DeviceName:        dev.DeviceName,
							AppVersion:        dev.AppVersion,
							Model:             dev.Model,
							Authenticated:     true,
							Registered:        true,
							RegistrationState: registrationStateRegistered,
							HeartbeatStatus:   heartbeatStatusStale, // offline/stale
							ConnectedAt:       dev.LastSeen,
							LastHeartbeat:     dev.LastSeen,
						}
					}
				}
			}
		}
	}

	devices := make([]Device, 0, len(deviceMap))
	for _, device := range deviceMap {
		devices = append(devices, device)
	}

	return devices, nil
}

// GetDevice returns one connected device by its permanent device id.
func (s *Service) GetDevice(ctx context.Context, deviceID string) (Device, bool, error) {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return Device{}, false, ErrMissingDeviceID
	}

	if s.sessions != nil {
		snapshot, ok := s.sessions.GetSessionByDeviceID(deviceID)
		if ok {
			device, err := s.deviceFromSnapshot(ctx, snapshot)
			if err != nil {
				return Device{}, false, err
			}
			return device, true, nil
		}
	}

	// Fallback to durable enrolled storage if currently offline/disconnected
	if s.enrolled != nil {
		dev, err := s.enrolled.GetEnrolledDevice(ctx, deviceID)
		if err == nil && dev != nil {
			return Device{
				DeviceID:          dev.DeviceID,
				DeviceName:        dev.DeviceName,
				AppVersion:        dev.AppVersion,
				Model:             dev.Model,
				Authenticated:     true,
				Registered:        true,
				RegistrationState: registrationStateRegistered,
				HeartbeatStatus:   heartbeatStatusStale,
				ConnectedAt:       dev.LastSeen,
				LastHeartbeat:     dev.LastSeen,
			}, true, nil
		}
	}

	return Device{}, false, nil
}

func (s *Service) deviceFromSnapshot(ctx context.Context, snapshot SessionSnapshot) (Device, error) {
	device := Device{
		DeviceID:          snapshot.DeviceID,
		ConnectionID:      snapshot.ConnectionID,
		Authenticated:     snapshot.Authenticated,
		Registered:        snapshot.Registered,
		RegistrationState: registrationState(snapshot.Registered),
		HeartbeatStatus:   s.heartbeatStatus(snapshot.LastHeartbeat),
		ConnectedAt:       snapshot.ConnectedAt,
		LastHeartbeat:     snapshot.LastHeartbeat,
		DeviceName:        snapshot.DeviceName,
		AppVersion:        snapshot.AppVersion,
		Model:             snapshot.Model,
	}

	if s.locations == nil {
		return device, nil
	}

	location, found, err := s.locations.GetLatest(ctx, snapshot.DeviceID)
	if err != nil {
		return Device{}, fmt.Errorf("get latest location: %w", err)
	}
	if found {
		device.LatestLocation = &location
	}

	return device, nil
}

func (s *Service) heartbeatStatus(lastHeartbeat time.Time) string {
	if s.heartbeat != nil && s.heartbeat.IsStale(lastHeartbeat) {
		return heartbeatStatusStale
	}

	return heartbeatStatusOnline
}

func registrationState(registered bool) string {
	if registered {
		return registrationStateRegistered
	}

	return registrationStateUnregistered
}
