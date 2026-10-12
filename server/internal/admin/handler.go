package admin

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/xaiop/project-sentinel/server/internal/auth"
)

// Authenticator validates bearer tokens for admin HTTP endpoints.
type Authenticator interface {
	Authenticate(token string) (string, error)
}

// Handler serves the admin backend HTTP API.
type Handler struct {
	service       *Service
	authenticator Authenticator
}

// NewHandler creates an admin HTTP handler.
func NewHandler(service *Service, authenticator Authenticator) *Handler {
	if service == nil {
		service = NewService(nil, nil, nil)
	}

	return &Handler{
		service:       service,
		authenticator: authenticator,
	}
}

// ListDevices handles GET /devices.
func (h *Handler) ListDevices(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/devices" {
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}

	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}

	devices, err := h.service.ListDevices(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "Internal Server Error")
		return
	}

	writeJSON(w, http.StatusOK, ListDevicesResponse{Devices: devices})
}

// RouteDevice routes /devices/{deviceId}, /devices/{deviceId}/wake, or /devices/{deviceId}/battery-history.
func (h *Handler) RouteDevice(w http.ResponseWriter, r *http.Request) {
	if strings.HasSuffix(r.URL.Path, "/wake") {
		h.WakeDevice(w, r)
		return
	}
	if strings.HasSuffix(r.URL.Path, "/battery-history") {
		h.GetBatteryHistory(w, r)
		return
	}
	h.GetDevice(w, r)
}

// GetBatteryHistory handles GET /devices/{deviceId}/battery-history.
func (h *Handler) GetBatteryHistory(w http.ResponseWriter, r *http.Request) {
	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}

	trimmed := strings.TrimPrefix(r.URL.Path, "/devices/")
	trimmed = strings.TrimSuffix(trimmed, "/battery-history")
	deviceID, err := url.PathUnescape(trimmed)
	if err != nil || strings.TrimSpace(deviceID) == "" || strings.Contains(deviceID, "/") {
		writeError(w, http.StatusBadRequest, "Invalid device ID in path")
		return
	}

	limit := 100
	history, err := h.service.GetBatteryHistory(r.Context(), deviceID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"deviceId": deviceID,
		"history":  history,
	})
}

// WakeDevice handles POST /devices/{deviceId}/wake.
func (h *Handler) WakeDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}

	if !h.authorize(w, r) {
		return
	}

	trimmed := strings.TrimPrefix(r.URL.Path, "/devices/")
	trimmed = strings.TrimSuffix(trimmed, "/wake")
	deviceID, err := url.PathUnescape(trimmed)
	if err != nil || strings.TrimSpace(deviceID) == "" || strings.Contains(deviceID, "/") {
		writeError(w, http.StatusBadRequest, "Invalid device ID in path")
		return
	}

	msgID, err := h.service.WakeDevice(r.Context(), deviceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"success":   true,
		"deviceId":  deviceID,
		"messageId": msgID,
	})
}

// GetDevice handles GET /devices/{deviceId}.
func (h *Handler) GetDevice(w http.ResponseWriter, r *http.Request) {
	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}

	deviceID, ok := deviceIDFromPath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}

	device, found, err := h.service.GetDevice(r.Context(), deviceID)
	if errors.Is(err, ErrMissingDeviceID) {
		writeError(w, http.StatusBadRequest, "Bad Request")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "Internal Server Error")
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}

	writeJSON(w, http.StatusOK, device)
}

func (h *Handler) allowGET(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == http.MethodGet {
		return true
	}

	w.Header().Set("Allow", http.MethodGet)
	writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
	return false
}

func (h *Handler) authorize(w http.ResponseWriter, r *http.Request) bool {
	if h.authenticator == nil {
		writeError(w, http.StatusUnauthorized, "Unauthorized")
		return false
	}

	token, ok := auth.BearerToken(r.Header.Get("Authorization"))
	if !ok {
		writeError(w, http.StatusUnauthorized, "Unauthorized")
		return false
	}

	identity, err := h.authenticator.Authenticate(token)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "Unauthorized")
		return false
	}

	if !auth.IsAdminIdentity(identity) {
		writeError(w, http.StatusForbidden, "Forbidden")
		return false
	}

	return true
}

func deviceIDFromPath(path string) (string, bool) {
	const prefix = "/devices/"

	if !strings.HasPrefix(path, prefix) {
		return "", false
	}

	deviceID := strings.TrimPrefix(path, prefix)
	if deviceID == "" || strings.Contains(deviceID, "/") {
		return "", false
	}

	decoded, err := url.PathUnescape(deviceID)
	if err != nil || strings.TrimSpace(decoded) == "" || strings.Contains(decoded, "/") {
		return "", false
	}

	return decoded, true
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, ErrorResponse{Error: message})
}

// ListDevicesResponse is the GET /devices response body.
type ListDevicesResponse struct {
	Devices []Device `json:"devices"`
}

// ErrorResponse is a generic admin API error body.
type ErrorResponse struct {
	Error string `json:"error"`
}
