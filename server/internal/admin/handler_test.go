package admin

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHandlerListDevicesRequiresAuthorization(t *testing.T) {
	handler := NewHandler(NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{}), fakeAuthenticator{})
	request := httptest.NewRequest(http.MethodGet, "/devices", nil)
	response := httptest.NewRecorder()

	handler.ListDevices(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", response.Code)
	}
}

func TestHandlerListDevicesReturnsDevices(t *testing.T) {
	now := time.Date(2026, time.July, 9, 12, 0, 0, 0, time.UTC)
	handler := NewHandler(
		NewService(&fakeSessionSource{
			sessions: []SessionSnapshot{
				{
					ConnectionID:  "CONN-1",
					DeviceID:      "HOST-0001",
					Authenticated: true,
					Registered:    true,
					ConnectedAt:   now,
					LastHeartbeat: now,
				},
			},
		}, &fakeLocationReader{}, fakeHeartbeatPolicy{}),
		fakeAuthenticator{authorized: true},
	)
	request := httptest.NewRequest(http.MethodGet, "/devices", nil)
	request.Header.Set("Authorization", "Bearer valid-token")
	response := httptest.NewRecorder()

	handler.ListDevices(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", response.Code)
	}

	var payload ListDevicesResponse
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatalf("Decode returned error: %v", err)
	}
	if len(payload.Devices) != 1 || payload.Devices[0].DeviceID != "HOST-0001" {
		t.Fatalf("unexpected devices response: %+v", payload.Devices)
	}
}

func TestHandlerGetDeviceReturnsDevice(t *testing.T) {
	now := time.Date(2026, time.July, 9, 12, 0, 0, 0, time.UTC)
	handler := NewHandler(
		NewService(&fakeSessionSource{
			sessions: []SessionSnapshot{
				{
					ConnectionID:  "CONN-1",
					DeviceID:      "HOST-0001",
					Authenticated: true,
					Registered:    true,
					ConnectedAt:   now,
					LastHeartbeat: now,
				},
			},
		}, &fakeLocationReader{}, fakeHeartbeatPolicy{}),
		fakeAuthenticator{authorized: true},
	)
	request := httptest.NewRequest(http.MethodGet, "/devices/HOST-0001", nil)
	request.Header.Set("Authorization", "Bearer valid-token")
	response := httptest.NewRecorder()

	handler.GetDevice(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", response.Code)
	}

	var payload Device
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatalf("Decode returned error: %v", err)
	}
	if payload.DeviceID != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", payload.DeviceID)
	}
}

func TestHandlerGetDeviceReturnsNotFound(t *testing.T) {
	handler := NewHandler(NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{}), fakeAuthenticator{authorized: true})
	request := httptest.NewRequest(http.MethodGet, "/devices/HOST-0001", nil)
	request.Header.Set("Authorization", "Bearer valid-token")
	response := httptest.NewRecorder()

	handler.GetDevice(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", response.Code)
	}
}

func TestHandlerGetDeviceRejectsEncodedPathSeparator(t *testing.T) {
	handler := NewHandler(NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{}), fakeAuthenticator{authorized: true})
	request := httptest.NewRequest(http.MethodGet, "/devices/HOST%2F0001", nil)
	request.Header.Set("Authorization", "Bearer valid-token")
	response := httptest.NewRecorder()

	handler.GetDevice(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", response.Code)
	}
}

func TestHandlerRejectsNonGET(t *testing.T) {
	handler := NewHandler(NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{}), fakeAuthenticator{authorized: true})
	request := httptest.NewRequest(http.MethodPost, "/devices", strings.NewReader("{}"))
	request.Header.Set("Authorization", "Bearer valid-token")
	response := httptest.NewRecorder()

	handler.ListDevices(response, request)

	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", response.Code)
	}
}

type fakeAuthenticator struct {
	identity   string
	authorized bool
}

func (a fakeAuthenticator) Authenticate(token string) (string, error) {
	if a.authorized && token == "valid-token" {
		if a.identity != "" {
			return a.identity, nil
		}
		return "ADMIN-0001", nil
	}

	return "", errFakeUnauthorized
}

var errFakeUnauthorized = &fakeUnauthorizedError{}

type fakeUnauthorizedError struct {
}

func (e *fakeUnauthorizedError) Error() string {
	return "unauthorized"
}

func TestHostCredentialCannotReadAdminFleet(t *testing.T) {
	h := NewHandler(NewService(&fakeSessionSource{}, &fakeLocationReader{}, fakeHeartbeatPolicy{}), fakeAuthenticator{authorized: true, identity: "HOST-0001"})
	req := httptest.NewRequest(http.MethodGet, "/devices", nil)
	req.Header.Set("Authorization", "Bearer valid-token")
	rec := httptest.NewRecorder()
	h.ListDevices(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for host token, got %d", rec.Code)
	}
}
