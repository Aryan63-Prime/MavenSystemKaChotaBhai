package auth

import (
	"errors"
	"strings"
)

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrMissingToken = errors.New("missing token")
)

type Service struct {
	Secret string
}

func New(secret string) *Service {
	return &Service{
		Secret: secret,
	}
}

// BearerToken extracts a bearer token from an Authorization header.
func BearerToken(header string) (string, bool) {
	const prefix = "Bearer "

	if !strings.HasPrefix(header, prefix) {
		return "", false
	}

	token := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	if token == "" {
		return "", false
	}

	return token, true
}

func (s *Service) Authenticate(token string) (string, error) {

	if strings.TrimSpace(token) == "" {
		return "", ErrMissingToken
	}

	claims, err := Validate(token, s.Secret)

	if err != nil {
		return "", ErrInvalidToken
	}

	if strings.TrimSpace(claims.DeviceID) == "" {
		return "", ErrInvalidToken
	}

	return claims.DeviceID, nil
}

// IsAdminIdentity checks the namespace assigned by the trusted token issuer.
func IsAdminIdentity(identity string) bool {
	return strings.HasPrefix(identity, "ADMIN-") && len(identity) > len("ADMIN-")
}
