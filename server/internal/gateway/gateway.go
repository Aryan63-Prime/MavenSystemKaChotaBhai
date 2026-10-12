package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/xaiop/project-sentinel/server/internal/dispatcher"
	"github.com/xaiop/project-sentinel/server/internal/health"
	"github.com/xaiop/project-sentinel/server/internal/metrics"
	"go.uber.org/zap"
)

type Gateway struct {
	mux             *http.ServeMux
	manager         *Manager
	dispatcher      *dispatcher.Dispatcher
	heartbeatPolicy HeartbeatPolicy
	logger          *zap.Logger
	healthService   *health.Service
	metrics         *metrics.Collector
	wg              sync.WaitGroup
}

func New(dispatcher *dispatcher.Dispatcher, heartbeatPolicy HeartbeatPolicy, logger *zap.Logger) *Gateway {
	if logger == nil {
		logger = zap.NewNop()
	}

	g := &Gateway{
		mux:             http.NewServeMux(),
		manager:         NewManager(),
		dispatcher:      dispatcher,
		heartbeatPolicy: heartbeatPolicy,
		logger:          logger,
		healthService:   health.NewService(),
		metrics:         metrics.NewCollector(),
	}

	g.registerRoutes()

	return g
}

func (g *Gateway) Handler() http.Handler {
	return g.requestIDMiddleware(g.requestLoggingMiddleware(g.mux))
}

// HandleFunc registers an HTTP route on the gateway mux.
func (g *Gateway) HandleFunc(pattern string, handler func(http.ResponseWriter, *http.Request)) {
	g.mux.HandleFunc(pattern, handler)
}

// SetHealthService configures the gateway health reporter.
func (g *Gateway) SetHealthService(service *health.Service) {
	if service == nil {
		service = health.NewService()
	}

	g.healthService = service
}

// MetricsCollector returns the gateway HTTP metrics collector.
func (g *Gateway) MetricsCollector() *metrics.Collector {
	return g.metrics
}

// SessionSnapshots returns read-only copies of connected sessions.
func (g *Gateway) SessionSnapshots() []SessionSnapshot {
	return g.manager.Snapshots()
}

// SessionSnapshotByDeviceID returns a read-only session copy for a device.
func (g *Gateway) SessionSnapshotByDeviceID(deviceID string) (SessionSnapshot, bool) {
	return g.manager.SnapshotByDeviceID(deviceID)
}

// GetConnectionIDByDeviceID returns the connection ID associated with a device ID.
func (g *Gateway) GetConnectionIDByDeviceID(deviceID string) (string, bool) {
	snapshot, ok := g.manager.SnapshotByDeviceID(deviceID)
	if !ok {
		return "", false
	}
	return snapshot.ConnectionID, true
}

func (g *Gateway) registerRoutes() {

	g.mux.HandleFunc("/health", g.health)

	g.mux.HandleFunc("/ws", g.websocket)
}

func (g *Gateway) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}

	_ = json.NewEncoder(w).Encode(g.healthService.Report(r.Context()))
}

// Shutdown closes all active WebSocket connections and waits for handler goroutines to exit.
func (g *Gateway) Shutdown() {
	g.logger.Info("gateway shutting down")
	g.manager.CloseAll()
	g.wg.Wait()
	g.logger.Info("gateway shutdown complete")
}

func (g *Gateway) websocket(w http.ResponseWriter, r *http.Request) {

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	g.wg.Add(1)
	defer g.wg.Done()

	// Bound memory consumed by one untrusted WebSocket message.
	conn.SetReadLimit(2 << 20)
	connectionID := uuid.NewString()
	ctx, cancel := context.WithCancel(r.Context())

	client := &Client{
		ConnectionID: connectionID,
		Context:      ctx,
		Cancel:       cancel,
		Conn:         conn,
		Send:         make(chan outgoingMessage, 64),
	}

	session := NewSession(connectionID, time.Now(), client)

	client.Session = session

	g.manager.Add(session)

	g.logger.Info("client connected", zap.String("connection_id", connectionID))

	go g.writeLoop(client)

	go g.heartbeat(client)

	g.readLoop(client)
}
func (g *Gateway) readLoop(client *Client) {

	defer func() {
		// Broadcast disconnect before removing session
		if client.Session != nil && client.Session.IsRegistered() {
			disconnID := client.Session.DeviceID()
			if disconnID == "" {
				disconnID = client.Session.AuthenticatedDeviceID()
			}
			g.dispatcher.BroadcastDisconnect(disconnID, client.Session.Model())
		}

		client.Cancel()

		g.manager.Remove(client.ConnectionID)

		close(client.Send)

		_ = client.Conn.Close()

		g.logger.Info("client disconnected", zap.String("connection_id", client.ConnectionID))

	}()

	for {

		messageType, payload, err := client.Conn.ReadMessage()

		if err != nil {
			break
		}

		switch messageType {

		case websocket.TextMessage:
			g.dispatch(client, payload)

		case websocket.BinaryMessage:
			g.dispatchBinary(client, payload)
		}
	}
}

func (g *Gateway) dispatch(client *Client, data []byte) {

	result := g.dispatcher.Dispatch(client.Context, client.Session, data)

	if result.AuthenticatedDeviceID != "" {
		client.Session.SetAuthenticated(result.AuthenticatedDeviceID)
		g.logger.Info(
			"authentication success",
			zap.String("connection_id", client.ConnectionID),
			zap.String("device_id", result.AuthenticatedDeviceID),
		)
	}

	if result.CloseConnection {
		g.logger.Warn(
			"authentication failure",
			zap.String("connection_id", client.ConnectionID),
			zap.String("device_id", client.Session.AuthenticatedDeviceID()),
		)
	}

	if result.Message == nil {
		return
	}

	payload, err := json.Marshal(result.Message)
	if err != nil {
		g.logger.Error("marshal response", zap.Error(err))
		return
	}

	outgoing := outgoingMessage{
		Payload:         payload,
		CloseAfterWrite: result.CloseConnection,
	}

	select {
	case client.Send <- outgoing:
	default:
		g.logger.Warn("client send buffer full", zap.String("connection_id", client.ConnectionID))
		if result.CloseConnection {
			client.Cancel()
			_ = client.Conn.Close()
		}
	}
}

func (g *Gateway) dispatchBinary(client *Client, data []byte) {
	if !client.Session.IsAuthenticated() || !client.Session.IsRegistered() {
		return
	}

	if err := g.dispatcher.DispatchBinary(client.Context, client.Session, data); err != nil {
		g.logger.Warn("binary dispatch error",
			zap.String("connection_id", client.ConnectionID),
			zap.Error(err),
		)
	}
}

// IsConnected returns true if a client with connectionID is currently connected.
func (g *Gateway) IsConnected(connectionID string) bool {
	if g == nil || g.manager == nil {
		return false
	}
	_, ok := g.manager.Get(connectionID)
	return ok
}

// ForwardBinary sends a binary frame to a connected client by connection ID.
// Returns nil if the client is disconnected or the send buffer is full (audio is lossy).
func (g *Gateway) ForwardBinary(connectionID string, data []byte) error {
	session, ok := g.manager.Get(connectionID)
	if !ok {
		return nil
	}

	client := session.client
	if client == nil {
		return nil
	}

	select {
	case client.Send <- outgoingMessage{
		MessageType: websocket.BinaryMessage,
		Payload:     data,
	}:
	default:
		g.logger.Debug("audio frame dropped, buffer full",
			zap.String("connection_id", connectionID),
		)
	}

	return nil
}

// ForwardText sends a text frame to a connected client by connection ID.
func (g *Gateway) ForwardText(connectionID string, data []byte) error {
	session, ok := g.manager.Get(connectionID)
	if !ok {
		return nil
	}

	client := session.client
	if client == nil {
		return nil
	}

	select {
	case client.Send <- outgoingMessage{
		MessageType: websocket.TextMessage,
		Payload:     data,
	}:
	default:
		g.logger.Warn("text message dropped, buffer full",
			zap.String("connection_id", connectionID),
		)
	}

	return nil
}

// BroadcastToAdmins sends a text message to every authenticated admin session.
// Non-blocking: drops silently if an admin's send buffer is full.
func (g *Gateway) BroadcastToAdmins(payload []byte) {
	g.manager.ForEachAdmin(func(client *Client) {
		select {
		case client.Send <- outgoingMessage{Payload: payload}:
		default:
			g.logger.Debug("admin broadcast dropped, buffer full",
				zap.String("connection_id", client.ConnectionID),
			)
		}
	})
}
