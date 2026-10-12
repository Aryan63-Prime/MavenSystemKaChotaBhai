package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Claims matches the server's auth.Claims structure exactly.
type Claims struct {
	DeviceID string `json:"device_id"`
	jwt.RegisteredClaims
}

func main() {
	secret := flag.String("secret", os.Getenv("JWT_SECRET"), "JWT signing secret (must match server JWT_SECRET)")
	deviceID := flag.String("device-id", "", "Device ID to embed in the token (required)")
	expiry := flag.Duration("expiry", 24*time.Hour, "Token expiry duration (default: 24 hours)")
	output := flag.String("output", "text", "Output format: text or json")
	flag.Parse()
	if len(*secret) < 32 || *secret == "change_me" {
		fmt.Fprintln(os.Stderr, "JWT_SECRET or -secret must contain at least 32 characters")
		os.Exit(1)
	}
	if *expiry <= 0 {
		fmt.Fprintln(os.Stderr, "expiry must be positive")
		os.Exit(1)
	}

	if *deviceID == "" {
		fmt.Fprintln(os.Stderr, "Error: -device-id is required")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "Usage:")
		fmt.Fprintln(os.Stderr, "  # Generate a host device token:")
		fmt.Fprintln(os.Stderr, "  go run . -device-id HOST-001")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # Generate a host device token with custom secret:")
		fmt.Fprintln(os.Stderr, "  go run . -device-id HOST-001 -secret my-secret")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # Generate an admin token (use an ADMIN-prefixed identity):")
		fmt.Fprintln(os.Stderr, "  go run . -device-id ADMIN-001")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # Generate with custom expiry:")
		fmt.Fprintln(os.Stderr, "  go run . -device-id HOST-001 -expiry 720h")
		os.Exit(1)
	}

	now := time.Now()
	claims := Claims{
		DeviceID: *deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(*expiry)),
			Subject:   *deviceID,
			Issuer:    "project-sentinel",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(*secret))
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error signing token: %v\n", err)
		os.Exit(1)
	}

	if *output == "json" {
		result := map[string]interface{}{
			"token":     tokenString,
			"device_id": *deviceID,
			"issued_at": now.Format(time.RFC3339),
			"expires":   now.Add(*expiry).Format(time.RFC3339),
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(result)
	} else {
		fmt.Println("=== Project Sentinel JWT Token ===")
		fmt.Println()
		fmt.Printf("Device ID : %s\n", *deviceID)
		fmt.Printf("Issued    : %s\n", now.Format(time.RFC3339))
		fmt.Printf("Expires   : %s\n", now.Add(*expiry).Format(time.RFC3339))
		fmt.Println()
		fmt.Println("Token:")
		fmt.Println(tokenString)
		fmt.Println()
		fmt.Println("Paste this token into the app's login screen.")
	}
}
