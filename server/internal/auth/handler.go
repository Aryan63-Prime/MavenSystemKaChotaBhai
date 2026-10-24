package auth

import (
	"context"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

// Result contains the successful authentication response and authenticated device identity.
type Result struct {
	DeviceID string
	Response *protocol.Message
}

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{
		service: service,
	}
}

func (h *Handler) Handle(ctx context.Context, message protocol.Message) (*Result, error) {
	_ = ctx

	var payload protocol.AuthMessage
	if err := message.DecodeData(&payload); err != nil {
		return nil, err
	}

	deviceID, err := h.service.Authenticate(payload.Token)
	if err != nil {
		return nil, err
	}

	response, err := protocol.NewMessage(
		protocol.TypeAuthAck,
		message.Sequence,
		protocol.AuthAckMessage{Success: true},
	)
	if err != nil {
		return nil, err
	}

	return &Result{
		DeviceID: deviceID,
		Response: response,
	}, nil
}

// IsIdentityActive checks whether a connected device or admin identity is active.
func (h *Handler) IsIdentityActive(identity string) bool {
	if h == nil || h.service == nil {
		return true
	}
	return h.service.IsIdentityActive(identity)
}

// Service returns the underlying auth service.
func (h *Handler) Service() *Service {
	if h == nil {
		return nil
	}
	return h.service
}

func (h *Handler) HasPermission(identity, requiredPerm string) bool {
	if h == nil || h.service == nil {
		return true
	}
	return h.service.HasPermission(identity, requiredPerm)
}

func (h *Handler) RecordAuditEvent(severity, actorID, action, detail, remoteIP, deviceModel, siliconDNA string) {
	if h != nil && h.service != nil {
		h.service.RecordAuditEvent(severity, actorID, action, detail, remoteIP, deviceModel, siliconDNA)
	}
}
