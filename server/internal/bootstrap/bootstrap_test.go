package bootstrap

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/xaiop/project-sentinel/server/internal/admin"
	"github.com/xaiop/project-sentinel/server/internal/auth"
)

func TestBuildRegistersAdminDeviceRoutes(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	application, err := Build()
	if err != nil {
		t.Fatalf("Build returned error: %v", err)
	}

	request := httptest.NewRequest(http.MethodGet, "/devices", nil)
	request.Header.Set("Authorization", "Bearer "+signedBootstrapToken(t, application.Config.JWT.Secret))
	response := httptest.NewRecorder()

	application.Gateway.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", response.Code)
	}

	var payload admin.ListDevicesResponse
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatalf("Decode returned error: %v", err)
	}
	if len(payload.Devices) != 0 {
		t.Fatalf("expected no connected devices, got %d", len(payload.Devices))
	}
}

func TestBuildFailsFastWhenPostgresConfiguredButUnreachable(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	t.Setenv("DATABASE_URL", "postgres://invalid_user:invalid_pass@127.0.0.1:54329/nonexistent?sslmode=disable&connect_timeout=1")

	_, err := Build()
	if err == nil {
		t.Fatal("expected Build to fail when configured Postgres is unreachable, got nil error")
	}
}

func signedBootstrapToken(t *testing.T, secret string) string {
	t.Helper()

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, auth.Claims{
		DeviceID: "ADMIN-0001",
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "project-sentinel",
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	})

	signed, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("SignedString returned error: %v", err)
	}

	return signed
}
