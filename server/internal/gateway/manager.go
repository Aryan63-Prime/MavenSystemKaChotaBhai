package gateway

import (
	"strings"
	"sync"
)

type Manager struct {
	mu sync.RWMutex

	sessions map[string]*Session
}

func NewManager() *Manager {
	return &Manager{
		sessions: make(map[string]*Session),
	}
}

func (m *Manager) Add(session *Session) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.sessions[session.ConnectionID()] = session
}

func (m *Manager) Remove(connectionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.sessions, connectionID)
}

func (m *Manager) Get(connectionID string) (*Session, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	session, ok := m.sessions[connectionID]
	return session, ok
}

func (m *Manager) Count() int {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return len(m.sessions)
}

// Snapshots returns read-only copies of all connected sessions.
func (m *Manager) Snapshots() []SessionSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	snapshots := make([]SessionSnapshot, 0, len(m.sessions))
	for _, session := range m.sessions {
		snapshots = append(snapshots, session.Snapshot())
	}

	return snapshots
}

// HasOtherActiveSession reports whether another active session exists for deviceID.
func (m *Manager) HasOtherActiveSession(deviceID string, excludeConnectionID string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()

	target := strings.TrimSpace(deviceID)
	if target == "" || target == "HOST-001" {
		return false
	}
	for _, session := range m.sessions {
		snap := session.Snapshot()
		if snap.ConnectionID != excludeConnectionID && snap.Authenticated && snap.Registered && snap.DeviceID == target {
			return true
		}
	}
	return false
}

// SnapshotByDeviceID resolves exact identities before explicit legacy aliases.
// An alias must identify one device; shared tokens and model names are not identities.
func (m *Manager) SnapshotByDeviceID(deviceID string) (SessionSnapshot, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	target := strings.TrimSpace(deviceID)
	if target == "" {
		return SessionSnapshot{}, false
	}
	var exact, aliases []SessionSnapshot
	for _, session := range m.sessions {
		snapshot := session.Snapshot()
		if !snapshot.Authenticated || !snapshot.Registered {
			continue
		}
		if snapshot.ConnectionID == target || snapshot.DeviceID+"_"+snapshot.ConnectionID == target {
			return snapshot, true
		}
		if snapshot.DeviceID == target || snapshot.DeviceID+"_"+snapshot.Model == target {
			exact = append(exact, snapshot)
			continue
		}
		// Compatibility with old hardware callsigns and token_model UI keys.
		callsign := ""
		if strings.HasPrefix(snapshot.DeviceID, "HOST-001-") {
			callsign = "HOST-" + strings.TrimPrefix(snapshot.DeviceID, "HOST-001-")
		}
		legacyModel := "HOST-001_" + snapshot.Model
		if (callsign != "" && callsign == target) ||
			(snapshot.Model != "" && target == legacyModel && strings.HasPrefix(snapshot.DeviceID, "HOST-001-")) {
			aliases = append(aliases, snapshot)
		}
	}
	matches := exact
	if len(matches) == 0 {
		matches = aliases
	}
	if len(matches) == 0 {
		return SessionSnapshot{}, false
	}
	selected := matches[0]
	for _, candidate := range matches[1:] {
		// Reconnects of a hardware-specific identity can overlap briefly.
		// A generic token cannot establish that two sessions are the same phone.
		if candidate.DeviceID != selected.DeviceID || candidate.Model != selected.Model || selected.DeviceID == "HOST-001" {
			return SessionSnapshot{}, false
		}
		if candidate.ConnectedAt.After(selected.ConnectedAt) ||
			(candidate.ConnectedAt.Equal(selected.ConnectedAt) && candidate.ConnectionID > selected.ConnectionID) {
			selected = candidate
		}
	}
	return selected, true
}

// CloseAll cancels and closes every active session connection.
// Each readLoop's deferred cleanup handles channel close and manager removal.
func (m *Manager) CloseAll() {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, session := range m.sessions {
		if session.client != nil {
			session.client.Cancel()
			_ = session.client.Conn.Close()
		}
	}
}

// ForEachAdmin calls fn for every authenticated admin session.
// Admin sessions are identified by being authenticated with no device ID.
func (m *Manager) ForEachAdmin(fn func(client *Client)) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, session := range m.sessions {
		if session.IsAdmin() && session.client != nil {
			fn(session.client)
		}
	}
}
