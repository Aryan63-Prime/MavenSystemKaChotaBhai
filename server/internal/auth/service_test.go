package auth

import (
	"errors"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestAuthenticateReturnsDeviceID(t *testing.T) {
	secret := "test_secret"
	token := signedToken(t, secret, "HOST-0001")

	service := New(secret)

	deviceID, err := service.Authenticate(token)
	if err != nil {
		t.Fatalf("Authenticate returned error: %v", err)
	}

	if deviceID != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", deviceID)
	}
}

func TestAuthenticateRejectsMissingToken(t *testing.T) {
	service := New("test_secret")

	_, err := service.Authenticate("")
	if !errors.Is(err, ErrMissingToken) {
		t.Fatalf("expected ErrMissingToken, got %v", err)
	}
}

func TestAuthenticateRejectsInvalidToken(t *testing.T) {
	token := signedToken(t, "correct_secret", "HOST-0001")
	service := New("wrong_secret")

	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsExpiredToken(t *testing.T) {
	token := signedTokenWithExpiration(t, "test_secret", "HOST-0001", time.Now().Add(-time.Hour))
	service := New("test_secret")

	_, err := service.Authenticate(token)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsMalformedToken(t *testing.T) {
	service := New("test_secret")

	_, err := service.Authenticate("not-a-jwt")
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expected ErrInvalidToken, got %v", err)
	}
}

func TestAuthenticateRejectsMissingClaims(t *testing.T) {
	token := signedToken(t, "test_secret", "")
	service := New("test_secret")

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
		token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte("test_secret"))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = New("test_secret").Authenticate(token); !errors.Is(err, ErrInvalidToken) {
			t.Fatal("token without valid required claims accepted")
		}
	}
}
