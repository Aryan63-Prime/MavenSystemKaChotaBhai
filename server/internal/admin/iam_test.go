package admin

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/auth"
)

type mockDisconnector struct {
	kickedCount map[string]int
}

func (m *mockDisconnector) DisconnectAdminIdentity(adminID string, reason string) int {
	if m.kickedCount == nil {
		m.kickedCount = make(map[string]int)
	}
	m.kickedCount[adminID]++
	return 1
}

func setupIAMTestHandler() (*Handler, *auth.Service, string, *mockDisconnector) {
	secret := "test-sovereign-root-iam-secret-key-32b"
	authSvc := auth.New(secret)
	rootToken, err := auth.GenerateTokenWithTTL("ADMIN-001", secret, 24*time.Hour)
	if err != nil {
		panic(err)
	}

	disco := &mockDisconnector{kickedCount: make(map[string]int)}
	sessSource := &fakeSessionSource{
		sessions: []SessionSnapshot{
			{
				ConnectionID:  "WS-CONN-ADMIN-001",
				DeviceID:      "ADMIN-001",
				Authenticated: true,
				ConnectedAt:   time.Now(),
				LastHeartbeat: time.Now(),
			},
		},
	}
	adminSvc := NewService(sessSource, &fakeLocationReader{}, fakeHeartbeatPolicy{})
	handler := NewHandler(adminSvc, authSvc)
	handler.SetAuthService(authSvc)
	handler.SetWsDisconnector(disco)

	return handler, authSvc, rootToken, disco
}

func TestAdminIAMList(t *testing.T) {
	handler, _, rootToken, _ := setupIAMTestHandler()

	req := httptest.NewRequest(http.MethodGet, "/admin/iam/list", nil)
	req.Header.Set("Authorization", "Bearer "+rootToken)
	w := httptest.NewRecorder()

	handler.HandleAdminIAMList(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}

	var res ListAdminIAMResponse
	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if !res.Success {
		t.Fatalf("expected success true")
	}
	if len(res.Admins) == 0 {
		t.Fatalf("expected at least root admin in list")
	}
	foundRoot := false
	for _, a := range res.Admins {
		if a.AdminID == "ADMIN-001" {
			foundRoot = true
			if a.Role != auth.RoleSovereignRoot {
				t.Errorf("expected RoleSovereignRoot, got %s", a.Role)
			}
			if !a.WsConnected {
				t.Errorf("expected root admin to have WsConnected true")
			}
		}
	}
	if !foundRoot {
		t.Fatalf("ADMIN-001 not found in admin list")
	}
}

func TestAdminIAMIssueSuspendActivateRevoke(t *testing.T) {
	handler, authSvc, rootToken, disco := setupIAMTestHandler()

	// 1. Issue ADMIN-003
	issueBody, _ := json.Marshal(IssueAdminIAMRequest{
		AdminID:     "ADMIN-003",
		Label:       "Field Operator Tablet",
		ExpiryHours: 48,
	})
	reqIssue := httptest.NewRequest(http.MethodPost, "/admin/iam/issue", bytes.NewReader(issueBody))
	reqIssue.Header.Set("Authorization", "Bearer "+rootToken)
	wIssue := httptest.NewRecorder()

	handler.HandleAdminIAMIssue(wIssue, reqIssue)
	if wIssue.Code != http.StatusOK {
		t.Fatalf("issue failed: %d: %s", wIssue.Code, wIssue.Body.String())
	}

	var issueRes struct {
		Success bool                   `json:"success"`
		Token   string                 `json:"token"`
		Admin   auth.AdminAccessRecord `json:"admin"`
	}
	if err := json.Unmarshal(wIssue.Body.Bytes(), &issueRes); err != nil {
		t.Fatalf("failed to parse issue response: %v", err)
	}
	if !issueRes.Success || issueRes.Token == "" {
		t.Fatalf("issue token missing")
	}
	if issueRes.Admin.AdminID != "ADMIN-003" || issueRes.Admin.Role != auth.RoleTempOperator {
		t.Fatalf("issued admin metadata mismatch: %+v", issueRes.Admin)
	}

	// Verify newly issued token authenticates
	authedID, err := authSvc.Authenticate(issueRes.Token)
	if err != nil || authedID != "ADMIN-003" {
		t.Fatalf("authenticate failed for new token: %v, authedID=%s", err, authedID)
	}

	// 2. Suspend ADMIN-003
	suspendBody, _ := json.Marshal(TargetAdminIAMRequest{Target: "ADMIN-003"})
	reqSuspend := httptest.NewRequest(http.MethodPost, "/admin/iam/suspend", bytes.NewReader(suspendBody))
	reqSuspend.Header.Set("Authorization", "Bearer "+rootToken)
	wSuspend := httptest.NewRecorder()

	handler.HandleAdminIAMSuspend(wSuspend, reqSuspend)
	if wSuspend.Code != http.StatusOK {
		t.Fatalf("suspend failed: %d: %s", wSuspend.Code, wSuspend.Body.String())
	}
	if disco.kickedCount["ADMIN-003"] != 1 {
		t.Errorf("expected 1 websocket kick for ADMIN-003, got %d", disco.kickedCount["ADMIN-003"])
	}

	// Authentication must fail with ErrSuspendedToken
	_, err = authSvc.Authenticate(issueRes.Token)
	if err != auth.ErrSuspendedToken {
		t.Fatalf("expected ErrSuspendedToken, got %v", err)
	}

	// 3. Activate ADMIN-003
	actBody, _ := json.Marshal(TargetAdminIAMRequest{Target: "ADMIN-003"})
	reqAct := httptest.NewRequest(http.MethodPost, "/admin/iam/activate", bytes.NewReader(actBody))
	reqAct.Header.Set("Authorization", "Bearer "+rootToken)
	wAct := httptest.NewRecorder()

	handler.HandleAdminIAMActivate(wAct, reqAct)
	if wAct.Code != http.StatusOK {
		t.Fatalf("activate failed: %d: %s", wAct.Code, wAct.Body.String())
	}

	// Authentication must succeed again
	authedID, err = authSvc.Authenticate(issueRes.Token)
	if err != nil || authedID != "ADMIN-003" {
		t.Fatalf("authentication after activate failed: %v", err)
	}

	// 4. Revoke ADMIN-003
	revBody, _ := json.Marshal(TargetAdminIAMRequest{Target: "ADMIN-003"})
	reqRev := httptest.NewRequest(http.MethodPost, "/admin/iam/revoke", bytes.NewReader(revBody))
	reqRev.Header.Set("Authorization", "Bearer "+rootToken)
	wRev := httptest.NewRecorder()

	handler.HandleAdminIAMRevoke(wRev, reqRev)
	if wRev.Code != http.StatusOK {
		t.Fatalf("revoke failed: %d: %s", wRev.Code, wRev.Body.String())
	}
	if disco.kickedCount["ADMIN-003"] != 2 {
		t.Errorf("expected 2 total websocket kicks for ADMIN-003, got %d", disco.kickedCount["ADMIN-003"])
	}

	// Authentication must permanently fail with ErrRevokedToken
	_, err = authSvc.Authenticate(issueRes.Token)
	if err != auth.ErrRevokedToken {
		t.Fatalf("expected ErrRevokedToken, got %v", err)
	}
}

func TestAdminIAMSovereignRootProtection(t *testing.T) {
	handler, _, rootToken, _ := setupIAMTestHandler()

	// Attempt to suspend ADMIN-001 -> MUST FAIL
	suspendRoot, _ := json.Marshal(TargetAdminIAMRequest{Target: "ADMIN-001"})
	req := httptest.NewRequest(http.MethodPost, "/admin/iam/suspend", bytes.NewReader(suspendRoot))
	req.Header.Set("Authorization", "Bearer "+rootToken)
	w := httptest.NewRecorder()

	handler.HandleAdminIAMSuspend(w, req)
	if w.Code == http.StatusOK {
		t.Fatalf("expected error when trying to suspend Sovereign Root ADMIN-001, got 200")
	}

	// Attempt to revoke ADMIN-001 -> MUST FAIL
	revokeRoot, _ := json.Marshal(TargetAdminIAMRequest{Target: "ADMIN-001"})
	reqRev := httptest.NewRequest(http.MethodPost, "/admin/iam/revoke", bytes.NewReader(revokeRoot))
	reqRev.Header.Set("Authorization", "Bearer "+rootToken)
	wRev := httptest.NewRecorder()

	handler.HandleAdminIAMRevoke(wRev, reqRev)
	if wRev.Code == http.StatusOK {
		t.Fatalf("expected error when trying to revoke Sovereign Root ADMIN-001, got 200")
	}
}

func TestAdminIAMPermissions(t *testing.T) {
	handler, authSvc, rootToken, _ := setupIAMTestHandler()

	// 1. Issue operator ADMIN-004 with specific permissions
	issueBody, _ := json.Marshal(IssueAdminIAMRequest{
		AdminID:     "ADMIN-004",
		Label:       "Telemetry Only Operator",
		ExpiryHours: 24,
		Permissions: []string{auth.PermViewTelemetry},
	})
	reqIssue := httptest.NewRequest(http.MethodPost, "/admin/iam/issue", bytes.NewReader(issueBody))
	reqIssue.Header.Set("Authorization", "Bearer "+rootToken)
	wIssue := httptest.NewRecorder()
	handler.HandleAdminIAMIssue(wIssue, reqIssue)
	if wIssue.Code != http.StatusOK {
		t.Fatalf("issue failed: %d: %s", wIssue.Code, wIssue.Body.String())
	}

	if !authSvc.HasPermission("ADMIN-004", auth.PermViewTelemetry) {
		t.Errorf("expected ADMIN-004 to have PermViewTelemetry")
	}
	if authSvc.HasPermission("ADMIN-004", auth.PermCameraCapture) {
		t.Errorf("expected ADMIN-004 to NOT have PermCameraCapture")
	}

	// 2. Update permissions via /admin/iam/permissions
	permBody, _ := json.Marshal(TargetAdminIAMRequest{
		AdminID:     "ADMIN-004",
		Permissions: []string{auth.PermViewTelemetry, auth.PermCameraCapture, auth.PermLiveAudio},
	})
	reqPerm := httptest.NewRequest(http.MethodPost, "/admin/iam/permissions", bytes.NewReader(permBody))
	reqPerm.Header.Set("Authorization", "Bearer "+rootToken)
	wPerm := httptest.NewRecorder()
	handler.HandleAdminIAMPermissions(wPerm, reqPerm)
	if wPerm.Code != http.StatusOK {
		t.Fatalf("update permissions failed: %d: %s", wPerm.Code, wPerm.Body.String())
	}

	if !authSvc.HasPermission("ADMIN-004", auth.PermCameraCapture) {
		t.Errorf("expected ADMIN-004 to have PermCameraCapture after update")
	}

	// 3. Attempt to restrict ADMIN-001 -> MUST FAIL
	rootPermBody, _ := json.Marshal(TargetAdminIAMRequest{
		AdminID:     "ADMIN-001",
		Permissions: []string{auth.PermViewTelemetry},
	})
	reqRootPerm := httptest.NewRequest(http.MethodPost, "/admin/iam/permissions", bytes.NewReader(rootPermBody))
	reqRootPerm.Header.Set("Authorization", "Bearer "+rootToken)
	wRootPerm := httptest.NewRecorder()
	handler.HandleAdminIAMPermissions(wRootPerm, reqRootPerm)
	if wRootPerm.Code == http.StatusOK {
		t.Fatalf("expected error when restricting ADMIN-001 permissions, got 200")
	}
}

func TestAdminIAMPanicLockdown(t *testing.T) {
	handler, authSvc, rootToken, disco := setupIAMTestHandler()

	// Issue two operators
	_, _, _ = authSvc.IssueTempAdminToken(rootToken, "ADMIN-005", "Operator 5", 24*time.Hour)
	_, _, _ = authSvc.IssueTempAdminToken(rootToken, "ADMIN-006", "Operator 6", 24*time.Hour)

	// Trigger Panic Lockdown
	reqPanic := httptest.NewRequest(http.MethodPost, "/admin/iam/panic", nil)
	reqPanic.Header.Set("Authorization", "Bearer "+rootToken)
	wPanic := httptest.NewRecorder()
	handler.HandleAdminIAMPanic(wPanic, reqPanic)
	if wPanic.Code != http.StatusOK {
		t.Fatalf("panic lockdown failed: %d: %s", wPanic.Code, wPanic.Body.String())
	}

	var panicRes struct {
		Success         bool     `json:"success"`
		SuspendedAdmins []string `json:"suspendedAdmins"`
	}
	_ = json.Unmarshal(wPanic.Body.Bytes(), &panicRes)
	if !panicRes.Success || len(panicRes.SuspendedAdmins) < 2 {
		t.Fatalf("expected at least 2 suspended admins, got %+v", panicRes)
	}

	// ADMIN-001 must still be active
	admins := authSvc.ListAdmins()
	for _, a := range admins {
		if a.AdminID == "ADMIN-001" && a.Status != auth.StatusActive {
			t.Errorf("ADMIN-001 was suspended during panic lockdown!")
		}
	}
	if disco.kickedCount["ADMIN-005"] == 0 || disco.kickedCount["ADMIN-006"] == 0 {
		t.Errorf("expected disconnector to kick suspended admins: %+v", disco.kickedCount)
	}
}

func TestAdminIAMAudit(t *testing.T) {
	handler, _, rootToken, _ := setupIAMTestHandler()

	reqAudit := httptest.NewRequest(http.MethodGet, "/admin/iam/audit", nil)
	reqAudit.Header.Set("Authorization", "Bearer "+rootToken)
	wAudit := httptest.NewRecorder()
	handler.HandleAdminIAMAudit(wAudit, reqAudit)
	if wAudit.Code != http.StatusOK {
		t.Fatalf("get audit failed: %d: %s", wAudit.Code, wAudit.Body.String())
	}

	var auditRes struct {
		Success bool                   `json:"success"`
		Events  []auth.AdminAuditEvent `json:"events"`
	}
	if err := json.Unmarshal(wAudit.Body.Bytes(), &auditRes); err != nil {
		t.Fatalf("failed to parse audit events: %v", err)
	}
	if !auditRes.Success || len(auditRes.Events) == 0 {
		t.Fatalf("expected audit events to be returned, got %+v", auditRes)
	}
}
