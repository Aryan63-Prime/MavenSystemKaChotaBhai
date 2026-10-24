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
	GetLocationHistory(ctx context.Context, deviceID string, from, to time.Time, limit int) ([]repository.Location, error)
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

// ListSessions returns snapshots of active sessions.
func (s *Service) ListSessions() []SessionSnapshot {
	if s == nil || s.sessions == nil {
		return nil
	}
	return s.sessions.ListSessions()
}

// GetBatteryHistory returns historical battery and thermal telemetry for a device.
func (s *Service) GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]repository.BatteryHistoryEntry, error) {
	if s.enrolled == nil {
		return []repository.BatteryHistoryEntry{}, nil
	}
	deviceID = strings.TrimSpace(deviceID)
	entries, err := s.enrolled.GetBatteryHistory(ctx, deviceID, limit)
	if err == nil && len(entries) > 0 {
		return entries, nil
	}

	if strings.HasPrefix(deviceID, "HOST") && !strings.HasPrefix(deviceID, "HOST-001-") && strings.Contains(deviceID, "-") {
		suffix := deviceID[strings.Index(deviceID, "-")+1:]
		longID := "HOST-001-" + suffix
		if altEntries, altErr := s.enrolled.GetBatteryHistory(ctx, longID, limit); altErr == nil && len(altEntries) > 0 {
			return altEntries, nil
		}
	}

	if strings.Contains(deviceID, "_") {
		baseID := strings.SplitN(deviceID, "_", 2)[0]
		if baseID != "HOST-001" && baseID != "" && baseID != deviceID {
			if altEntries, altErr := s.enrolled.GetBatteryHistory(ctx, baseID, limit); altErr == nil && len(altEntries) > 0 {
				return altEntries, nil
			}
		}
	}

	return entries, err
}

// GetLocationHistory returns historical location breadcrumbs for a device.
func (s *Service) GetLocationHistory(ctx context.Context, deviceID string, from, to time.Time, limit int) ([]repository.Location, error) {
	if s.enrolled == nil {
		return []repository.Location{}, nil
	}

	deviceID = strings.TrimSpace(deviceID)
	pts, err := s.enrolled.GetLocationHistory(ctx, deviceID, from, to, limit)
	if err == nil && len(pts) > 0 {
		return pts, nil
	}

	if strings.HasPrefix(deviceID, "HOST") && !strings.HasPrefix(deviceID, "HOST-001-") && strings.Contains(deviceID, "-") {
		suffix := deviceID[strings.Index(deviceID, "-")+1:]
		longID := "HOST-001-" + suffix
		if altPts, altErr := s.enrolled.GetLocationHistory(ctx, longID, from, to, limit); altErr == nil && len(altPts) > 0 {
			return altPts, nil
		}
	}

	if strings.Contains(deviceID, "_") {
		baseID := strings.SplitN(deviceID, "_", 2)[0]
		if baseID != "HOST-001" && baseID != "" && baseID != deviceID {
			if altPts, altErr := s.enrolled.GetLocationHistory(ctx, baseID, from, to, limit); altErr == nil && len(altPts) > 0 {
				return altPts, nil
			}
		}
	}

	if err != nil {
		return nil, err
	}
	return []repository.Location{}, nil
}

// WakeDevice sends a high-priority FCM wakeup ping to the device.
func (s *Service) WakeDevice(ctx context.Context, deviceID string) (string, error) {
	if s.fcm == nil {
		return "", errors.New("fcm service not configured")
	}
	return s.fcm.WakeDevice(ctx, deviceID)
}

func canonicalDeviceKey(deviceID, model, connectionID string) string {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID != "" && deviceID != "HOST-001" {
		return deviceID
	}
	if model != "" {
		return deviceID + "_" + model
	}
	if connectionID != "" {
		return deviceID + "_" + connectionID
	}
	return deviceID
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

			mapKey := canonicalDeviceKey(snapshot.DeviceID, snapshot.Model, snapshot.ConnectionID)
			if snapshot.DeviceID == "HOST-001" {
				if existing, ok := deviceMap[mapKey]; ok && existing.ConnectionID != "" && existing.ConnectionID != snapshot.ConnectionID {
					mapKey = snapshot.DeviceID + "_" + snapshot.ConnectionID
				}
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
				mapKey := canonicalDeviceKey(dev.DeviceID, dev.Model, "")
				if _, ok := deviceMap[mapKey]; !ok {
					// Check if any active session matches deviceID prefix
					hasActive := false
					for _, activeDev := range deviceMap {
						if activeDev.DeviceID == dev.DeviceID ||
							(dev.DeviceID == "HOST-001" && dev.Model != "" && strings.EqualFold(activeDev.Model, dev.Model)) {
							hasActive = true
							break
						}
					}
					if !hasActive && dev.DeviceID == "HOST-001" {
						for _, otherEnrolled := range enrolledList {
							if otherEnrolled.DeviceID != "HOST-001" && (dev.Model == "" || strings.EqualFold(otherEnrolled.Model, dev.Model)) {
								hasActive = true
								break
							}
						}
					}
					if !hasActive {
						var locPtr *repository.Location
						if s.locations != nil {
							if loc, found, err := s.locations.GetLatest(ctx, dev.DeviceID); found && err == nil {
								locPtr = &loc
							}
						}
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
							LatestLocation:    locPtr,
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
		if (err != nil || dev == nil) && strings.HasPrefix(deviceID, "HOST") && !strings.HasPrefix(deviceID, "HOST-001-") && strings.Contains(deviceID, "-") {
			suffix := deviceID[strings.Index(deviceID, "-")+1:]
			dev, err = s.enrolled.GetEnrolledDevice(ctx, "HOST-001-"+suffix)
		}
		if err == nil && dev != nil {
			var locPtr *repository.Location
			if s.locations != nil {
				if loc, found, err := s.locations.GetLatest(ctx, dev.DeviceID); found && err == nil {
					locPtr = &loc
				}
			}
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
				LatestLocation:    locPtr,
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
