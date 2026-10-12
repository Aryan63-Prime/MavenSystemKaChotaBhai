package device

import (
	"errors"
	"testing"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"github.com/xaiop/project-sentinel/server/internal/repository"
)

func TestValidateRegistrationAcceptsMatchingDeviceID(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}, "HOST-0001", false)
	if err != nil {
		t.Fatalf("ValidateRegistration returned error: %v", err)
	}
}

func TestValidateRegistrationRejectsMissingDeviceID(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{}, "HOST-0001", false)
	if !errors.Is(err, ErrMissingDeviceID) {
		t.Fatalf("expected ErrMissingDeviceID, got %v", err)
	}
}

func TestValidateRegistrationRejectsMissingDeviceName(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}, "HOST-0001", false)
	if !errors.Is(err, ErrMissingDeviceName) {
		t.Fatalf("expected ErrMissingDeviceName, got %v", err)
	}
}

func TestValidateRegistrationRejectsMissingAppVersion(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		Model:      "Google Pixel",
	}, "HOST-0001", false)
	if !errors.Is(err, ErrMissingAppVersion) {
		t.Fatalf("expected ErrMissingAppVersion, got %v", err)
	}
}

func TestValidateRegistrationRejectsMissingModel(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
	}, "HOST-0001", false)
	if !errors.Is(err, ErrMissingModel) {
		t.Fatalf("expected ErrMissingModel, got %v", err)
	}
}

func TestValidateRegistrationRejectsMismatchedDeviceID(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0002",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}, "HOST-0001", false)
	if !errors.Is(err, ErrDeviceMismatch) {
		t.Fatalf("expected ErrDeviceMismatch, got %v", err)
	}
}

func TestValidateRegistrationRejectsUnauthenticatedDevice(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}, "", false)
	if !errors.Is(err, ErrUnauthenticatedDevice) {
		t.Fatalf("expected ErrUnauthenticatedDevice, got %v", err)
	}
}

func TestValidateRegistrationRejectsAlreadyRegisteredDevice(t *testing.T) {
	service := NewService()

	err := service.ValidateRegistration(protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}, "HOST-0001", true)
	if !errors.Is(err, ErrAlreadyRegistered) {
		t.Fatalf("expected ErrAlreadyRegistered, got %v", err)
	}
}

func TestEnrollDeviceStoresMetadata(t *testing.T) {
	repo := repository.NewMemoryDeviceRepository()
	service := NewServiceWithRepository(repo)

	msg := protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}

	if err := service.EnrollDevice(t.Context(), msg); err != nil {
		t.Fatalf("EnrollDevice failed: %v", err)
	}

	dev, err := repo.GetEnrolledDevice(t.Context(), "HOST-0001")
	if err != nil {
		t.Fatalf("GetEnrolledDevice failed: %v", err)
	}

	if dev.DeviceName != "Pixel 9" || dev.Model != "Google Pixel" {
		t.Fatalf("Unexpected enrolled device data: %+v", dev)
	}
}
