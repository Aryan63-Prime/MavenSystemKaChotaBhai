package repository

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestMemoryDeviceRepository_FcmTokens(t *testing.T) {
	repo := NewMemoryDeviceRepository()
	ctx := context.Background()

	// 1. Initial lookup should return ErrTokenNotFound
	_, err := repo.GetFcmToken(ctx, "HOST-001")
	if !errors.Is(err, ErrTokenNotFound) {
		t.Fatalf("expected ErrTokenNotFound, got %v", err)
	}

	// 2. Save token
	err = repo.SaveFcmToken(ctx, "HOST-001", "token-abc-123", "Pixel9")
	if err != nil {
		t.Fatalf("unexpected error saving token: %v", err)
	}

	// 3. Exact match
	tok, err := repo.GetFcmToken(ctx, "HOST-001")
	if err != nil {
		t.Fatalf("unexpected error getting token: %v", err)
	}
	if tok != "token-abc-123" {
		t.Fatalf("expected token-abc-123, got %s", tok)
	}

	// 4. Prefix or model variant should NOT match (strict exact match)
	_, err = repo.GetFcmToken(ctx, "HOST-001_Pixel9")
	if !errors.Is(err, ErrTokenNotFound) {
		t.Fatalf("expected ErrTokenNotFound for prefix query, got error: %v", err)
	}

	_, err = repo.GetFcmToken(ctx, "HOST-001-other")
	if !errors.Is(err, ErrTokenNotFound) {
		t.Fatalf("expected ErrTokenNotFound for prefix query HOST-001-other, got error: %v", err)
	}
}

func TestMemoryDeviceRepository_EnrolledDevices(t *testing.T) {
	repo := NewMemoryDeviceRepository()
	ctx := context.Background()

	// 1. Initial lookup returns ErrDeviceNotFound
	_, err := repo.GetEnrolledDevice(ctx, "HOST-001")
	if !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("expected ErrDeviceNotFound, got %v", err)
	}

	// 2. Save enrolled device
	dev := EnrolledDevice{
		DeviceID:   "HOST-001",
		DeviceName: "Security Node 1",
		AppVersion: "1.0.0",
		Model:      "iQOO 13",
		LastSeen:   time.Now(),
	}
	err = repo.SaveEnrolledDevice(ctx, dev)
	if err != nil {
		t.Fatalf("unexpected error saving enrolled device: %v", err)
	}

	// 3. Retrieve enrolled device
	saved, err := repo.GetEnrolledDevice(ctx, "HOST-001")
	if err != nil {
		t.Fatalf("unexpected error retrieving enrolled device: %v", err)
	}
	if saved.DeviceID != "HOST-001" || saved.Model != "iQOO 13" {
		t.Fatalf("retrieved device mismatch: %+v", saved)
	}

	// 4. List enrolled devices
	list, err := repo.ListEnrolledDevices(ctx)
	if err != nil {
		t.Fatalf("unexpected error listing devices: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("expected 1 enrolled device, got %d", len(list))
	}
}
