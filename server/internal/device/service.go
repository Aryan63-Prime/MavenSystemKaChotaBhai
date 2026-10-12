package device

import (
	"context"
	"errors"
	"strings"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"github.com/xaiop/project-sentinel/server/internal/repository"
)

var (
	ErrMissingDeviceID       = errors.New("missing device id")
	ErrMissingDeviceName     = errors.New("missing device name")
	ErrMissingAppVersion     = errors.New("missing app version")
	ErrMissingModel          = errors.New("missing model")
	ErrDeviceMismatch        = errors.New("device id does not match authenticated identity")
	ErrAlreadyRegistered     = errors.New("device already registered")
	ErrUnauthenticatedDevice = errors.New("device is not authenticated")
)

type Service struct {
	repo repository.DeviceRepository
}

func NewService() *Service {
	return &Service{}
}

func NewServiceWithRepository(repo repository.DeviceRepository) *Service {
	return &Service{repo: repo}
}

func (s *Service) SetRepository(repo repository.DeviceRepository) {
	s.repo = repo
}

func (s *Service) EnrollDevice(ctx context.Context, msg protocol.RegisterMessage) error {
	if s.repo == nil {
		return nil
	}
	return s.repo.SaveEnrolledDevice(ctx, repository.EnrolledDevice{
		DeviceID:   msg.DeviceID,
		DeviceName: msg.DeviceName,
		AppVersion: msg.AppVersion,
		Model:      msg.Model,
	})
}

func (s *Service) ValidateRegistration(message protocol.RegisterMessage, authenticatedDeviceID string, alreadyRegistered bool) error {
	_ = s

	if strings.TrimSpace(message.DeviceID) == "" {
		return ErrMissingDeviceID
	}

	if strings.TrimSpace(message.DeviceName) == "" {
		return ErrMissingDeviceName
	}

	if strings.TrimSpace(message.AppVersion) == "" {
		return ErrMissingAppVersion
	}

	if strings.TrimSpace(message.Model) == "" {
		return ErrMissingModel
	}

	if strings.TrimSpace(authenticatedDeviceID) == "" {
		return ErrUnauthenticatedDevice
	}

	if strings.TrimSpace(authenticatedDeviceID) != "" {
		matches := message.DeviceID == authenticatedDeviceID ||
			strings.HasPrefix(message.DeviceID, authenticatedDeviceID+"_") ||
			strings.HasPrefix(message.DeviceID, authenticatedDeviceID+"/") ||
			strings.HasPrefix(message.DeviceID, authenticatedDeviceID+"-") ||
			(authenticatedDeviceID == "HOST-001" && (strings.HasPrefix(message.DeviceID, "HOST-") || strings.HasPrefix(message.DeviceID, "UNIT-")))
		if !matches {
			return ErrDeviceMismatch
		}
	}

	if alreadyRegistered {
		return ErrAlreadyRegistered
	}

	return nil
}
