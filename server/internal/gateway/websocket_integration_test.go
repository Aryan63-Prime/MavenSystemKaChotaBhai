package gateway

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
	"github.com/xaiop/project-sentinel/server/internal/audio"
	"github.com/xaiop/project-sentinel/server/internal/auth"
	"github.com/xaiop/project-sentinel/server/internal/device"
	"github.com/xaiop/project-sentinel/server/internal/dispatcher"
	"github.com/xaiop/project-sentinel/server/internal/heartbeat"
	"github.com/xaiop/project-sentinel/server/internal/location"
	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"go.uber.org/zap"
)

const integrationJWTSecret = "integration_secret"

func TestWebSocketConnect(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	waitForSessionCount(t, gateway, 1)
}

func TestWebSocketDisconnect(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	waitForSessionCount(t, gateway, 1)

	closeWebSocket(t, conn)

	waitForSessionCount(t, gateway, 0)
}

func TestWebSocketHeartbeat(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authResponse := authenticateWebSocket(t, conn, "HOST-0001")
	if authResponse.Type != protocol.TypeAuthAck {
		t.Fatalf("expected AUTH_ACK, got %s", authResponse.Type)
	}

	session := onlySession(t, gateway)
	previousHeartbeat := time.Now().Add(-time.Minute)
	session.SetLastHeartbeat(previousHeartbeat)

	writeControlMessage(t, conn, protocol.TypeHeartbeat, 2, protocol.HeartbeatMessage{})

	response := readControlMessage(t, conn)
	if response.Type != protocol.TypeHeartbeatAck {
		t.Fatalf("expected HEARTBEAT_ACK, got %s", response.Type)
	}

	waitForCondition(t, func() bool {
		return sessionHeartbeat(t, session).After(previousHeartbeat)
	}, "session heartbeat to update")
}

func TestWebSocketPingPong(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")

	writeControlMessage(t, conn, protocol.TypePing, 3, protocol.PingMessage{})

	response := readControlMessage(t, conn)
	if response.Type != protocol.TypePong {
		t.Fatalf("expected PONG, got %s", response.Type)
	}

	if response.Sequence != 3 {
		t.Fatalf("expected sequence 3, got %d", response.Sequence)
	}
}

func TestWebSocketAuthSuccess(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	response := authenticateWebSocket(t, conn, "HOST-0001")
	if response.Type != protocol.TypeAuthAck {
		t.Fatalf("expected AUTH_ACK, got %s", response.Type)
	}

	session := onlySession(t, gateway)
	if !session.IsAuthenticated() {
		t.Fatal("expected authenticated session")
	}

	if session.AuthenticatedDeviceID() != "HOST-0001" {
		t.Fatalf("expected HOST-0001, got %s", session.AuthenticatedDeviceID())
	}
}

func TestWebSocketAuthFailure(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	writeControlMessage(t, conn, protocol.TypeAuth, 1, protocol.AuthMessage{
		Token: "malformed.jwt.token",
	})

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 401)
	assertConnectionClosed(t, conn)
	waitForSessionCount(t, gateway, 0)
}

func TestWebSocketRegisterBeforeAuth(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	writeRegisterMessage(t, conn, 2, "HOST-0001")

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 401)
}

func TestWebSocketRegisterAfterAuth(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	writeRegisterMessage(t, conn, 2, "HOST-0001")

	response := readControlMessage(t, conn)
	if response.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected REGISTER_ACK, got %s", response.Type)
	}

	session := onlySession(t, gateway)
	if !session.IsRegistered() {
		t.Fatal("expected registered session")
	}
}

func TestWebSocketDuplicateRegisterFails(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	writeRegisterMessage(t, conn, 2, "HOST-0001")

	response := readControlMessage(t, conn)
	if response.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected REGISTER_ACK, got %s", response.Type)
	}

	writeRegisterMessage(t, conn, 3, "HOST-0001")

	response = readControlMessage(t, conn)
	assertErrorResponse(t, response, 409)
}

func TestWebSocketMismatchedRegisterFails(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	writeRegisterMessage(t, conn, 2, "HOST-0002")

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 403)
}

func TestWebSocketMalformedRegisterFails(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	writeControlMessage(t, conn, protocol.TypeRegister, 2, protocol.RegisterMessage{
		DeviceID:   "HOST-0001",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	})

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 400)
}

func TestWebSocketLocationUpdatesSession(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	registerWebSocket(t, conn, "HOST-0001")

	writeLocationMessage(t, conn, 4, protocol.LocationMessage{
		Latitude:  28.6139,
		Longitude: 77.2090,
		Accuracy:  5.4,
		Battery:   81,
		Network:   "5G",
	})

	session := onlySession(t, gateway)
	waitForCondition(t, func() bool {
		locationMessage := session.LastLocation()
		return locationMessage.Latitude == 28.6139 && locationMessage.Network == "5G"
	}, "session location to update")
}

func TestWebSocketInvalidLocationFails(t *testing.T) {
	_, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")
	registerWebSocket(t, conn, "HOST-0001")

	writeLocationMessage(t, conn, 4, protocol.LocationMessage{
		Latitude:  91,
		Longitude: 77.2090,
		Accuracy:  5.4,
		Battery:   81,
		Network:   "5G",
	})

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 400)
}

func TestWebSocketDisconnectOnFailedAuth(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	writeControlMessage(t, conn, protocol.TypeAuth, 1, protocol.AuthMessage{
		Token: signedTokenWithSecret(t, "wrong_secret", "HOST-0001"),
	})

	response := readControlMessage(t, conn)
	assertErrorResponse(t, response, 401)
	assertConnectionClosed(t, conn)
	waitForSessionCount(t, gateway, 0)
}

func TestWebSocketSessionCleanup(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	conn := dialWebSocket(t, server)
	authenticateWebSocket(t, conn, "HOST-0001")

	session := onlySession(t, gateway)
	if !session.IsAuthenticated() {
		t.Fatal("expected authenticated session before cleanup")
	}

	closeWebSocket(t, conn)

	waitForSessionCount(t, gateway, 0)

	if gateway.manager.Count() != 0 {
		t.Fatalf("expected no sessions after cleanup, got %d", gateway.manager.Count())
	}
}

func TestWebSocketStaleSessionCleanup(t *testing.T) {
	gateway, server := newIntegrationServerWithHeartbeat(t, heartbeat.NewServiceWithConfig(
		time.Now,
		10*time.Millisecond,
		30*time.Millisecond,
	))
	defer server.Close()

	conn := dialWebSocket(t, server)
	defer closeWebSocket(t, conn)

	authenticateWebSocket(t, conn, "HOST-0001")

	session := onlySession(t, gateway)
	session.SetLastHeartbeat(time.Now().Add(-time.Minute))

	assertConnectionClosed(t, conn)
	waitForSessionCount(t, gateway, 0)
}

func TestWebSocketStaleSessionCleanupWithConcurrentClients(t *testing.T) {
	gateway, server := newIntegrationServerWithHeartbeat(t, heartbeat.NewServiceWithConfig(
		time.Now,
		10*time.Millisecond,
		30*time.Millisecond,
	))
	defer server.Close()

	staleConn := dialWebSocket(t, server)
	defer closeWebSocket(t, staleConn)
	activeConn := dialWebSocket(t, server)
	defer closeWebSocket(t, activeConn)

	authenticateWebSocket(t, staleConn, "HOST-0001")
	authenticateWebSocket(t, activeConn, "HOST-0002")

	waitForSessionCount(t, gateway, 2)

	staleSession := sessionByDeviceID(t, gateway, "HOST-0001")
	staleSession.SetLastHeartbeat(time.Now().Add(-time.Minute))
	activeSession := sessionByDeviceID(t, gateway, "HOST-0002")

	assertConnectionClosed(t, staleConn)

	waitForCondition(t, func() bool {
		return gateway.manager.Count() == 1 && activeSession.IsAuthenticated()
	}, "only stale session to be removed")

	writeControlMessage(t, activeConn, protocol.TypeHeartbeat, 4, protocol.HeartbeatMessage{})

	response := readControlMessage(t, activeConn)
	if response.Type != protocol.TypeHeartbeatAck {
		t.Fatalf("expected HEARTBEAT_ACK, got %s", response.Type)
	}
}

func TestWebSocketMultipleSimultaneousClients(t *testing.T) {
	gateway, server := newIntegrationServer(t)
	defer server.Close()

	const clientCount = 5

	connections := make([]*websocket.Conn, 0, clientCount)
	for i := 0; i < clientCount; i++ {
		conn := dialWebSocket(t, server)
		connections = append(connections, conn)

		deviceID := fmt.Sprintf("HOST-%04d", i+1)
		response := authenticateWebSocket(t, conn, deviceID)
		if response.Type != protocol.TypeAuthAck {
			t.Fatalf("expected AUTH_ACK for %s, got %s", deviceID, response.Type)
		}
	}

	waitForSessionCount(t, gateway, clientCount)

	for _, conn := range connections {
		closeWebSocket(t, conn)
	}

	waitForSessionCount(t, gateway, 0)
}

func newIntegrationServer(t *testing.T) (*Gateway, *httptest.Server) {
	t.Helper()

	return newIntegrationServerWithHeartbeat(t, heartbeat.NewService())
}

func newIntegrationServerWithHeartbeat(t *testing.T, heartbeatService *heartbeat.Service) (*Gateway, *httptest.Server) {
	t.Helper()

	authHandler := auth.NewHandler(auth.New(integrationJWTSecret))
	deviceHandler := device.NewHandler(device.NewService())
	heartbeatHandler := heartbeat.NewHandler(heartbeatService)
	locationHandler := location.NewHandler(location.NewService())
	audioHandler := audio.NewHandler(audio.NewService(nil, nil))
	dispatch := dispatcher.New(authHandler, deviceHandler, heartbeatHandler, locationHandler, audioHandler, nil)
	gateway := New(dispatch, heartbeatService, zap.NewNop())
	server := httptest.NewServer(gateway.Handler())

	return gateway, server
}

func dialWebSocket(t *testing.T, server *httptest.Server) *websocket.Conn {
	t.Helper()

	url := "ws" + strings.TrimPrefix(server.URL, "http") + "/ws"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("Dial returned error: %v", err)
	}

	return conn
}

func authenticateWebSocket(t *testing.T, conn *websocket.Conn, deviceID string) protocol.Message {
	t.Helper()

	writeControlMessage(t, conn, protocol.TypeAuth, 1, protocol.AuthMessage{
		Token: signedIntegrationToken(t, deviceID),
	})

	return readControlMessage(t, conn)
}

func registerWebSocket(t *testing.T, conn *websocket.Conn, deviceID string) protocol.Message {
	t.Helper()

	writeRegisterMessage(t, conn, 2, deviceID)
	return readControlMessage(t, conn)
}

func writeRegisterMessage(t *testing.T, conn *websocket.Conn, sequence int64, deviceID string) {
	t.Helper()

	writeControlMessage(t, conn, protocol.TypeRegister, sequence, protocol.RegisterMessage{
		DeviceID:   deviceID,
		DeviceName: "Pixel 9",
		AppVersion: "1.0.0",
		Model:      "Google Pixel",
	})
}

func writeLocationMessage(t *testing.T, conn *websocket.Conn, sequence int64, locationMessage protocol.LocationMessage) {
	t.Helper()

	writeControlMessage(t, conn, protocol.TypeLocation, sequence, locationMessage)
}

func writeControlMessage(t *testing.T, conn *websocket.Conn, messageType protocol.MessageType, sequence int64, data any) {
	t.Helper()

	message, err := protocol.NewMessage(messageType, sequence, data)
	if err != nil {
		t.Fatalf("NewMessage returned error: %v", err)
	}

	payload, err := json.Marshal(message)
	if err != nil {
		t.Fatalf("Marshal returned error: %v", err)
	}

	if err := conn.WriteMessage(websocket.TextMessage, payload); err != nil {
		t.Fatalf("WriteMessage returned error: %v", err)
	}
}

func readControlMessage(t *testing.T, conn *websocket.Conn) protocol.Message {
	t.Helper()

	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline returned error: %v", err)
	}

	messageType, payload, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("ReadMessage returned error: %v", err)
	}

	if messageType != websocket.TextMessage {
		t.Fatalf("expected text message, got %d", messageType)
	}

	var message protocol.Message
	if err := json.Unmarshal(payload, &message); err != nil {
		t.Fatalf("Unmarshal returned error: %v", err)
	}

	return message
}

func assertErrorResponse(t *testing.T, message protocol.Message, code int) {
	t.Helper()

	if message.Type != protocol.TypeError {
		t.Fatalf("expected ERROR, got %s", message.Type)
	}

	var errorMessage protocol.ErrorMessage
	if err := message.DecodeData(&errorMessage); err != nil {
		t.Fatalf("DecodeData returned error: %v", err)
	}

	if errorMessage.Code != code {
		t.Fatalf("expected error code %d, got %d", code, errorMessage.Code)
	}
}

func assertConnectionClosed(t *testing.T, conn *websocket.Conn) {
	t.Helper()

	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline returned error: %v", err)
	}

	_, _, err := conn.ReadMessage()
	if err == nil {
		t.Fatal("expected websocket connection to close")
	}
}

func closeWebSocket(t *testing.T, conn *websocket.Conn) {
	t.Helper()

	deadline := time.Now().Add(time.Second)
	_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""), deadline)
	_ = conn.Close()
}

func waitForSessionCount(t *testing.T, gateway *Gateway, expected int) {
	t.Helper()

	waitForCondition(t, func() bool {
		return gateway.manager.Count() == expected
	}, fmt.Sprintf("session count to become %d", expected))
}

func waitForCondition(t *testing.T, condition func() bool, description string) {
	t.Helper()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}

		time.Sleep(10 * time.Millisecond)
	}

	t.Fatalf("timed out waiting for %s", description)
}

func onlySession(t *testing.T, gateway *Gateway) *Session {
	t.Helper()

	gateway.manager.mu.RLock()
	defer gateway.manager.mu.RUnlock()

	if len(gateway.manager.sessions) != 1 {
		t.Fatalf("expected one session, got %d", len(gateway.manager.sessions))
	}

	for _, session := range gateway.manager.sessions {
		return session
	}

	t.Fatal("expected session")
	return nil
}

func sessionByDeviceID(t *testing.T, gateway *Gateway, deviceID string) *Session {
	t.Helper()

	gateway.manager.mu.RLock()
	defer gateway.manager.mu.RUnlock()

	for _, session := range gateway.manager.sessions {
		if session.AuthenticatedDeviceID() == deviceID {
			return session
		}
	}

	t.Fatalf("expected session for device %s", deviceID)
	return nil
}

func sessionHeartbeat(t *testing.T, session *Session) time.Time {
	t.Helper()

	return session.LastHeartbeat()
}

func signedIntegrationToken(t *testing.T, deviceID string) string {
	t.Helper()

	return signedTokenWithSecret(t, integrationJWTSecret, deviceID)
}

func signedTokenWithSecret(t *testing.T, secret string, deviceID string) string {
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

	return signed
}
