package admin

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/auth"
)

// Authenticator validates bearer tokens for admin HTTP endpoints.
type Authenticator interface {
	Authenticate(token string) (string, error)
}

// WsDisconnector provides ability to forcefully disconnect admin sessions.
type WsDisconnector interface {
	DisconnectAdminIdentity(adminID string, reason string) int
}

// Handler serves the admin backend HTTP API.
type Handler struct {
	service       *Service
	authenticator Authenticator
	authService   *auth.Service
	disconnector  WsDisconnector
}

// NewHandler creates an admin HTTP handler.
func NewHandler(service *Service, authenticator Authenticator) *Handler {
	if service == nil {
		service = NewService(nil, nil, nil)
	}

	h := &Handler{
		service:       service,
		authenticator: authenticator,
	}
	if as, ok := authenticator.(*auth.Service); ok {
		h.authService = as
	}
	return h
}

func (h *Handler) SetAuthService(as *auth.Service) {
	h.authService = as
}

func (h *Handler) SetWsDisconnector(d WsDisconnector) {
	h.disconnector = d
}

func (h *Handler) getAuthService() *auth.Service {
	if h.authService != nil {
		return h.authService
	}
	if as, ok := h.authenticator.(*auth.Service); ok {
		return as
	}
	return nil
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

// RouteDevice routes /devices/{deviceId}, /devices/{deviceId}/wake, /devices/{deviceId}/battery-history, or /devices/{deviceId}/route.
func (h *Handler) RouteDevice(w http.ResponseWriter, r *http.Request) {
	if strings.HasSuffix(r.URL.Path, "/wake") {
		h.WakeDevice(w, r)
		return
	}
	if strings.HasSuffix(r.URL.Path, "/battery-history") {
		h.GetBatteryHistory(w, r)
		return
	}
	if strings.HasSuffix(r.URL.Path, "/route") {
		h.GetRouteHistory(w, r)
		return
	}
	h.GetDevice(w, r)
}

// GetRouteHistory handles GET /devices/{deviceId}/route.
func (h *Handler) GetRouteHistory(w http.ResponseWriter, r *http.Request) {
	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}

	trimmed := strings.TrimPrefix(r.URL.Path, "/devices/")
	trimmed = strings.TrimSuffix(trimmed, "/route")
	deviceID, err := url.PathUnescape(trimmed)
	if err != nil || strings.TrimSpace(deviceID) == "" || strings.Contains(deviceID, "/") {
		writeError(w, http.StatusBadRequest, "Invalid device ID in path")
		return
	}

	now := time.Now().UTC()
	from := now.Add(-24 * time.Hour)
	to := now.Add(time.Hour)

	if startStr := strings.TrimSpace(r.URL.Query().Get("start")); startStr != "" {
		if t, err := time.Parse(time.RFC3339, startStr); err == nil {
			from = t.UTC()
		} else if t, err := time.Parse("2006-01-02", startStr); err == nil {
			from = t.UTC()
		}
	}

	if endStr := strings.TrimSpace(r.URL.Query().Get("end")); endStr != "" {
		if t, err := time.Parse(time.RFC3339, endStr); err == nil {
			to = t.UTC()
		} else if t, err := time.Parse("2006-01-02", endStr); err == nil {
			to = t.Add(24*time.Hour - time.Nanosecond).UTC()
		}
	}

	points, err := h.service.GetLocationHistory(r.Context(), deviceID, from, to, 2000)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"deviceId": deviceID,
		"points":   points,
	})
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
		if authSvc := h.getAuthService(); authSvc != nil {
			authSvc.RecordAuditEvent("CRITICAL", "UNAUTH_HTTP", "REJECTED_ADMIN_REQUEST", err.Error(), r.RemoteAddr, r.Header.Get("X-Sentinel-Device-Model"), r.Header.Get("X-Sentinel-Silicon-DNA"))
		}
		writeError(w, http.StatusUnauthorized, "Unauthorized")
		return false
	}

	if !auth.IsAdminIdentity(identity) {
		if authSvc := h.getAuthService(); authSvc != nil {
			authSvc.RecordAuditEvent("CRITICAL", identity, "NON_ADMIN_IDENTITY", "Forbidden identity presented", r.RemoteAddr, r.Header.Get("X-Sentinel-Device-Model"), r.Header.Get("X-Sentinel-Silicon-DNA"))
		}
		writeError(w, http.StatusForbidden, "Forbidden")
		return false
	}

	// Verify Sovereign Silicon Hardware Proof if present
	hwNonce := r.Header.Get("X-Sentinel-HW-Nonce")
	hwSig := r.Header.Get("X-Sentinel-HW-Sig")
	hwCert := r.Header.Get("X-Sentinel-HW-Cert")
	if hwNonce != "" && hwSig != "" && hwCert != "" {
		proof := auth.SovereignSiliconProof{
			AdminID:                  identity,
			SiliconDNAHash:           r.Header.Get("X-Sentinel-Silicon-DNA"),
			DeviceModel:              r.Header.Get("X-Sentinel-Device-Model"),
			SocModel:                 r.Header.Get("X-Sentinel-SoC-Model"),
			IsSovereignIqoo13Silicon: strings.EqualFold(r.Header.Get("X-Sentinel-Device-Model"), "I2401"),
			HwNonce:                  hwNonce,
			HwSignature:              hwSig,
			HwCertBase64:             hwCert,
		}
		popRes := auth.GetDefaultPopVerifier().VerifySovereignAdminProof(proof)
		if !popRes.Verified {
			if authSvc := h.getAuthService(); authSvc != nil {
				authSvc.RecordAuditEvent("CRITICAL", identity, "SILICON_SIG_FAILED", popRes.Status, r.RemoteAddr, proof.DeviceModel, proof.SiliconDNAHash)
			}
			writeError(w, http.StatusForbidden, "Hardware silicon signature verification failed: "+popRes.Status)
			return false
		}
		if popRes.Verified && identity == "ADMIN-001" {
			authSvc := h.getAuthService()
			if authSvc != nil {
				if err := authSvc.BindSovereignSilicon("ADMIN-001", proof.DeviceModel, proof.SiliconDNAHash, popRes.SPKIPin); err != nil {
					authSvc.RecordAuditEvent("CRITICAL", identity, "SILICON_BIND_FAILED", err.Error(), r.RemoteAddr, proof.DeviceModel, proof.SiliconDNAHash)
					writeError(w, http.StatusForbidden, err.Error())
					return false
				}
			}
		}
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

// AdminIAMEntry represents an admin identity for IAM administration.
type AdminIAMEntry struct {
	AdminID        string    `json:"adminId"`
	Role           string    `json:"role"`
	Status         string    `json:"status"`
	Label          string    `json:"label"`
	DeviceModel    string    `json:"deviceModel,omitempty"`
	SiliconDNAHash string    `json:"siliconDnaHash,omitempty"`
	SpkiPinSHA256  string    `json:"spkiPinSha256,omitempty"`
	Permissions    []string  `json:"permissions,omitempty"`
	IssuedAt       time.Time `json:"issuedAt"`
	ExpiresAt      time.Time `json:"expiresAt"`
	LastSeenAt     time.Time `json:"lastSeenAt"`
	TokenSHA256    string    `json:"tokenSha256"`
	WsConnected    bool      `json:"wsConnected"`
}

// ListAdminIAMResponse is the response body for GET /admin/iam/list.
type ListAdminIAMResponse struct {
	Success bool            `json:"success"`
	Admins  []AdminIAMEntry `json:"admins"`
}

// IssueAdminIAMRequest is the request body for POST /admin/iam/issue.
type IssueAdminIAMRequest struct {
	AdminID     string   `json:"adminId"`
	Label       string   `json:"label"`
	ExpiryHours int      `json:"expiryHours"`
	Hours       int      `json:"hours"`
	Permissions []string `json:"permissions,omitempty"`
}

// TargetAdminIAMRequest is the request body for POST /admin/iam/(suspend|activate|revoke|status|permissions).
type TargetAdminIAMRequest struct {
	AdminID     string   `json:"adminId"`
	Target      string   `json:"target"`
	Status      string   `json:"status,omitempty"`
	Permissions []string `json:"permissions,omitempty"`
}

// HandleAdminIAM routes /admin/iam endpoints.
func (h *Handler) HandleAdminIAM(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	if r.Method == http.MethodGet {
		if strings.HasSuffix(path, "/audit") {
			h.HandleAdminIAMAudit(w, r)
			return
		}
		h.HandleAdminIAMList(w, r)
		return
	}
	if r.Method == http.MethodPost {
		if strings.HasSuffix(path, "/issue") {
			h.HandleAdminIAMIssue(w, r)
			return
		}
		if strings.HasSuffix(path, "/suspend") {
			h.HandleAdminIAMSuspend(w, r)
			return
		}
		if strings.HasSuffix(path, "/activate") {
			h.HandleAdminIAMActivate(w, r)
			return
		}
		if strings.HasSuffix(path, "/revoke") {
			h.HandleAdminIAMRevoke(w, r)
			return
		}
		if strings.HasSuffix(path, "/permissions") {
			h.HandleAdminIAMPermissions(w, r)
			return
		}
		if strings.HasSuffix(path, "/panic") {
			h.HandleAdminIAMPanic(w, r)
			return
		}
		if strings.HasSuffix(path, "/status") {
			h.HandleAdminIAMStatus(w, r)
			return
		}
		// Default POST to status transition
		h.HandleAdminIAMStatus(w, r)
		return
	}

	w.Header().Set("Allow", "GET, POST")
	writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
}

// HandleAdminIAMList handles GET /admin/iam/list and GET /admin/iam.
func (h *Handler) HandleAdminIAMList(w http.ResponseWriter, r *http.Request) {
	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}

	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}

	admins := authSvc.ListAdmins()
	liveSessions := h.service.ListSessions()
	activeMap := make(map[string]bool)
	for _, s := range liveSessions {
		if s.Authenticated && s.DeviceID != "" {
			activeMap[s.DeviceID] = true
		}
	}

	entries := make([]AdminIAMEntry, 0, len(admins))
	for _, a := range admins {
		entries = append(entries, AdminIAMEntry{
			AdminID:        a.AdminID,
			Role:           a.Role,
			Status:         a.Status,
			Label:          a.Label,
			DeviceModel:    a.DeviceModel,
			SiliconDNAHash: a.SiliconDNAHash,
			SpkiPinSHA256:  a.SpkiPinSHA256,
			Permissions:    a.Permissions,
			IssuedAt:       a.IssuedAt,
			ExpiresAt:      a.ExpiresAt,
			LastSeenAt:     a.LastSeenAt,
			TokenSHA256:    a.TokenSHA256,
			WsConnected:    activeMap[a.AdminID],
		})
	}

	writeJSON(w, http.StatusOK, ListAdminIAMResponse{
		Success: true,
		Admins:  entries,
	})
}

// HandleAdminIAMIssue handles POST /admin/iam/issue.
func (h *Handler) HandleAdminIAMIssue(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}

	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}

	var req IssueAdminIAMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}

	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))

	hours := req.ExpiryHours
	if hours <= 0 {
		hours = req.Hours
	}
	if hours <= 0 {
		hours = 24
	}

	rawToken, record, err := authSvc.IssueTempAdminToken(callerToken, req.AdminID, req.Label, time.Duration(hours)*time.Hour, req.Permissions)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, "Forbidden: Only Sovereign Root Admin ADMIN-001 can issue admin tokens")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"token":   rawToken,
		"admin":   record,
	})
}

// HandleAdminIAMSuspend handles POST /admin/iam/suspend.
func (h *Handler) HandleAdminIAMSuspend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}

	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}

	var req TargetAdminIAMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}
	target := strings.TrimSpace(req.Target)
	if target == "" {
		target = strings.TrimSpace(req.AdminID)
	}
	if target == "" {
		writeError(w, http.StatusBadRequest, "Target admin ID is required")
		return
	}

	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))
	record, err := authSvc.SetAdminStatus(callerToken, target, auth.StatusSuspended)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, "Forbidden: Only Sovereign Root Admin ADMIN-001 can suspend admins")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	// Immediately kick live WebSocket sessions!
	kicked := 0
	if h.disconnector != nil {
		kicked = h.disconnector.DisconnectAdminIdentity(record.AdminID, "Admin Suspended by Sovereign Root ADMIN-001")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"success":        true,
		"status":         auth.StatusSuspended,
		"kickedSessions": kicked,
		"admin":          record,
	})
}

// HandleAdminIAMActivate handles POST /admin/iam/activate.
func (h *Handler) HandleAdminIAMActivate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}

	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}

	var req TargetAdminIAMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}
	target := strings.TrimSpace(req.Target)
	if target == "" {
		target = strings.TrimSpace(req.AdminID)
	}
	if target == "" {
		writeError(w, http.StatusBadRequest, "Target admin ID is required")
		return
	}

	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))
	record, err := authSvc.SetAdminStatus(callerToken, target, auth.StatusActive)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, "Forbidden: Only Sovereign Root Admin ADMIN-001 can activate admins")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"status":  auth.StatusActive,
		"admin":   record,
	})
}

// HandleAdminIAMRevoke handles POST /admin/iam/revoke.
func (h *Handler) HandleAdminIAMRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}

	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}

	var req TargetAdminIAMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}
	target := strings.TrimSpace(req.Target)
	if target == "" {
		target = strings.TrimSpace(req.AdminID)
	}
	if target == "" {
		writeError(w, http.StatusBadRequest, "Target admin ID is required")
		return
	}

	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))
	record, err := authSvc.SetAdminStatus(callerToken, target, auth.StatusRevoked)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, "Forbidden: Only Sovereign Root Admin ADMIN-001 can revoke admins")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	// Immediately kick live WebSocket sessions!
	kicked := 0
	if h.disconnector != nil {
		kicked = h.disconnector.DisconnectAdminIdentity(record.AdminID, "Admin Revoked by Sovereign Root ADMIN-001")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"success":        true,
		"status":         auth.StatusRevoked,
		"kickedSessions": kicked,
		"admin":          record,
	})
}

// HandleAdminIAMStatus handles unified POST /admin/iam/status.
func (h *Handler) HandleAdminIAMStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}

	var bodyBytes []byte
	if r.Body != nil {
		bodyBytes, _ = io.ReadAll(r.Body)
	}

	var req TargetAdminIAMRequest
	if err := json.Unmarshal(bodyBytes, &req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}

	st := strings.ToUpper(strings.TrimSpace(req.Status))
	r.Body = io.NopCloser(bytes.NewReader(bodyBytes))

	switch st {
	case auth.StatusActive:
		h.HandleAdminIAMActivate(w, r)
	case auth.StatusSuspended:
		h.HandleAdminIAMSuspend(w, r)
	case auth.StatusRevoked:
		h.HandleAdminIAMRevoke(w, r)
	default:
		writeError(w, http.StatusBadRequest, "Invalid status: must be ACTIVE, SUSPENDED, or REVOKED")
	}
}

// HandleAdminIAMPermissions handles POST /admin/iam/permissions.
func (h *Handler) HandleAdminIAMPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}
	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}
	var req TargetAdminIAMRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}
	target := strings.TrimSpace(req.Target)
	if target == "" {
		target = strings.TrimSpace(req.AdminID)
	}
	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))
	record, err := authSvc.SetAdminPermissions(callerToken, target, req.Permissions)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, err.Error())
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"admin":   record,
	})
}

// HandleAdminIAMPanic handles POST /admin/iam/panic.
func (h *Handler) HandleAdminIAMPanic(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
		return
	}
	if !h.authorize(w, r) {
		return
	}
	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}
	callerToken, _ := auth.BearerToken(r.Header.Get("Authorization"))
	suspendedIDs, err := authSvc.PanicLockdownAllTempAdmins(callerToken)
	if err != nil {
		if errors.Is(err, auth.ErrPrivilegeDenied) {
			writeError(w, http.StatusForbidden, err.Error())
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	totalKicked := 0
	if h.disconnector != nil {
		for _, id := range suspendedIDs {
			totalKicked += h.disconnector.DisconnectAdminIdentity(id, "DEFCON-1 Emergency Lockdown by Sovereign Root ADMIN-001")
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"success":         true,
		"suspendedAdmins": suspendedIDs,
		"kickedSessions":  totalKicked,
	})
}

// HandleAdminIAMAudit handles GET /admin/iam/audit.
func (h *Handler) HandleAdminIAMAudit(w http.ResponseWriter, r *http.Request) {
	if !h.allowGET(w, r) || !h.authorize(w, r) {
		return
	}
	authSvc := h.getAuthService()
	if authSvc == nil {
		writeError(w, http.StatusInternalServerError, "IAM Auth service not available")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"events":  authSvc.ListAuditEvents(),
	})
}
