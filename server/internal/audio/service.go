package audio

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/google/uuid"
)

// ErrMissingDeviceID indicates a LISTEN or STOP without a target device.
var ErrMissingDeviceID = errors.New("missing device id")

// ListenerRepository manages audio listener mappings in storage.
type ListenerRepository interface {
	SetListener(ctx context.Context, deviceID string, connectionID string) error
	GetListener(ctx context.Context, deviceID string) (string, bool, error)
	RemoveListener(ctx context.Context, deviceID string) error
}

// FrameForwarder sends binary audio frames to a connected client.
type FrameForwarder interface {
	ForwardBinary(connectionID string, data []byte) error
}

// Service manages audio listener state and routes binary frames.
type Service struct {
	mu            sync.Mutex
	listeners     ListenerRepository
	forwarder     FrameForwarder
	activeStreams map[string]uuid.UUID
}

// NewService creates an audio service.
func NewService(listeners ListenerRepository, forwarder FrameForwarder) *Service {
	return &Service{
		listeners:     listeners,
		forwarder:     forwarder,
		activeStreams: make(map[string]uuid.UUID),
	}
}

// SetForwarder sets the frame forwarder after construction.
// This supports two-phase initialization when the gateway is created after the service.
func (s *Service) SetForwarder(forwarder FrameForwarder) {
	s.forwarder = forwarder
}

// ConnectionChecker checks if a connection is currently active.
type ConnectionChecker interface {
	IsConnected(connectionID string) bool
}

// StartListening registers an admin connection as the listener for a device with optional stream UUID.
func (s *Service) StartListening(ctx context.Context, adminConnectionID string, targetDeviceID string, streamID ...uuid.UUID) error {
	cleanTarget := strings.TrimSpace(targetDeviceID)
	if cleanTarget == "" {
		return ErrMissingDeviceID
	}
	if strings.TrimSpace(adminConnectionID) == "" {
		return fmt.Errorf("start listening: missing admin connection id")
	}

	if s.listeners == nil {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	currentListener, found, err := s.listeners.GetListener(ctx, cleanTarget)
	if err != nil {
		return err
	}
	if found && currentListener != adminConnectionID {
		stillActive := true
		if checker, ok := s.forwarder.(ConnectionChecker); ok {
			stillActive = checker.IsConnected(currentListener)
		}
		if stillActive {
			return fmt.Errorf("start listening: device %s already has active listener %s", cleanTarget, currentListener)
		}
	}

	if err := s.listeners.SetListener(ctx, cleanTarget, adminConnectionID); err != nil {
		return err
	}

	if len(streamID) > 0 && streamID[0] != uuid.Nil {
		if s.activeStreams == nil {
			s.activeStreams = make(map[string]uuid.UUID)
		}
		s.activeStreams[cleanTarget] = streamID[0]
	}

	return nil
}

// StopListening removes the listener for a device if owned by the calling admin connection.
func (s *Service) StopListening(ctx context.Context, adminConnectionID string, targetDeviceID string, streamID ...uuid.UUID) error {
	cleanTarget := strings.TrimSpace(targetDeviceID)
	if cleanTarget == "" {
		return ErrMissingDeviceID
	}

	if s.listeners == nil {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	currentListener, found, err := s.listeners.GetListener(ctx, cleanTarget)
	if err != nil {
		return err
	}
	if !found {
		return fmt.Errorf("stop listening: no active listener for device %s", cleanTarget)
	}

	// Enforce connection ownership only if current listener is still active
	if adminConnectionID != "" && currentListener != adminConnectionID {
		stillActive := true
		if checker, ok := s.forwarder.(ConnectionChecker); ok {
			stillActive = checker.IsConnected(currentListener)
		}
		if stillActive {
			return fmt.Errorf("stop listening: unauthorized connection %s does not own listener %s for %s", adminConnectionID, currentListener, cleanTarget)
		}
	}

	if len(streamID) > 0 && streamID[0] != uuid.Nil && s.activeStreams != nil {
		if active, ok := s.activeStreams[cleanTarget]; ok && active != streamID[0] {
			return fmt.Errorf("stop listening: mismatched stream id %s (active is %s)", streamID[0], active)
		}
	}

	if s.activeStreams != nil {
		delete(s.activeStreams, cleanTarget)
	}

	return s.listeners.RemoveListener(ctx, cleanTarget)
}

// ActiveStream returns the active stream UUID for a device if one is registered.
func (s *Service) ActiveStream(deviceID string) (uuid.UUID, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.activeStreams == nil {
		return uuid.Nil, false
	}
	streamID, ok := s.activeStreams[deviceID]
	return streamID, ok
}

// RouteFrame validates and forwards a binary audio frame to the listening admin.
// Returns nil silently if no one is listening (audio is lossy by nature).
// Drops stale or mismatched AUDIO_V2 frames whose stream UUID doesn't match the active stream.
func (s *Service) RouteFrame(ctx context.Context, sourceDeviceID string, frame []byte) error {
	header, err := ParseHeader(frame)
	if err != nil {
		return err
	}

	if s.listeners == nil || s.forwarder == nil {
		return nil
	}

	s.mu.Lock()
	var activeStream uuid.UUID
	var hasActiveStream bool
	if s.activeStreams != nil {
		activeStream, hasActiveStream = s.activeStreams[sourceDeviceID]
	}
	s.mu.Unlock()

	// If packet is AUDIO_V2 (0x03), drop if source device stream does not match active stream
	if header.PacketType == PacketTypeAudioV2 {
		if !hasActiveStream || header.StreamID != activeStream {
			return nil
		}
	}

	connectionID, found, err := s.listeners.GetListener(ctx, sourceDeviceID)
	if err != nil {
		return fmt.Errorf("get listener: %w", err)
	}
	if !found {
		return nil
	}

	return s.forwarder.ForwardBinary(connectionID, frame)
}
