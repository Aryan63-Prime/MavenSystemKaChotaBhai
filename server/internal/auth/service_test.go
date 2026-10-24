package auth

import (
	"errors"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const testSecret = "unit-test-only-jwt-secret-32-bytes!!"

func TestAuthenticateReturnsDeviceID(t *testing.T) {
	token := signedToken(t, testSecret, "HOST-0001")
	service := New(testSecret)

	deviceID, err := service.Authenticate(token)
	if err != nil {
		t.Fatalf("Authenticate returned error: %v", err)
	}

	if deviceID != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", deviceID)
	}
}

func TestAuthenticateRejectsMissingToken(t *testing.T) {
	service := New(testSecret)

	_, err := service.Authenticate("")
	if !errors.Is(err, ErrMissingToken) {
		t.Fatalf("expected ErrMissingToken, got %v", err)
	}
}

func TestAuthenticateRejectsInvalidToken(t *testing.T) {
	token := signedToken(t, "correct_secret_value_32_bytes!", "HOST-0001")
	service := New("wrong_secret_value_32_bytes!!")

	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsExpiredToken(t *testing.T) {
	token := signedTokenWithExpiration(t, testSecret, "HOST-0001", time.Now().Add(-time.Hour))
	service := New(testSecret)

	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsMalformedToken(t *testing.T) {
	service := New(testSecret)

	_, err := service.Authenticate("not-a-jwt")
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsMissingClaims(t *testing.T) {
	token := signedToken(t, testSecret, "")
	service := New(testSecret)

	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func signedToken(t *testing.T, secret string, deviceID string) string {
	t.Helper()
	return signedTokenWithExpiration(t, secret, deviceID, time.Now().Add(time.Hour))
}

func signedTokenWithExpiration(t *testing.T, secret string, deviceID string, expiresAt time.Time) string {
	t.Helper()

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, Claims{
		DeviceID: deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "project-sentinel",
			ExpiresAt: jwt.NewNumericDate(expiresAt),
		},
	})

	signed, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("SignedString returned error: %v", err)
	}

	return signed
}

func TestRejectsMissingExpiryAndWrongIssuer(t *testing.T) {
	for _, claims := range []Claims{
		{DeviceID: "HOST-0001", RegisteredClaims: jwt.RegisteredClaims{Issuer: "project-sentinel"}},
		{DeviceID: "HOST-0001", RegisteredClaims: jwt.RegisteredClaims{Issuer: "other", ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour))}},
	} {
		token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(testSecret))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = New(testSecret).Authenticate(token); !errors.Is(err, ErrInvalidToken) {
			t.Fatal("token without valid required claims accepted")
		}
	}
}

func TestAuthenticateRejectsRevokedAdminToken(t *testing.T) {
	service := New(testSecret)

	// Verify that kSha256LegacyAdmin001 and kSha256Admin002 are present with Status == StatusRevoked in ListAdmins()
	admins := service.ListAdmins()
	foundLegacy001 := false
	found002 := false
	for _, a := range admins {
		if a.TokenSHA256 == kSha256LegacyAdmin001 && a.Status == StatusRevoked {
			foundLegacy001 = true
		}
		if a.TokenSHA256 == kSha256Admin002 && a.Status == StatusRevoked {
			found002 = true
		}
	}
	if !foundLegacy001 {
		t.Fatalf("expected kSha256LegacyAdmin001 with StatusRevoked in ListAdmins()")
	}
	if !found002 {
		t.Fatalf("expected kSha256Admin002 with StatusRevoked in ListAdmins()")
	}

	// Mint a token and revoke it dynamically, verifying rejection
	token := signedToken(t, testSecret, "ADMIN-002")
	service.RevokeToken(token, "test revocation")
	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrRevokedToken) {
		t.Fatalf("expected ErrRevokedToken for revoked token, got %v", err)
	}
	if !IsAuthenticationError(err) {
		t.Fatalf("expected IsAuthenticationError(err) to be true for ErrRevokedToken")
	}
}

func TestSovereignRootAdminAndIAMLifecycle(t *testing.T) {
	service := New(testSecret)

	// 1. Register test Sovereign ADMIN-001 token dynamically
	rootToken, err := GenerateTokenWithTTL("ADMIN-001", testSecret, 24*time.Hour)
	if err != nil {
		t.Fatalf("failed to generate test root token: %v", err)
	}
	service.RegisterSovereignRootToken(rootToken, "I2401")

	id, err := service.Authenticate(rootToken)
	if err != nil || id != "ADMIN-001" {
		t.Fatalf("expected valid authentication for Sovereign ADMIN-001, got id=%s, err=%v", id, err)
	}

	admins := service.ListAdmins()
	var rootAdmin *AdminAccessRecord
	for i := range admins {
		if admins[i].AdminID == "ADMIN-001" && admins[i].Role == RoleSovereignRoot {
			rootAdmin = &admins[i]
			break
		}
	}
	if rootAdmin == nil {
		t.Fatalf("ADMIN-001 must be registered with Role %s", RoleSovereignRoot)
	}
	if rootAdmin.Status != StatusActive {
		t.Fatalf("ADMIN-001 status must be %s", StatusActive)
	}

	// 2. Refuse to suspend or revoke ADMIN-001
	_, err = service.SetAdminStatus(rootToken, "ADMIN-001", StatusSuspended)
	if err == nil {
		t.Fatalf("SetAdminStatus must refuse to suspend SOVEREIGN_ROOT_ADMIN")
	}
	_, err = service.SetAdminStatus(rootToken, "ADMIN-001", StatusRevoked)
	if err == nil {
		t.Fatalf("SetAdminStatus must refuse to revoke SOVEREIGN_ROOT_ADMIN")
	}

	// 3. Issue new temporary admin token for ADMIN-003
	rawToken003, record003, err := service.IssueTempAdminToken(rootToken, "ADMIN-003", "Field Tablet Operator", 24*time.Hour)
	if err != nil {
		t.Fatalf("IssueTempAdminToken failed: %v", err)
	}
	if record003.Role != RoleTempOperator {
		t.Fatalf("issued token must have Role %s, got %s", RoleTempOperator, record003.Role)
	}
	if record003.Status != StatusActive {
		t.Fatalf("issued token status must be %s", StatusActive)
	}

	// 4. Verify ADMIN-003 authenticates successfully
	id003, err := service.Authenticate(rawToken003)
	if err != nil || id003 != "ADMIN-003" {
		t.Fatalf("expected ADMIN-003 authentication success, got id=%s, err=%v", id003, err)
	}
	if !service.IsAdminSessionActive("ADMIN-003", rawToken003) {
		t.Fatalf("IsAdminSessionActive should return true for active ADMIN-003")
	}

	// 5. Suspend ADMIN-003
	updatedRec, err := service.SetAdminStatus(rootToken, "ADMIN-003", StatusSuspended)
	if err != nil || updatedRec.Status != StatusSuspended {
		t.Fatalf("SetAdminStatus to SUSPENDED failed: %v", err)
	}

	// Verify Authenticate() fails with ErrSuspendedToken
	_, err = service.Authenticate(rawToken003)
	if !errors.Is(err, ErrSuspendedToken) {
		t.Fatalf("expected ErrSuspendedToken, got %v", err)
	}
	if service.IsAdminSessionActive("ADMIN-003", rawToken003) {
		t.Fatalf("IsAdminSessionActive should return false for suspended ADMIN-003")
	}

	// 6. Reactivate ADMIN-003
	updatedRec, err = service.SetAdminStatus(rootToken, "ADMIN-003", StatusActive)
	if err != nil || updatedRec.Status != StatusActive {
		t.Fatalf("SetAdminStatus to ACTIVE failed: %v", err)
	}

	// Verify Authenticate() succeeds again
	id003, err = service.Authenticate(rawToken003)
	if err != nil || id003 != "ADMIN-003" {
		t.Fatalf("expected ADMIN-003 to authenticate after reactivation, got %v", err)
	}

	// 7. Revoke ADMIN-003 permanently
	updatedRec, err = service.SetAdminStatus(rootToken, "ADMIN-003", StatusRevoked)
	if err != nil || updatedRec.Status != StatusRevoked {
		t.Fatalf("SetAdminStatus to REVOKED failed: %v", err)
	}
	_, err = service.Authenticate(rawToken003)
	if !errors.Is(err, ErrRevokedToken) {
		t.Fatalf("expected ErrRevokedToken after revoke, got %v", err)
	}

	// 8. Test Non-Root caller cannot perform IAM mutations
	tempSecret := "temp_operator_secret_32_bytes!!"
	tempTok := signedToken(t, tempSecret, "ADMIN-999")
	_, _, err = service.IssueTempAdminToken(tempTok, "ADMIN-004", "Unauthorized", time.Hour)
	if !errors.Is(err, ErrPrivilegeDenied) {
		t.Fatalf("expected ErrPrivilegeDenied for non-root caller, got %v", err)
	}
}

func TestRevokeTokenDynamically(t *testing.T) {
	secret := "test_secret_for_dynamic_revocation"
	token := signedToken(t, secret, "ADMIN-999")
	service := New(secret)

	// First verify it works
	id, err := service.Authenticate(token)
	if err != nil || id != "ADMIN-999" {
		t.Fatalf("expected valid authentication, got id=%s, err=%v", id, err)
	}

	// Revoke the token
	service.RevokeToken(token, "test revocation")

	if !service.IsTokenRevoked(token) {
		t.Fatalf("expected token to be reported as revoked")
	}

	// Verify authentication now fails with ErrRevokedToken
	_, err = service.Authenticate(token)
	if !errors.Is(err, ErrRevokedToken) {
		t.Fatalf("expected ErrRevokedToken after revocation, got %v", err)
	}
}
