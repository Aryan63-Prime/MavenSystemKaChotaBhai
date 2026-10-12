package device

import (
	"context"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

type Session interface {
	AuthenticatedDeviceID() string
	IsRegistered() bool
	SetRegistered(message protocol.RegisterMessage)
}

type FcmRegistrar interface {
	RegisterToken(ctx context.Context, deviceID, fcmToken string) error
}

type Handler struct {
	service *Service
	fcm     FcmRegistrar
}

func NewHandler(service *Service) *Handler {
	return &Handler{
		service: service,
	}
}

func (h *Handler) SetFcmRegistrar(fcm FcmRegistrar) {
	h.fcm = fcm
}

func (h *Handler) Handle(ctx context.Context, session Session, message protocol.Message) (*protocol.Message, error) {
	var payload protocol.RegisterMessage
	if err := message.DecodeData(&payload); err != nil {
		return nil, err
	}

	if err := h.service.ValidateRegistration(payload, session.AuthenticatedDeviceID(), session.IsRegistered()); err != nil {
		return nil, err
	}

	if h.fcm != nil && payload.FcmToken != "" {
		if err := h.fcm.RegisterToken(ctx, payload.DeviceID, payload.FcmToken); err != nil {
			return nil, err
		}
	}

	if err := h.service.EnrollDevice(ctx, payload); err != nil {
		return nil, err
	}

	session.SetRegistered(payload)

	return protocol.NewMessage(
		protocol.TypeRegisterAck,
		message.Sequence,
		protocol.RegisterAckMessage{Success: true},
	)
}
