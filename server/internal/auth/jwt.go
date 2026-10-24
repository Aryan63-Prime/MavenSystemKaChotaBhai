package auth

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type Claims struct {
	DeviceID string `json:"device_id"`
	jwt.RegisteredClaims
}

func Validate(tokenString string, secret string) (*Claims, error) {

	token, err := jwt.ParseWithClaims(
		tokenString,
		&Claims{},
		func(token *jwt.Token) (interface{}, error) {
			return []byte(secret), nil
		},
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithExpirationRequired(),
		jwt.WithIssuer("project-sentinel"),
	)

	if err != nil {
		return nil, fmt.Errorf("validate jwt: %w", err)
	}

	claims, ok := token.Claims.(*Claims)

	if !ok || !token.Valid {
		return nil, errors.New("invalid token")
	}

	return claims, nil
}

// GenerateToken mints a signed HS256 JWT for a device with 10-year validity.
func GenerateToken(deviceID string, secret string) (string, error) {
	return GenerateTokenWithTTL(deviceID, secret, 10*365*24*time.Hour)
}

// GenerateTokenWithTTL mints a signed HS256 JWT with custom TTL duration.
func GenerateTokenWithTTL(deviceID string, secret string, ttl time.Duration) (string, error) {
	now := time.Now()
	claims := Claims{
		DeviceID: deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			Subject:   deviceID,
			Issuer:    "project-sentinel",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(secret))
}
