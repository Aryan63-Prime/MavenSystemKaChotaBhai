package dispatcher

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/xaiop/project-sentinel/server/internal/audio"
	"github.com/xaiop/project-sentinel/server/internal/auth"
	"github.com/xaiop/project-sentinel/server/internal/device"
	"github.com/xaiop/project-sentinel/server/internal/heartbeat"
	"github.com/xaiop/project-sentinel/server/internal/location"
	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

func TestDispatchAuthAuthenticatesSession(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{}

	result := dispatcher.Dispatch(context.Background(), session, authRequest(t, "test_secret", "HOST-0001"))

	if result.Message == nil || result.Message.Type != protocol.TypeAuthAck {
		t.Fatalf("expected AUTH_ACK, got %#v", result.Message)
	}

	if session.authenticated {
		t.Fatal("dispatcher must not authenticate session directly")
	}

	if result.AuthenticatedDeviceID != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", result.AuthenticatedDeviceID)
	}
}

func TestDispatchRequiresAuthentication(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{}

	result := dispatcher.Dispatch(context.Background(), session, registerRequest(t, "HOST-0001"))

	assertErrorResponse(t, result.Message, 401)
}

func TestDispatchRegisterStoresDeviceDetails(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
	}

	result := dispatcher.Dispatch(context.Background(), session, registerRequest(t, "HOST-0001"))

	if result.Message == nil || result.Message.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected REGISTER_ACK, got %#v", result.Message)
	}

	if !session.registered {
		t.Fatal("expected session to be registered")
	}

	if session.deviceName != "Pixel 9" {
		t.Fatalf("expected Pixel 9, got %s", session.deviceName)
	}
}

func TestDispatchRejectsMismatchedRegistration(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
	}

	result := dispatcher.Dispatch(context.Background(), session, registerRequest(t, "HOST-0002"))

	assertErrorResponse(t, result.Message, 403)
}

func TestDispatchRejectsDuplicateRegistration(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
		registered:    true,
	}

	result := dispatcher.Dispatch(context.Background(), session, registerRequest(t, "HOST-0001"))

	assertErrorResponse(t, result.Message, 409)
}

func TestDispatchRejectsMalformedRegistration(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
	}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeRegister, 2, protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	}))

	assertErrorResponse(t, result.Message, 400)
}

func TestDispatchHeartbeatUpdatesSession(t *testing.T) {
	now := time.Date(2026, 7, 9, 10, 0, 0, 0, time.UTC)
	dispatcher := newTestDispatcher("test_secret", func() time.Time { return now })
	session := authenticatedSession()

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeHeartbeat, 3, protocol.HeartbeatMessage{}))

	if result.Message == nil || result.Message.Type != protocol.TypeHeartbeatAck {
		t.Fatalf("expected HEARTBEAT_ACK, got %#v", result.Message)
	}

	if !session.lastHeartbeat.Equal(now) {
		t.Fatalf("expected heartbeat %s, got %s", now, session.lastHeartbeat)
	}
}

func TestDispatchLocationStoresValidatedLocation(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := authenticatedSession()

	locationMessage := protocol.LocationMessage{
		Latitude:  28.6139,
		Longitude: 77.2090,
		Accuracy:  5.4,
		Battery:   81,
		Network:   "5G",
	}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeLocation, 4, locationMessage))

	if result.Message != nil {
		t.Fatalf("expected no LOCATION response, got %#v", result.Message)
	}

	if session.lastLocation.Network != "5G" {
		t.Fatalf("expected network 5G, got %s", session.lastLocation.Network)
	}
}

func TestDispatchRejectsInvalidLocation(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := authenticatedSession()

	locationMessage := protocol.LocationMessage{
		Latitude:  91,
		Longitude: 77.2090,
		Accuracy:  5.4,
		Battery:   81,
	}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeLocation, 4, locationMessage))

	assertErrorResponse(t, result.Message, 400)
}

func TestDispatchPingReturnsPong(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := authenticatedSession()

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypePing, 8, protocol.PingMessage{}))

	if result.Message == nil || result.Message.Type != protocol.TypePong {
		t.Fatalf("expected PONG, got %#v", result.Message)
	}
}

func TestDispatchInvalidAuthClosesConnection(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeAuth, 1, protocol.AuthMessage{
		Token: "malformed.jwt.token",
	}))

	assertErrorResponse(t, result.Message, 401)

	if !result.CloseConnection {
		t.Fatal("expected invalid auth to close connection")
	}
}

func newTestDispatcher(secret string, now func() time.Time) *Dispatcher {
	authHandler := auth.NewHandler(auth.New(secret))
	deviceHandler := device.NewHandler(device.NewService())
	heartbeatHandler := heartbeat.NewHandler(heartbeat.NewServiceWithClock(now))
	locationHandler := location.NewHandler(location.NewService())
	audioHandler := audio.NewHandler(audio.NewService(nil, nil))

	return New(authHandler, deviceHandler, heartbeatHandler, locationHandler, audioHandler, nil)
}

func authenticatedSession() *testSession {
	return &testSession{
		registered:    true,
		authenticated: true,
		deviceID:      "HOST-0001",
	}
}

func authRequest(t *testing.T, secret string, deviceID string) []byte {
	t.Helper()

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, auth.Claims{
		DeviceID: deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "project-sentinel",
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	})

	signed, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("SignedString returned error: %v", err)
	}

	return envelope(t, protocol.TypeAuth, 1, protocol.AuthMessage{Token: signed})
}

func registerRequest(t *testing.T, deviceID string) []byte {
	t.Helper()

	return envelope(t, protocol.TypeRegister, 2, protocol.RegisterMessage{
		DeviceID:   deviceID,
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	})
}

func envelope(t *testing.T, messageType protocol.MessageType, sequence int64, data any) []byte {
	t.Helper()

	payload, err := protocol.NewMessage(messageType, sequence, data)
	if err != nil {
		t.Fatalf("NewMessage returned error: %v", err)
	}

	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("Marshal returned error: %v", err)
	}

	return raw
}

func assertErrorResponse(t *testing.T, response *protocol.Message, code int) {
	t.Helper()

	if response == nil || response.Type != protocol.TypeError {
		t.Fatalf("expected ERROR, got %#v", response)
	}

	var errorMessage protocol.ErrorMessage
	if err := response.DecodeData(&errorMessage); err != nil {
		t.Fatalf("DecodeData returned error: %v", err)
	}

	if errorMessage.Code != code {
		t.Fatalf("expected error code %d, got %d", code, errorMessage.Code)
	}
}

type testSession struct {
	connectionID  string
	authenticated bool
	deviceID      string
	registered    bool
	deviceName    string
	lastHeartbeat time.Time
	lastLocation  protocol.LocationMessage
}

func (s *testSession) ConnectionID() string {
	if s.connectionID == "" {
		return "test-conn"
	}
	return s.connectionID
}

func (s *testSession) SetAuthenticated(deviceID string) {
	s.deviceID = deviceID
	s.authenticated = true
}

func (s *testSession) IsAdmin() bool {
	return auth.IsAdminIdentity(s.deviceID)
}

func (s *testSession) IsAuthenticated() bool {
	return s.authenticated
}

func (s *testSession) AuthenticatedDeviceID() string {
	return s.deviceID
}

func (s *testSession) DeviceID() string {
	return s.deviceID
}

func (s *testSession) IsRegistered() bool {
	return s.registered
}

func (s *testSession) SetRegistered(message protocol.RegisterMessage) {
	s.registered = true
	s.deviceID = message.DeviceID
	s.deviceName = message.DeviceName
}

func (s *testSession) SetLastHeartbeat(timestamp time.Time) {
	s.lastHeartbeat = timestamp
}

func (s *testSession) SetLocation(message protocol.LocationMessage) {
	s.lastLocation = message
}

// --- LISTEN / STOP / Binary dispatch tests ---

func TestDispatchListenRequiresAuthentication(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeListen, 5, protocol.ListenMessage{DeviceID: "HOST-0001"}))
	assertErrorResponse(t, result.Message, 401)
}

func TestDispatchListenAuthenticated(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{authenticated: true, deviceID: "ADMIN-0001"}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeListen, 5, protocol.ListenMessage{DeviceID: "HOST-0001"}))

	if result.Message != nil {
		t.Fatalf("expected nil response for LISTEN, got %#v", result.Message)
	}
}

func TestDispatchStopAuthenticated(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{authenticated: true, deviceID: "ADMIN-0001"}

	result := dispatcher.Dispatch(context.Background(), session, envelope(t, protocol.TypeStop, 6, protocol.StopMessage{DeviceID: "HOST-0001"}))

	if result.Message != nil {
		t.Fatalf("expected nil response for STOP, got %#v", result.Message)
	}
}

func TestDispatchBinaryRequiresAuthentication(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := &testSession{}

	err := dispatcher.DispatchBinary(context.Background(), session, testAudioFrame())
	if err != nil {
		t.Fatalf("expected nil for unauthenticated binary, got %v", err)
	}
}

func TestDispatchBinaryAuthenticated(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	session := authenticatedSession()

	err := dispatcher.DispatchBinary(context.Background(), session, testAudioFrame())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func testAudioFrame() []byte {
	buf := make([]byte, 14)
	buf[0] = 0x01 // PacketTypeAudio
	binary.BigEndian.PutUint32(buf[1:5], 1)
	binary.BigEndian.PutUint64(buf[5:13], uint64(time.Now().UnixMilli()))
	buf[13] = 0xAA // one byte of Opus data
	return buf
}

type testBroadcaster struct {
	broadcasts [][]byte
}

func (b *testBroadcaster) BroadcastToAdmins(payload []byte) {
	b.broadcasts = append(b.broadcasts, payload)
}

func TestDispatchEmergencySOSBroadcastsToAdmins(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)
	session := authenticatedSession()

	sosPayload := envelope(t, protocol.TypeEmergencySOS, 10, protocol.EmergencySOSMessage{
		TriggerReason: "FALL_DETECTED",
		ImpactGForce:  4.8,
		Latitude:      28.7041,
		Longitude:     77.1025,
		Accuracy:      10.0,
		Battery:       85,
		Timestamp:     time.Now().Unix(),
	})

	result := dispatcher.Dispatch(context.Background(), session, sosPayload)
	if result.Message != nil {
		t.Fatalf("expected nil result message, got %#v", result.Message)
	}

	if len(broadcaster.broadcasts) == 0 {
		t.Fatal("expected emergency SOS to broadcast to admins")
	}

	var msg protocol.Message
	if err := json.Unmarshal(broadcaster.broadcasts[0], &msg); err != nil {
		t.Fatalf("failed to unmarshal broadcast message: %v", err)
	}

	if msg.Type != protocol.TypeDeviceUpdate {
		t.Fatalf("expected type DEVICE_UPDATE, got %s", msg.Type)
	}

	var update protocol.DeviceUpdateMessage
	if err := json.Unmarshal(msg.Data, &update); err != nil {
		t.Fatalf("failed to unmarshal update data: %v", err)
	}

	if update.Event != protocol.EventEmergencySOS {
		t.Fatalf("expected event emergency_sos, got %s", update.Event)
	}
	if update.DeviceID != "HOST-0001" {
		t.Fatalf("expected deviceId HOST-0001, got %s", update.DeviceID)
	}
	if update.ImpactGForce == nil || *update.ImpactGForce != 4.8 {
		t.Fatalf("expected impact 4.8, got %v", update.ImpactGForce)
	}
}

func TestDispatchHeartbeatBroadcastsDeviceID(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)

	session := authenticatedSession()
	session.deviceID = "HOST-001-VIVO-2D2C"

	heartbeatPayload := envelope(t, protocol.TypeHeartbeat, 11, protocol.HeartbeatMessage{})
	_ = dispatcher.Dispatch(context.Background(), session, heartbeatPayload)

	if len(broadcaster.broadcasts) == 0 {
		t.Fatal("expected heartbeat to broadcast to admins")
	}

	var msg protocol.Message
	if err := json.Unmarshal(broadcaster.broadcasts[0], &msg); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	var update protocol.DeviceUpdateMessage
	if err := json.Unmarshal(msg.Data, &update); err != nil {
		t.Fatalf("failed to unmarshal update: %v", err)
	}

	if update.Event != protocol.EventHeartbeat {
		t.Fatalf("expected heartbeat event, got %s", update.Event)
	}
	if update.DeviceID != "HOST-001-VIVO-2D2C" {
		t.Fatalf("expected deviceId HOST-001-VIVO-2D2C, got %s", update.DeviceID)
	}
}

func TestDispatchLocationBroadcastsDeviceID(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)

	session := authenticatedSession()
	session.deviceID = "HOST-001-VIVO-2D2C"

	locationPayload := envelope(t, protocol.TypeLocation, 12, protocol.LocationMessage{
		Latitude:  28.6,
		Longitude: 77.2,
		Accuracy:  5,
		Battery:   90,
		Network:   "WIFI",
	})
	_ = dispatcher.Dispatch(context.Background(), session, locationPayload)

	if len(broadcaster.broadcasts) == 0 {
		t.Fatal("expected location to broadcast to admins")
	}

	var msg protocol.Message
	if err := json.Unmarshal(broadcaster.broadcasts[0], &msg); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	var update protocol.DeviceUpdateMessage
	if err := json.Unmarshal(msg.Data, &update); err != nil {
		t.Fatalf("failed to unmarshal update: %v", err)
	}

	if update.Event != protocol.EventLocation {
		t.Fatalf("expected location event, got %s", update.Event)
	}
	if update.DeviceID != "HOST-001-VIVO-2D2C" {
		t.Fatalf("expected deviceId HOST-001-VIVO-2D2C, got %s", update.DeviceID)
	}
}

func TestDispatchEmergencySOSBroadcastsDeviceID(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)

	session := authenticatedSession()
	session.deviceID = "HOST-001-VIVO-2D2C"

	sosPayload := envelope(t, protocol.TypeEmergencySOS, 13, protocol.EmergencySOSMessage{
		TriggerReason: "FALL_DETECTED",
		ImpactGForce:  3.5,
		Latitude:      28.6,
		Longitude:     77.2,
		Accuracy:      5,
		Battery:       90,
	})
	_ = dispatcher.Dispatch(context.Background(), session, sosPayload)

	if len(broadcaster.broadcasts) == 0 {
		t.Fatal("expected emergency SOS to broadcast to admins")
	}

	var msg protocol.Message
	if err := json.Unmarshal(broadcaster.broadcasts[0], &msg); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	var update protocol.DeviceUpdateMessage
	if err := json.Unmarshal(msg.Data, &update); err != nil {
		t.Fatalf("failed to unmarshal update: %v", err)
	}

	if update.Event != protocol.EventEmergencySOS {
		t.Fatalf("expected emergency_sos event, got %s", update.Event)
	}
	if update.DeviceID != "HOST-001-VIVO-2D2C" {
		t.Fatalf("expected deviceId HOST-001-VIVO-2D2C, got %s", update.DeviceID)
	}
}

func TestHostCannotIssuePrivilegedRequests(t *testing.T) {
	d := newTestDispatcher("test_secret", time.Now)
	for _, registered := range []bool{false, true} {
		s := &testSession{authenticated: true, deviceID: "HOST-0001", registered: registered}
		for _, kind := range []protocol.MessageType{protocol.TypeListen, protocol.TypeStop, protocol.TypeCommand, protocol.TypeFilesListReq, protocol.TypeFileDownloadReq, protocol.TypeFileChunkAck, protocol.TypeFileStopReq} {
			result := d.Dispatch(context.Background(), s, envelope(t, kind, 1, map[string]any{}))
			assertErrorResponse(t, result.Message, 403)
		}
	}
}

func TestUnregisteredHostCannotPublishResults(t *testing.T) {
	d := newTestDispatcher("test_secret", time.Now)
	s := &testSession{authenticated: true, deviceID: "HOST-0001"}
	for _, kind := range []protocol.MessageType{protocol.TypeCommandResult, protocol.TypeFilesListRes, protocol.TypeFileDownloadRes, protocol.TypeLocation, protocol.TypeEmergencySOS} {
		assertErrorResponse(t, d.Dispatch(context.Background(), s, envelope(t, kind, 1, map[string]any{})).Message, 403)
	}
}

func TestAdminCannotRegisterAsHostOrPublishHostData(t *testing.T) {
	d := newTestDispatcher("test_secret", time.Now)
	s := &testSession{authenticated: true, deviceID: "ADMIN-0001"}
	for _, kind := range []protocol.MessageType{protocol.TypeRegister, protocol.TypeCommandResult, protocol.TypeLocation} {
		assertErrorResponse(t, d.Dispatch(context.Background(), s, envelope(t, kind, 1, map[string]any{})).Message, 403)
	}
}

func TestAuthenticatedSessionCannotChangePrincipal(t *testing.T) {
	d := newTestDispatcher("test_secret", time.Now)
	s := authenticatedSession()
	assertErrorResponse(t, d.Dispatch(context.Background(), s, authRequest(t, "test_secret", "ADMIN-0001")).Message, 409)
}

type mockCountingFcmRegistrar struct {
	mu         sync.Mutex
	callCount  int
	lastDevice string
	lastToken  string
	err        error
}

func (m *mockCountingFcmRegistrar) RegisterToken(ctx context.Context, deviceID, fcmToken string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callCount++
	m.lastDevice = deviceID
	m.lastToken = fcmToken
	return m.err
}

func TestDispatchRegister_PersistenceExactlyOnce(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	registrar := &mockCountingFcmRegistrar{}
	dispatcher.SetFcmRegistrar(registrar)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)

	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
	}

	payload := protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Pixel",
		FcmToken:   "fcm-sample-token",
	}
	raw := envelope(t, protocol.TypeRegister, 1, payload)

	result := dispatcher.Dispatch(context.Background(), session, raw)

	if result.Message == nil || result.Message.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected TypeRegisterAck, got %#v", result.Message)
	}

	if !session.registered {
		t.Fatal("session must be marked registered")
	}

	if registrar.callCount != 1 {
		t.Fatalf("expected token persistence to be invoked exactly once, got %d calls", registrar.callCount)
	}

	if len(broadcaster.broadcasts) != 1 {
		t.Fatalf("expected 1 broadcasted event, got %d", len(broadcaster.broadcasts))
	}
}

func TestDispatchRegister_PersistenceFailureLeavesSessionUnregisteredAndEmitsNeitherAckNorEvent(t *testing.T) {
	dispatcher := newTestDispatcher("test_secret", time.Now)
	registrar := &mockCountingFcmRegistrar{err: errors.New("fcm db write failed")}
	dispatcher.SetFcmRegistrar(registrar)
	broadcaster := &testBroadcaster{}
	dispatcher.SetBroadcaster(broadcaster)

	session := &testSession{
		authenticated: true,
		deviceID:      "HOST-0001",
	}

	payload := protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Pixel",
		FcmToken:   "fcm-sample-token",
	}
	raw := envelope(t, protocol.TypeRegister, 1, payload)

	result := dispatcher.Dispatch(context.Background(), session, raw)

	if result.Message == nil || result.Message.Type != protocol.TypeError {
		t.Fatalf("expected TypeError response when persistence fails, got %#v", result.Message)
	}

	if session.registered {
		t.Fatal("session must not be marked registered when FCM token persistence fails")
	}

	if len(broadcaster.broadcasts) != 0 {
		t.Fatalf("expected 0 broadcasted events on failure, got %d", len(broadcaster.broadcasts))
	}
}
