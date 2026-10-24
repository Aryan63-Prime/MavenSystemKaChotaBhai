package dispatcher

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/audio"
	"github.com/xaiop/project-sentinel/server/internal/auth"
	"github.com/xaiop/project-sentinel/server/internal/command"
	"github.com/xaiop/project-sentinel/server/internal/device"
	"github.com/xaiop/project-sentinel/server/internal/file"
	"github.com/xaiop/project-sentinel/server/internal/heartbeat"
	"github.com/xaiop/project-sentinel/server/internal/location"
	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

type Session interface {
	device.Session
	heartbeat.Session
	location.Session
	audio.Session
	file.Session
	command.Session

	IsAuthenticated() bool
	AuthenticatedDeviceID() string
	DeviceID() string
}

// Broadcaster sends messages to authenticated admin sessions.
type Broadcaster interface {
	BroadcastToAdmins(payload []byte)
}

// Result describes a dispatched control response and connection side effects.
type Result struct {
	Message               *protocol.Message
	AuthenticatedDeviceID string
	CloseConnection       bool
}

type Dispatcher struct {
	auth        *auth.Handler
	device      *device.Handler
	heartbeat   *heartbeat.Handler
	location    *location.Handler
	audio       *audio.Handler
	file        *file.Handler
	command     *command.Handler
	broadcaster Broadcaster
	fcm         FcmRegistrar
	popVerifier *auth.HardwarePopVerifier
}

type FcmRegistrar interface {
	RegisterToken(ctx context.Context, deviceID, fcmToken string) error
}

func New(
	authHandler *auth.Handler,
	deviceHandler *device.Handler,
	heartbeatHandler *heartbeat.Handler,
	locationHandler *location.Handler,
	audioHandler *audio.Handler,
	fileHandler *file.Handler,
) *Dispatcher {
	return &Dispatcher{
		auth:        authHandler,
		device:      deviceHandler,
		heartbeat:   heartbeatHandler,
		location:    locationHandler,
		audio:       audioHandler,
		file:        fileHandler,
		popVerifier: auth.GetDefaultPopVerifier(),
	}
}

// SetPopVerifier configures the hardware PoP verifier.
func (d *Dispatcher) SetPopVerifier(v *auth.HardwarePopVerifier) {
	d.popVerifier = v
}

// SetBroadcaster configures the admin broadcast target.
func (d *Dispatcher) SetBroadcaster(b Broadcaster) {
	d.broadcaster = b
}

// SetFileHandler configures the file message handler.
func (d *Dispatcher) SetFileHandler(h *file.Handler) {
	d.file = h
}

// SetCommandHandler configures the command message handler.
func (d *Dispatcher) SetCommandHandler(h *command.Handler) {
	d.command = h
}

// SetFcmRegistrar configures the FCM token registrar.
func (d *Dispatcher) SetFcmRegistrar(r FcmRegistrar) {
	d.fcm = r
	if d.device != nil {
		d.device.SetFcmRegistrar(r)
	}
}

func (d *Dispatcher) Dispatch(ctx context.Context, session Session, data []byte) Result {

	message, err := protocol.DecodeMessage(data)
	if err != nil {
		return Result{
			Message: protocol.NewError(0, 400, "Bad Request"),
		}
	}

	if message.Type == protocol.TypeAuth {
		if session.IsAuthenticated() {
			return Result{Message: protocol.NewError(message.Sequence, 409, "Already authenticated")}
		}
		return d.dispatchAuthenticated(ctx, session, message)
	}

	if !session.IsAuthenticated() {
		return Result{
			Message: protocol.NewError(message.Sequence, 401, "Unauthorized"),
		}
	}

	// Command-Target Gating: Verify admin identity is currently active.
	// If an admin identity was suspended or revoked while the WebSocket is open, immediately terminate.
	if session.IsAdmin() {
		adminID := session.DeviceID()
		if adminID == "" {
			adminID = session.AuthenticatedDeviceID()
		}
		if d.auth != nil && !d.auth.IsIdentityActive(adminID) {
			return Result{
				Message:         protocol.NewError(message.Sequence, 403, "Admin identity suspended or revoked"),
				CloseConnection: true,
			}
		}

		if d.auth != nil {
			switch message.Type {
			case protocol.TypeListen:
				if !d.auth.HasPermission(adminID, auth.PermLiveAudio) {
					d.auth.RecordAuditEvent("CRITICAL", adminID, "BLOCKED_WS_LIVE_AUDIO", "Operator lacks LIVE_AUDIO scope", "", "", "")
					return Result{Message: protocol.NewError(message.Sequence, 403, "Forbidden: Operator lacks LIVE_AUDIO capability")}
				}
			case protocol.TypeFilesListReq, protocol.TypeFileDownloadReq, protocol.TypeFileStopReq:
				if !d.auth.HasPermission(adminID, auth.PermFileAccess) {
					d.auth.RecordAuditEvent("CRITICAL", adminID, "BLOCKED_WS_FILE_ACCESS", "Operator lacks FILE_ACCESS scope", "", "", "")
					return Result{Message: protocol.NewError(message.Sequence, 403, "Forbidden: Operator lacks FILE_ACCESS capability")}
				}
			case protocol.TypeCommand:
				var cmdPeek struct {
					Command string `json:"command"`
				}
				_ = message.DecodeData(&cmdPeek)
				cmdName := cmdPeek.Command
				if cmdName == "CAPTURE_PHOTO" {
					if !d.auth.HasPermission(adminID, auth.PermCameraCapture) {
						d.auth.RecordAuditEvent("CRITICAL", adminID, "BLOCKED_WS_CAMERA", "Operator lacks CAMERA_CAPTURE scope", "", "", "")
						return Result{Message: protocol.NewError(message.Sequence, 403, "Forbidden: Operator lacks CAMERA_CAPTURE capability")}
					}
				} else if cmdName == "START_AUDIO" || cmdName == "RECORD_AUDIO_CLIP" {
					if !d.auth.HasPermission(adminID, auth.PermLiveAudio) {
						d.auth.RecordAuditEvent("CRITICAL", adminID, "BLOCKED_WS_AUDIO_CMD", "Operator lacks LIVE_AUDIO scope for "+cmdName, "", "", "")
						return Result{Message: protocol.NewError(message.Sequence, 403, "Forbidden: Operator lacks LIVE_AUDIO capability")}
					}
				} else if cmdName != "" {
					if !d.auth.HasPermission(adminID, auth.PermDeviceControl) {
						d.auth.RecordAuditEvent("CRITICAL", adminID, "BLOCKED_WS_DEVICE_CTRL", "Operator lacks DEVICE_CONTROL scope for "+cmdName, "", "", "")
						return Result{Message: protocol.NewError(message.Sequence, 403, "Forbidden: Operator lacks DEVICE_CONTROL capability")}
					}
				}
			}
		}
	}

	// Authorization is centralized before dispatching any privileged operation.
	switch message.Type {
	case protocol.TypeListen, protocol.TypeStop, protocol.TypeCommand,
		protocol.TypeFilesListReq, protocol.TypeFileDownloadReq,
		protocol.TypeFileChunkAck, protocol.TypeFileStopReq:
		if !session.IsAdmin() {
			return Result{Message: protocol.NewError(message.Sequence, 403, "Admin identity required")}
		}
	case protocol.TypeRegister:
		if session.IsAdmin() {
			return Result{Message: protocol.NewError(message.Sequence, 403, "Hosts only")}
		}
	case protocol.TypeLocation, protocol.TypeEmergencySOS, protocol.TypeCommandResult,
		protocol.TypeFilesListRes, protocol.TypeFileDownloadRes:
		if session.IsAdmin() || !session.IsRegistered() {
			return Result{Message: protocol.NewError(message.Sequence, 403, "Registered host required")}
		}
	}

	switch message.Type {
	case protocol.TypeRegister:
		response, err := d.device.Handle(ctx, session, message)
		if err != nil {
			return d.dispatchWithErrors(response, err, message.Sequence)
		}
		var reg protocol.RegisterMessage
		_ = message.DecodeData(&reg)
		d.broadcastDeviceUpdate(protocol.DeviceUpdateMessage{
			Event:      protocol.EventConnected,
			DeviceID:   reg.DeviceID,
			DeviceName: strPtr(reg.DeviceName),
			AppVersion: strPtr(reg.AppVersion),
			Model:      strPtr(reg.Model),
		})
		return Result{Message: response}

	case protocol.TypeHeartbeat:
		response, err := d.heartbeat.Handle(ctx, session, message)
		result := d.dispatchWithErrors(response, err, message.Sequence)
		if err == nil {
			isAdmin := false
			if as, ok := session.(interface{ IsAdmin() bool }); ok {
				isAdmin = as.IsAdmin()
			}
			isRegistered := true
			if rs, ok := session.(interface{ IsRegistered() bool }); ok {
				isRegistered = rs.IsRegistered()
			}
			deviceID := session.DeviceID()
			if !isAdmin && isRegistered && deviceID != "" {
				ts := time.Now().UTC().Format(time.RFC3339)
				var modelPtr *string
				if ms, ok := session.(interface{ Model() string }); ok {
					m := ms.Model()
					if m != "" {
						modelPtr = &m
					}
				}
				d.broadcastDeviceUpdate(protocol.DeviceUpdateMessage{
					Event:     protocol.EventHeartbeat,
					DeviceID:  deviceID,
					Timestamp: &ts,
					Model:     modelPtr,
				})
			}
		}
		return result

	case protocol.TypeLocation:
		response, err := d.location.Handle(ctx, session, message)
		result := d.dispatchWithErrors(response, err, message.Sequence)
		if err == nil {
			var loc protocol.LocationMessage
			_ = message.DecodeData(&loc)
			var modelPtr *string
			if ms, ok := session.(interface{ Model() string }); ok {
				m := ms.Model()
				if m != "" {
					modelPtr = &m
				}
			}
			deviceID := session.DeviceID()
			if deviceID == "" {
				deviceID = session.AuthenticatedDeviceID()
			}
			d.broadcastDeviceUpdate(protocol.DeviceUpdateMessage{
				Event:     protocol.EventLocation,
				DeviceID:  deviceID,
				Latitude:  &loc.Latitude,
				Longitude: &loc.Longitude,
				Accuracy:  &loc.Accuracy,
				Battery:   &loc.Battery,
				Network:   &loc.Network,
				Model:     modelPtr,
			})
		}
		return result

	case protocol.TypeListen:
		response, err := d.dispatchAudio(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeFilesListReq:
		response, err := d.file.HandleFilesListReq(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeFilesListRes:
		deviceID := session.DeviceID()
		if deviceID == "" {
			deviceID = session.AuthenticatedDeviceID()
		}
		err := d.file.HandleFilesListRes(ctx, deviceID, message)
		return d.dispatchWithErrors(nil, err, message.Sequence)

	case protocol.TypeFileDownloadReq:
		response, err := d.file.HandleFileDownloadReq(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeFileDownloadRes:
		deviceID := session.DeviceID()
		if deviceID == "" {
			deviceID = session.AuthenticatedDeviceID()
		}
		err := d.file.HandleFileDownloadRes(ctx, deviceID, message)
		return d.dispatchWithErrors(nil, err, message.Sequence)

	case protocol.TypeFileChunkAck:
		response, err := d.file.HandleFileChunkAck(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeFileStopReq:
		response, err := d.file.HandleFileStopReq(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeCommand:
		if d.command == nil {
			return Result{Message: protocol.NewError(message.Sequence, 500, "Command handler not configured")}
		}
		response, err := d.command.HandleCommand(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeCommandResult:
		if d.command == nil {
			return Result{Message: protocol.NewError(message.Sequence, 500, "Command handler not configured")}
		}
		d.enrichTelemetryCommandResult(session, &message)
		err := d.command.HandleCommandResult(ctx, session, message)
		return d.dispatchWithErrors(nil, err, message.Sequence)

	case "TELEMETRY_REPORT":
		d.enrichTelemetryDirect(session, &message)
		if d.broadcaster != nil {
			rawMsg, err := json.Marshal(message)
			if err == nil {
				d.broadcaster.BroadcastToAdmins(rawMsg)
			}
		}
		return Result{Message: nil}

	case protocol.TypeStop:
		response, err := d.dispatchStop(ctx, session, message)
		return d.dispatchWithErrors(response, err, message.Sequence)

	case protocol.TypeEmergencySOS:
		var sos protocol.EmergencySOSMessage
		_ = message.DecodeData(&sos)
		var modelPtr *string
		if ms, ok := session.(interface{ Model() string }); ok {
			m := ms.Model()
			if m != "" {
				modelPtr = &m
			}
		}
		var accPtr *float32
		if sos.Accuracy > 0 {
			acc := float32(sos.Accuracy)
			accPtr = &acc
		}
		ts := time.Now().UTC().Format(time.RFC3339)
		deviceID := session.DeviceID()
		if deviceID == "" {
			deviceID = session.AuthenticatedDeviceID()
		}
		d.broadcastDeviceUpdate(protocol.DeviceUpdateMessage{
			Event:         protocol.EventEmergencySOS,
			DeviceID:      deviceID,
			Latitude:      &sos.Latitude,
			Longitude:     &sos.Longitude,
			Accuracy:      accPtr,
			Battery:       &sos.Battery,
			Timestamp:     &ts,
			Model:         modelPtr,
			TriggerReason: strPtr(sos.TriggerReason),
			ImpactGForce:  &sos.ImpactGForce,
		})
		if d.broadcaster != nil {
			d.broadcaster.BroadcastToAdmins(data)
		}
		return Result{Message: nil}

	case protocol.TypePing:
		response, err := protocol.NewMessage(protocol.TypePong, message.Sequence, protocol.PongMessage{})
		if err != nil {
			return Result{
				Message: protocol.NewError(message.Sequence, 500, "Internal Server Error"),
			}
		}
		return Result{Message: response}

	default:
		return Result{
			Message: protocol.NewError(message.Sequence, 400, "Bad Request"),
		}
	}
}

// DispatchBinary routes a binary audio frame or file chunk to the correct handler.
func (d *Dispatcher) DispatchBinary(ctx context.Context, session Session, data []byte) error {
	if !session.IsAuthenticated() || !session.IsRegistered() || session.IsAdmin() {
		return nil
	}

	if len(data) == 0 {
		return nil
	}

	packetType := data[0]

	deviceID := session.DeviceID()
	if deviceID == "" {
		deviceID = session.AuthenticatedDeviceID()
	}

	switch packetType {
	case 0x01, 0x03: // Audio (0x01 = V1, 0x03 = AUDIO_V2)
		if d.audio == nil {
			return nil
		}
		return d.audio.HandleFrame(ctx, deviceID, data)

	case 0x02: // File Chunk
		if d.file == nil {
			return nil
		}
		return d.file.HandleBinaryChunk(ctx, deviceID, data)

	default:
		return errors.New("unknown packet type")
	}
}

func (d *Dispatcher) dispatchAudio(ctx context.Context, session Session, message protocol.Message) (*protocol.Message, error) {
	if d.audio == nil {
		return nil, nil
	}

	return d.audio.HandleListen(ctx, session, message)
}

func (d *Dispatcher) dispatchStop(ctx context.Context, session Session, message protocol.Message) (*protocol.Message, error) {
	if d.audio == nil {
		return nil, nil
	}

	return d.audio.HandleStop(ctx, session, message)
}

func (d *Dispatcher) dispatchAuthenticated(ctx context.Context, session Session, message protocol.Message) Result {
	_ = session

	result, err := d.auth.Handle(ctx, message)
	if err != nil {
		return d.dispatchAuthenticationError(err, message.Sequence)
	}

	return Result{
		Message:               result.Response,
		AuthenticatedDeviceID: result.DeviceID,
	}
}

func (d *Dispatcher) dispatchWithErrors(response *protocol.Message, err error, sequence int64) Result {
	if err == nil {
		return Result{Message: response}
	}

	if errors.Is(err, device.ErrDeviceMismatch) {
		return Result{
			Message: protocol.NewError(sequence, 403, "Forbidden"),
		}
	}

	if errors.Is(err, device.ErrAlreadyRegistered) {
		return Result{
			Message: protocol.NewError(sequence, 409, "Already Registered"),
		}
	}

	return Result{
		Message: protocol.NewError(sequence, 400, "Bad Request"),
	}
}

func (d *Dispatcher) dispatchAuthenticationError(err error, sequence int64) Result {
	if auth.IsAuthenticationError(err) {
		return Result{
			Message:         protocol.NewError(sequence, 401, "Unauthorized"),
			CloseConnection: true,
		}
	}

	return Result{
		Message:         protocol.NewError(sequence, 400, "Bad Request"),
		CloseConnection: true,
	}
}

// broadcastDeviceUpdate serializes a DEVICE_UPDATE message and sends it to all admin sessions.
// Failures are silently ignored — broadcast is best-effort.
func (d *Dispatcher) broadcastDeviceUpdate(update protocol.DeviceUpdateMessage) {
	if d.broadcaster == nil {
		return
	}

	msg, err := protocol.NewMessage(protocol.TypeDeviceUpdate, 0, update)
	if err != nil {
		return
	}

	payload, err := json.Marshal(msg)
	if err != nil {
		return
	}

	d.broadcaster.BroadcastToAdmins(payload)
}

// BroadcastDisconnect broadcasts a device disconnection event to admin sessions.
// Called from the gateway readLoop cleanup when a host session disconnects.
func (d *Dispatcher) BroadcastDisconnect(deviceID string, model ...string) {
	if deviceID == "" {
		return
	}

	var modelPtr *string
	if len(model) > 0 && model[0] != "" {
		m := model[0]
		modelPtr = &m
	}

	d.broadcastDeviceUpdate(protocol.DeviceUpdateMessage{
		Event:    protocol.EventDisconnected,
		DeviceID: deviceID,
		Model:    modelPtr,
	})
}

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func (d *Dispatcher) enrichTelemetryCommandResult(session Session, msg *protocol.Message) {
	if msg == nil || len(msg.Data) == 0 {
		return
	}
	var cmdRes protocol.CommandResultMessage
	if err := msg.DecodeData(&cmdRes); err != nil {
		return
	}
	if cmdRes.Command != "TELEMETRY_REPORT" {
		return
	}

	deviceID := session.DeviceID()
	if deviceID == "" {
		deviceID = session.AuthenticatedDeviceID()
	}
	if cmdRes.DeviceID == "" {
		cmdRes.DeviceID = deviceID
	}
	if cmdRes.Payload == nil {
		cmdRes.Payload = make(map[string]interface{})
	}

	verifier := d.popVerifier
	if verifier == nil {
		verifier = auth.GetDefaultPopVerifier()
	}
	res := verifier.VerifyTelemetryPop(deviceID, cmdRes.Payload)
	cmdRes.Payload["serverPopVerified"] = res.Verified
	cmdRes.Payload["serverPopStatus"] = res.Status

	if enrichedData, err := json.Marshal(cmdRes); err == nil {
		msg.Data = enrichedData
	}
}

func (d *Dispatcher) enrichTelemetryDirect(session Session, msg *protocol.Message) {
	if msg == nil || len(msg.Data) == 0 {
		return
	}
	var payload map[string]interface{}
	if err := msg.DecodeData(&payload); err != nil || payload == nil {
		payload = make(map[string]interface{})
	}

	deviceID := session.DeviceID()
	if deviceID == "" {
		deviceID = session.AuthenticatedDeviceID()
	}

	verifier := d.popVerifier
	if verifier == nil {
		verifier = auth.GetDefaultPopVerifier()
	}
	res := verifier.VerifyTelemetryPop(deviceID, payload)
	payload["serverPopVerified"] = res.Verified
	payload["serverPopStatus"] = res.Status

	if enrichedData, err := json.Marshal(payload); err == nil {
		msg.Data = enrichedData
	}
}
