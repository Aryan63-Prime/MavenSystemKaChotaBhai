package gateway

import (
	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"testing"
	"time"
)

func fleetHost(m *Manager, conn, id, model string, when time.Time) {
	s := NewSession(conn, when, nil)
	s.SetAuthenticated("HOST-001")
	s.SetRegistered(protocol.RegisterMessage{DeviceID: id, DeviceName: "Phone (HOST-02)", Model: model})
	m.Add(s)
}

func TestFleetResolutionIsUnambiguous(t *testing.T) {
	m := NewManager()
	now := time.Now()
	fleetHost(m, "vivo-a", "HOST-001-VIVO-AAAA", "I2401", now)
	fleetHost(m, "vivo-b", "HOST-001-VIVO-BBBB", "I2401", now.Add(time.Second))
	for _, target := range []string{"HOST-001", "HOST-001_I2401", "HOST-001-VIVO", "AAAA", "HOST-02", "HOST-001-MISSING"} {
		if got, ok := m.SnapshotByDeviceID(target); ok {
			t.Errorf("ambiguous/unknown %q resolved to %s", target, got.ConnectionID)
		}
	}
	for _, target := range []string{"vivo-a", "HOST-001-VIVO-AAAA", "HOST-VIVO-AAAA", "HOST-001-VIVO-AAAA_I2401"} {
		got, ok := m.SnapshotByDeviceID(target)
		if !ok || got.ConnectionID != "vivo-a" {
			t.Errorf("%q: got %+v, %v", target, got, ok)
		}
	}
	m.Remove("vivo-b")
	if got, ok := m.SnapshotByDeviceID("HOST-001_I2401"); !ok || got.ConnectionID != "vivo-a" {
		t.Fatal("unique legacy model key should resolve")
	}
	if _, ok := m.SnapshotByDeviceID("HOST-001-MISSING"); ok {
		t.Fatal("unknown ID must not fall back to only online host")
	}
}

func TestFleetExactIdentityWinsAndReconnectSelectsLatest(t *testing.T) {
	m := NewManager()
	now := time.Now()
	fleetHost(m, "old", "HOST-001-VIVO-AAAA", "I2401", now)
	fleetHost(m, "new", "HOST-001-VIVO-AAAA", "I2401", now.Add(time.Second))
	got, ok := m.SnapshotByDeviceID("HOST-001-VIVO-AAAA")
	if !ok || got.ConnectionID != "new" {
		t.Fatalf("expected newest reconnect: %+v", got)
	}
	fleetHost(m, "legacy", "HOST-001", "I2401", now)
	got, ok = m.SnapshotByDeviceID("HOST-001_I2401")
	if !ok || got.ConnectionID != "legacy" {
		t.Fatalf("exact model key must precede aliases: %+v", got)
	}
	fleetHost(m, "legacy2", "HOST-001", "I2401", now.Add(time.Second))
	if _, ok = m.SnapshotByDeviceID("HOST-001_I2401"); ok {
		t.Fatal("shared token and model cannot identify a phone")
	}
}
