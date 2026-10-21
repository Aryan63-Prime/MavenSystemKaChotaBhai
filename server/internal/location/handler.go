package location

import (
	"context"
	"strings"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"github.com/xaiop/project-sentinel/server/internal/repository"
)

type Session interface {
	DeviceID() string
	AuthenticatedDeviceID() string
	SetLocation(message protocol.LocationMessage)
}

type BatteryLogger interface {
	SaveBatteryHistory(ctx context.Context, entry repository.BatteryHistoryEntry) error
}

type LocationHistoryLogger interface {
	SaveLocationHistory(ctx context.Context, loc repository.Location) error
}

type Handler struct {
	service       *Service
	batteryLogger BatteryLogger
	historyLogger LocationHistoryLogger
}

func NewHandler(service *Service) *Handler {
	return &Handler{
		service: service,
	}
}

// SetBatteryLogger configures the battery history storage logger.
func (h *Handler) SetBatteryLogger(l BatteryLogger) {
	h.batteryLogger = l
}

// SetLocationHistoryLogger configures the location breadcrumb history storage logger.
func (h *Handler) SetLocationHistoryLogger(l LocationHistoryLogger) {
	h.historyLogger = l
}

func (h *Handler) Handle(ctx context.Context, session Session, message protocol.Message) (*protocol.Message, error) {
	_ = ctx

	var payload protocol.LocationMessage
	if err := message.DecodeData(&payload); err != nil {
		return nil, err
	}

	deviceID := strings.TrimSpace(session.DeviceID())
	if deviceID == "" {
		deviceID = strings.TrimSpace(session.AuthenticatedDeviceID())
	}

	if _, err := h.service.Handle(ctx, deviceID, payload); err != nil {
		return nil, err
	}

	// Only write the hardware-specific callsign alias (e.g. HOST-VIVO-CFAE), NEVER write to shared "HOST-001"!
	if strings.HasPrefix(deviceID, "HOST-001-") {
		suffix := strings.TrimPrefix(deviceID, "HOST-001-")
		if suffix != "" {
			_, _ = h.service.Handle(ctx, "HOST-"+suffix, payload)
		}
	}

	session.SetLocation(payload)

	if h.batteryLogger != nil && payload.Battery > 0 {
		_ = h.batteryLogger.SaveBatteryHistory(ctx, repository.BatteryHistoryEntry{
			DeviceID:       deviceID,
			BatteryPercent: payload.Battery,
			BatteryTempC:   25.0,
			IsCharging:     strings.Contains(strings.ToLower(payload.Network), "charging"),
			ThermalStatus:  "Nominal",
			RecordedAt:     time.Now().UTC(),
		})
	}

	if h.historyLogger != nil && (payload.Latitude != 0 || payload.Longitude != 0) {
		_ = h.historyLogger.SaveLocationHistory(ctx, repository.Location{
			DeviceID:   deviceID,
			Latitude:   payload.Latitude,
			Longitude:  payload.Longitude,
			Accuracy:   payload.Accuracy,
			Battery:    payload.Battery,
			Network:    payload.Network,
			RecordedAt: time.Now().UTC(),
		})
	}

	return nil, nil
}
