package file

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

var ErrMissingDeviceID = errors.New("missing device id")

type ListenerRepository interface {
	SetListener(ctx context.Context, deviceID string, connectionID string) error
	GetListener(ctx context.Context, deviceID string) (string, bool, error)
	RemoveListener(ctx context.Context, deviceID string) error
}

type MessageForwarder interface {
	ForwardBinary(connectionID string, data []byte) error
	ForwardText(connectionID string, data []byte) error
}

type Service struct {
	listeners ListenerRepository
	forwarder MessageForwarder
}

func NewService(listeners ListenerRepository, forwarder MessageForwarder) *Service {
	return &Service{
		listeners: listeners,
		forwarder: forwarder,
	}
}

func (s *Service) SetForwarder(forwarder MessageForwarder) {
	s.forwarder = forwarder
}

func (s *Service) StartInteracting(ctx context.Context, adminConnectionID string, targetDeviceID string) error {
	if strings.TrimSpace(targetDeviceID) == "" {
		return ErrMissingDeviceID
	}
	if strings.TrimSpace(adminConnectionID) == "" {
		return fmt.Errorf("start interacting: missing admin connection id")
	}

	if s.listeners == nil {
		return nil
	}

	if err := s.listeners.SetListener(ctx, targetDeviceID, adminConnectionID); err != nil {
		return err
	}

	// Register alias so GetListener matches regardless of whether HOST-001-<suffix> or HOST-<suffix> was sent
	if strings.HasPrefix(targetDeviceID, "HOST-001-") {
		shortAlias := "HOST-" + strings.TrimPrefix(targetDeviceID, "HOST-001-")
		_ = s.listeners.SetListener(ctx, shortAlias, adminConnectionID)
	} else if strings.HasPrefix(targetDeviceID, "HOST-") && !strings.HasPrefix(targetDeviceID, "HOST-001-") {
		longAlias := "HOST-001-" + strings.TrimPrefix(targetDeviceID, "HOST-")
		_ = s.listeners.SetListener(ctx, longAlias, adminConnectionID)
	}

	return nil
}

func (s *Service) RouteBinaryChunk(ctx context.Context, sourceDeviceID string, chunk []byte) error {
	if s.listeners == nil || s.forwarder == nil {
		return nil
	}

	connectionID, found, err := s.GetListener(ctx, sourceDeviceID)
	if err != nil {
		return fmt.Errorf("get file listener: %w", err)
	}
	if !found {
		return nil
	}

	return s.forwarder.ForwardBinary(connectionID, chunk)
}

func (s *Service) RouteText(ctx context.Context, targetConnectionID string, payload []byte) error {
	if s.forwarder == nil {
		return nil
	}
	return s.forwarder.ForwardText(targetConnectionID, payload)
}

func (s *Service) GetListener(ctx context.Context, deviceID string) (string, bool, error) {
	if s.listeners == nil {
		return "", false, nil
	}
	connID, found, err := s.listeners.GetListener(ctx, deviceID)
	if err == nil && found {
		return connID, true, nil
	}
	if strings.HasPrefix(deviceID, "HOST-001-") {
		shortAlias := "HOST-" + strings.TrimPrefix(deviceID, "HOST-001-")
		if c, f, e := s.listeners.GetListener(ctx, shortAlias); e == nil && f {
			return c, true, nil
		}
	} else if strings.HasPrefix(deviceID, "HOST-") && !strings.HasPrefix(deviceID, "HOST-001-") {
		longAlias := "HOST-001-" + strings.TrimPrefix(deviceID, "HOST-")
		if c, f, e := s.listeners.GetListener(ctx, longAlias); e == nil && f {
			return c, true, nil
		}
	}
	return connID, found, err
}
