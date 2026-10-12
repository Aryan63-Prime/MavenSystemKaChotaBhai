package gateway

import (
	"testing"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

func TestSession_IsAdmin_AuthenticatedNoRegistration(t *testing.T) {
	s := NewSession("conn-1", time.Now(), nil)
	s.SetAuthenticated("ADMIN-001")

	if !s.IsAdmin() {
		t.Error("expected IsAdmin=true for authenticated session without registration")
	}
}

func TestSession_IsAdmin_NotAuthenticated(t *testing.T) {
	s := NewSession("conn-1", time.Now(), nil)

	if s.IsAdmin() {
		t.Error("expected IsAdmin=false for unauthenticated session")
	}
}

func TestSession_IsAdmin_RegisteredHostSession(t *testing.T) {
	s := NewSession("conn-1", time.Now(), nil)
	s.SetAuthenticated("HOST-001")
	s.SetRegistered(protocol.RegisterMessage{
		DeviceID:   "HOST-001",
		DeviceName: "Test Device",
		AppVersion: "1.0",
		Model:      "Pixel",
	})

	if s.IsAdmin() {
		t.Error("expected IsAdmin=false for registered host session")
	}
}

func TestHostIsNotAdminBeforeRegistration(t *testing.T) {
	s := NewSession("host", time.Now(), nil)
	s.SetAuthenticated("HOST-001")
	if s.IsAdmin() {
		t.Fatal("host must never gain admin rights before registration")
	}
	s.SetRegistered(protocol.RegisterMessage{DeviceID: "HOST-001-VIVO-AAAA"})
	if s.AuthenticatedDeviceID() != "HOST-001" || s.DeviceID() != "HOST-001-VIVO-AAAA" {
		t.Fatal("principal and registered device must be separate")
	}
}
