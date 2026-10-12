package gateway

import (
	"strings"
	"sync"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

type Session struct {
	mu sync.RWMutex

	connectionID string

	deviceID              string
	authenticatedDeviceID string

	authenticated bool

	connectedAt time.Time

	lastHeartbeat time.Time

	registered bool

	deviceName string

	appVersion string

	model string

	lastLocation protocol.LocationMessage

	client *Client
}

// SessionSnapshot is a read-only copy of live session state.
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

// NewSession creates a fully initialized gateway session.
func NewSession(connectionID string, connectedAt time.Time, client *Client) *Session {
	return &Session{
		connectionID:  connectionID,
		connectedAt:   connectedAt,
		lastHeartbeat: connectedAt,
		client:        client,
	}
}

// ConnectionID returns the immutable connection identifier.
func (s *Session) ConnectionID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.connectionID
}

// SetAuthenticated marks the session authenticated for a device.
func (s *Session) SetAuthenticated(deviceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.deviceID = deviceID
	s.authenticatedDeviceID = deviceID
	s.authenticated = true
}

// IsAuthenticated reports whether the session passed authentication.
func (s *Session) IsAuthenticated() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.authenticated
}

// AuthenticatedDeviceID returns the device identity attached to the session.
func (s *Session) AuthenticatedDeviceID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.authenticatedDeviceID
}

// DeviceID returns the current device identity (registered or authenticated).
func (s *Session) DeviceID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.deviceID
}

// IsRegistered reports whether the session completed device registration.
func (s *Session) IsRegistered() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.registered
}

// SetRegistered stores device registration details on the session.
func (s *Session) SetRegistered(message protocol.RegisterMessage) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.registered = true
	s.deviceID = message.DeviceID
	s.deviceName = message.DeviceName
	s.appVersion = message.AppVersion
	s.model = message.Model
}

// Model returns the device hardware model.
func (s *Session) Model() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.model
}

// DeviceName returns the user-friendly device name.
func (s *Session) DeviceName() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.deviceName
}

// SetLastHeartbeat stores the latest heartbeat timestamp.
func (s *Session) SetLastHeartbeat(timestamp time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.lastHeartbeat = timestamp
}

// LastHeartbeat returns the latest heartbeat timestamp.
func (s *Session) LastHeartbeat() time.Time {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.lastHeartbeat
}

// SetLocation stores the latest location payload for the session.
func (s *Session) SetLocation(message protocol.LocationMessage) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.lastLocation = message
}

// LastLocation returns the latest location payload.
func (s *Session) LastLocation() protocol.LocationMessage {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.lastLocation
}

// Snapshot returns a consistent read-only copy of session state.
func (s *Session) Snapshot() SessionSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return SessionSnapshot{
		ConnectionID:  s.connectionID,
		DeviceID:      s.deviceID,
		Authenticated: s.authenticated,
		Registered:    s.registered,
		ConnectedAt:   s.connectedAt,
		LastHeartbeat: s.lastHeartbeat,
		DeviceName:    s.deviceName,
		AppVersion:    s.appVersion,
		Model:         s.model,
	}
}

// IsAdmin uses the signed principal namespace, never host registration state.
func (s *Session) IsAdmin() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.authenticated && strings.HasPrefix(s.authenticatedDeviceID, "ADMIN-") && len(s.authenticatedDeviceID) > len("ADMIN-")
}
