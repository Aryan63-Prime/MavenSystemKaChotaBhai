package audio

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/google/uuid"
	"github.com/xaiop/project-sentinel/server/internal/protocol"
)

// --- Frame tests ---

func testFrame(seq uint32, ts int64, opus []byte) []byte {
	buf := make([]byte, headerSize+len(opus))
	buf[0] = PacketTypeAudio
	binary.BigEndian.PutUint32(buf[1:5], seq)
	binary.BigEndian.PutUint64(buf[5:13], uint64(ts))
	copy(buf[13:], opus)
	return buf
}

func testFrameV2(streamID uuid.UUID, seq uint32, ts int64, opus []byte) []byte {
	buf := make([]byte, HeaderSizeV2+len(opus))
	buf[0] = PacketTypeAudioV2
	copy(buf[1:17], streamID[:])
	binary.BigEndian.PutUint32(buf[17:21], seq)
	binary.BigEndian.PutUint64(buf[21:29], uint64(ts))
	copy(buf[29:], opus)
	return buf
}

func TestParseHeader_Valid(t *testing.T) {
	frame := testFrame(42, 1700000000000, []byte{0xAA, 0xBB})
	header, err := ParseHeader(frame)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if header.PacketType != PacketTypeAudio {
		t.Errorf("expected packet type %d, got %d", PacketTypeAudio, header.PacketType)
	}
	if header.Sequence != 42 {
		t.Errorf("expected sequence 42, got %d", header.Sequence)
	}
	if header.Timestamp != 1700000000000 {
		t.Errorf("expected timestamp 1700000000000, got %d", header.Timestamp)
	}
}

func TestParseHeader_TooSmall(t *testing.T) {
	_, err := ParseHeader([]byte{0x01, 0x02})
	if !errors.Is(err, ErrFrameTooSmall) {
		t.Errorf("expected ErrFrameTooSmall, got %v", err)
	}
}

func TestParseHeader_ExactHeaderSize(t *testing.T) {
	frame := testFrame(1, 0, nil)[:headerSize]
	_, err := ParseHeader(frame)
	if err != nil {
		t.Fatalf("header-only frame should be valid: %v", err)
	}
}

func TestValidateFrame_Valid(t *testing.T) {
	if err := ValidateFrame(testFrame(1, 0, []byte{0xFF})); err != nil {
		t.Errorf("expected valid frame: %v", err)
	}
}

func TestValidateFrame_TooSmall(t *testing.T) {
	if err := ValidateFrame(nil); !errors.Is(err, ErrFrameTooSmall) {
		t.Errorf("expected ErrFrameTooSmall, got %v", err)
	}
}

func TestValidateFrame_InvalidType(t *testing.T) {
	frame := testFrame(1, 0, nil)
	frame[0] = 0xFF
	if err := ValidateFrame(frame); !errors.Is(err, ErrInvalidPacketType) {
		t.Errorf("expected ErrInvalidPacketType, got %v", err)
	}
}

// --- Stubs ---

type stubListenerRepo struct {
	mu        sync.Mutex
	listeners map[string]string
	setErr    error
	getErr    error
	removeErr error
}

func newStubListenerRepo() *stubListenerRepo {
	return &stubListenerRepo{listeners: make(map[string]string)}
}

func (r *stubListenerRepo) SetListener(_ context.Context, deviceID string, connectionID string) error {
	if r.setErr != nil {
		return r.setErr
	}
	r.mu.Lock()
	r.listeners[deviceID] = connectionID
	r.mu.Unlock()
	return nil
}

func (r *stubListenerRepo) GetListener(_ context.Context, deviceID string) (string, bool, error) {
	if r.getErr != nil {
		return "", false, r.getErr
	}
	r.mu.Lock()
	connID, ok := r.listeners[deviceID]
	r.mu.Unlock()
	return connID, ok, nil
}

func (r *stubListenerRepo) RemoveListener(_ context.Context, deviceID string) error {
	if r.removeErr != nil {
		return r.removeErr
	}
	r.mu.Lock()
	delete(r.listeners, deviceID)
	r.mu.Unlock()
	return nil
}

type stubForwarder struct {
	forwarded []forwardedFrame
	err       error
}

type forwardedFrame struct {
	ConnectionID string
	Data         []byte
}

func (f *stubForwarder) ForwardBinary(connectionID string, data []byte) error {
	if f.err != nil {
		return f.err
	}
	f.forwarded = append(f.forwarded, forwardedFrame{
		ConnectionID: connectionID,
		Data:         data,
	})
	return nil
}

type stubSession struct {
	connectionID string
	isAdmin      bool
}

func (s *stubSession) ConnectionID() string {
	return s.connectionID
}

func (s *stubSession) IsAdmin() bool {
	return s.isAdmin
}

// --- Service tests ---

func TestStartListening_Success(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)

	if err := svc.StartListening(context.Background(), "conn-001", "HOST-0001"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if repo.listeners["HOST-0001"] != "conn-001" {
		t.Error("expected listener to be stored")
	}
}

func TestStartListening_MissingDeviceID(t *testing.T) {
	svc := NewService(newStubListenerRepo(), nil)

	if err := svc.StartListening(context.Background(), "conn-001", ""); !errors.Is(err, ErrMissingDeviceID) {
		t.Errorf("expected ErrMissingDeviceID, got %v", err)
	}
}

func TestStartListening_MissingConnectionID(t *testing.T) {
	svc := NewService(newStubListenerRepo(), nil)

	if err := svc.StartListening(context.Background(), "", "HOST-0001"); err == nil {
		t.Error("expected error for missing connection ID")
	}
}

func TestStopListening_Success(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	_ = svc.StartListening(context.Background(), "conn-001", "HOST-0001")

	if err := svc.StopListening(context.Background(), "conn-001", "HOST-0001"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, ok := repo.listeners["HOST-0001"]; ok {
		t.Error("expected listener to be removed")
	}
}

func TestStopListening_UnauthorizedConnection(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	_ = svc.StartListening(context.Background(), "conn-admin-1", "HOST-0001")

	// Another connection attempts to stop it
	err := svc.StopListening(context.Background(), "conn-admin-2", "HOST-0001")
	if err == nil {
		t.Fatal("expected error when unauthorized connection stops listener")
	}
	if repo.listeners["HOST-0001"] != "conn-admin-1" {
		t.Error("listener should still belong to conn-admin-1")
	}
}

func TestStopListening_MissingDeviceID(t *testing.T) {
	svc := NewService(newStubListenerRepo(), nil)

	if err := svc.StopListening(context.Background(), "conn-001", "  "); !errors.Is(err, ErrMissingDeviceID) {
		t.Errorf("expected ErrMissingDeviceID, got %v", err)
	}
}

func TestRouteFrame_Success(t *testing.T) {
	repo := newStubListenerRepo()
	fwd := &stubForwarder{}
	svc := NewService(repo, fwd)
	repo.listeners["HOST-0001"] = "admin-conn-001"

	frame := testFrame(1, 1700000000000, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(fwd.forwarded) != 1 {
		t.Fatalf("expected 1 forwarded frame, got %d", len(fwd.forwarded))
	}
	if fwd.forwarded[0].ConnectionID != "admin-conn-001" {
		t.Errorf("expected admin-conn-001, got %s", fwd.forwarded[0].ConnectionID)
	}
}

func TestRouteFrame_NoListener(t *testing.T) {
	svc := NewService(newStubListenerRepo(), &stubForwarder{})

	frame := testFrame(1, 0, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-UNKNOWN", frame); err != nil {
		t.Errorf("expected nil for no listener, got %v", err)
	}
}

func TestRouteFrame_InvalidFrame(t *testing.T) {
	svc := NewService(newStubListenerRepo(), &stubForwarder{})

	if err := svc.RouteFrame(context.Background(), "HOST-0001", []byte{0x01}); !errors.Is(err, ErrFrameTooSmall) {
		t.Errorf("expected ErrFrameTooSmall, got %v", err)
	}
}

func TestRouteFrame_InvalidPacketType(t *testing.T) {
	svc := NewService(newStubListenerRepo(), &stubForwarder{})

	frame := testFrame(1, 0, []byte{0xAA})
	frame[0] = 0xFF
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); !errors.Is(err, ErrInvalidPacketType) {
		t.Errorf("expected ErrInvalidPacketType, got %v", err)
	}
}

func TestRouteFrame_NilDependencies(t *testing.T) {
	svc := NewService(nil, nil)

	frame := testFrame(1, 0, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err != nil {
		t.Errorf("expected nil for nil dependencies, got %v", err)
	}
}

func TestRouteFrame_ForwarderError(t *testing.T) {
	repo := newStubListenerRepo()
	repo.listeners["HOST-0001"] = "admin-conn-001"
	fwd := &stubForwarder{err: errors.New("send failed")}
	svc := NewService(repo, fwd)

	frame := testFrame(1, 0, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err == nil {
		t.Error("expected error from forwarder")
	}
}

func TestRouteFrame_ListenerLookupError(t *testing.T) {
	repo := newStubListenerRepo()
	repo.getErr = errors.New("redis down")
	svc := NewService(repo, &stubForwarder{})

	frame := testFrame(1, 0, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err == nil {
		t.Error("expected error from listener lookup")
	}
}

func TestSetForwarder(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil) // forwarder set later
	repo.listeners["HOST-0001"] = "admin-conn-001"

	// Without forwarder, frames are silently dropped.
	frame := testFrame(1, 0, []byte{0xAA})
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Set forwarder, frames should now be delivered.
	fwd := &stubForwarder{}
	svc.SetForwarder(fwd)
	if err := svc.RouteFrame(context.Background(), "HOST-0001", frame); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(fwd.forwarded) != 1 {
		t.Errorf("expected 1 forwarded frame after SetForwarder")
	}
}

// --- Handler tests ---

func TestHandleListen(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	session := &stubSession{connectionID: "admin-001", isAdmin: true}

	msg := listenMessage(t, "HOST-0001", 5)
	response, err := handler.HandleListen(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response != nil {
		t.Error("expected nil response for LISTEN")
	}
	if repo.listeners["HOST-0001"] != "admin-001" {
		t.Error("expected listener to be stored")
	}
}

func TestHandleListen_NonAdminRejected(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	session := &stubSession{connectionID: "host-001", isAdmin: false}

	msg := listenMessage(t, "HOST-0001", 5)
	response, err := handler.HandleListen(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response == nil || response.Type != protocol.TypeError {
		t.Fatalf("expected 403 error response, got %v", response)
	}
}

func TestHandleStop(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	session := &stubSession{connectionID: "admin-001", isAdmin: true}

	repo.listeners["HOST-0001"] = "admin-001"
	msg := stopMessage(t, "HOST-0001", 6)
	response, err := handler.HandleStop(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response != nil {
		t.Error("expected nil response for STOP")
	}
	if _, ok := repo.listeners["HOST-0001"]; ok {
		t.Error("expected listener to be removed")
	}
}

func TestHandleStop_NonAdminRejected(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	session := &stubSession{connectionID: "host-001", isAdmin: false}

	repo.listeners["HOST-0001"] = "admin-001"
	msg := stopMessage(t, "HOST-0001", 6)
	response, err := handler.HandleStop(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response == nil || response.Type != protocol.TypeError {
		t.Fatalf("expected 403 error response, got %v", response)
	}
}

func TestHandleListen_ForwardsToHost(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	finder := &stubFinder{sessions: map[string]string{"HOST-0001": "host-conn-001"}}
	router := &stubRouter{}
	handler.SetFinder(finder)
	handler.SetRouter(router)

	session := &stubSession{connectionID: "admin-001", isAdmin: true}
	msg := listenMessage(t, "HOST-0001", 10)

	response, err := handler.HandleListen(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response != nil {
		t.Fatalf("expected nil response, got %v", response)
	}
	if repo.listeners["HOST-0001"] != "admin-001" {
		t.Errorf("expected listener registered in repo")
	}
	if len(router.messages["host-conn-001"]) != 1 {
		t.Fatalf("expected 1 forwarded message to host, got %d", len(router.messages["host-conn-001"]))
	}
}

func TestHandleListen_HostOffline_WakesFCM(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	finder := &stubFinder{sessions: map[string]string{}}
	router := &stubRouter{}
	waker := &stubFcmWaker{}
	handler.SetFinder(finder)
	handler.SetRouter(router)
	handler.SetFcmWaker(waker)

	session := &stubSession{connectionID: "admin-001", isAdmin: true}
	msg := listenMessage(t, "HOST-OFFLINE", 11)

	response, err := handler.HandleListen(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response == nil || response.Type != protocol.TypeError {
		t.Fatalf("expected 404 Error response, got %v", response)
	}
}

func TestHandleStop_ForwardsToHost(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)
	handler := NewHandler(svc)
	finder := &stubFinder{sessions: map[string]string{"HOST-0001": "host-conn-001"}}
	router := &stubRouter{}
	handler.SetFinder(finder)
	handler.SetRouter(router)

	session := &stubSession{connectionID: "admin-001", isAdmin: true}
	repo.listeners["HOST-0001"] = "admin-001"
	msg := stopMessage(t, "HOST-0001", 12)

	response, err := handler.HandleStop(context.Background(), session, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if response != nil {
		t.Fatalf("expected nil response, got %v", response)
	}
	if _, ok := repo.listeners["HOST-0001"]; ok {
		t.Errorf("expected listener removed")
	}
	if len(router.messages["host-conn-001"]) != 1 {
		t.Fatalf("expected 1 forwarded message to host, got %d", len(router.messages["host-conn-001"]))
	}
}

func TestHandleFrame(t *testing.T) {
	repo := newStubListenerRepo()
	fwd := &stubForwarder{}
	svc := NewService(repo, fwd)
	handler := NewHandler(svc)
	repo.listeners["HOST-0001"] = "admin-001"

	frame := testFrame(1, 0, []byte{0xAA, 0xBB, 0xCC})
	if err := handler.HandleFrame(context.Background(), "HOST-0001", frame); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(fwd.forwarded) != 1 {
		t.Fatal("expected frame to be forwarded")
	}
}

// --- Concurrent tests ---

func TestRouteFrame_ConcurrentClients(t *testing.T) {
	repo := newStubListenerRepo()
	var forwarded atomic.Int64
	fwd := &atomicForwarder{count: &forwarded}
	svc := NewService(repo, fwd)
	repo.listeners["HOST-0001"] = "admin-001"

	frame := testFrame(1, 0, []byte{0xAA})
	const numClients = 50
	var wg sync.WaitGroup
	wg.Add(numClients)

	for i := 0; i < numClients; i++ {
		go func() {
			defer wg.Done()
			_ = svc.RouteFrame(context.Background(), "HOST-0001", frame)
		}()
	}

	wg.Wait()
	if forwarded.Load() != numClients {
		t.Errorf("expected %d forwards, got %d", numClients, forwarded.Load())
	}
}

type atomicForwarder struct {
	count *atomic.Int64
}

func (f *atomicForwarder) ForwardBinary(_ string, _ []byte) error {
	f.count.Add(1)
	return nil
}

func TestStartStopListening_ConcurrentAccess(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)

	const numOps = 100
	var wg sync.WaitGroup
	wg.Add(numOps * 2)

	for i := 0; i < numOps; i++ {
		go func() {
			defer wg.Done()
			_ = svc.StartListening(context.Background(), "admin-001", "HOST-0001")
		}()
		go func() {
			defer wg.Done()
			_ = svc.StopListening(context.Background(), "admin-001", "HOST-0001")
		}()
	}

	wg.Wait()
}

func TestStartListening_AtomicSingleClaimRace(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)

	const numAdmins = 50
	var wg sync.WaitGroup
	wg.Add(numAdmins)

	var successCount atomic.Int32
	var conflictCount atomic.Int32

	for i := 0; i < numAdmins; i++ {
		adminID := fmt.Sprintf("admin-%d", i)
		go func() {
			defer wg.Done()
			err := svc.StartListening(context.Background(), adminID, "HOST-0001")
			if err == nil {
				successCount.Add(1)
			} else if strings.Contains(err.Error(), "already has active listener") {
				conflictCount.Add(1)
			} else {
				t.Errorf("unexpected error: %v", err)
			}
		}()
	}

	wg.Wait()

	if successCount.Load() != 1 {
		t.Fatalf("expected exactly 1 successful claim; got %d", successCount.Load())
	}
	if conflictCount.Load() != numAdmins-1 {
		t.Fatalf("expected %d conflict rejections; got %d", numAdmins-1, conflictCount.Load())
	}
}

func TestStopListening_AtomicReleaseRace(t *testing.T) {
	repo := newStubListenerRepo()
	svc := NewService(repo, nil)

	// Admin-001 claims the listener
	if err := svc.StartListening(context.Background(), "admin-001", "HOST-0001"); err != nil {
		t.Fatalf("initial start listening failed: %v", err)
	}

	const numStops = 50
	var wg sync.WaitGroup
	wg.Add(numStops)

	var successCount atomic.Int32
	var noListenerCount atomic.Int32

	for i := 0; i < numStops; i++ {
		go func() {
			defer wg.Done()
			err := svc.StopListening(context.Background(), "admin-001", "HOST-0001")
			if err == nil {
				successCount.Add(1)
			} else if strings.Contains(err.Error(), "no active listener") {
				noListenerCount.Add(1)
			} else {
				t.Errorf("unexpected error: %v", err)
			}
		}()
	}

	wg.Wait()

	if successCount.Load() != 1 {
		t.Fatalf("expected exactly 1 successful stop; got %d", successCount.Load())
	}
	if noListenerCount.Load() != numStops-1 {
		t.Fatalf("expected %d no-listener rejections; got %d", numStops-1, noListenerCount.Load())
	}
}

// --- Helpers ---

func listenMessage(t *testing.T, deviceID string, sequence int64) protocol.Message {
	t.Helper()
	msg, err := protocol.NewMessage(protocol.TypeListen, sequence, protocol.ListenMessage{DeviceID: deviceID})
	if err != nil {
		t.Fatalf("NewMessage error: %v", err)
	}
	return *msg
}

func stopMessage(t *testing.T, deviceID string, sequence int64) protocol.Message {
	t.Helper()
	msg, err := protocol.NewMessage(protocol.TypeStop, sequence, protocol.StopMessage{DeviceID: deviceID})
	if err != nil {
		t.Fatalf("NewMessage error: %v", err)
	}
	return *msg
}

type stubFinder struct {
	sessions map[string]string
}

func (f *stubFinder) GetConnectionIDByDeviceID(deviceID string) (string, bool) {
	connID, ok := f.sessions[deviceID]
	return connID, ok
}

type stubRouter struct {
	messages map[string][][]byte
}

func (r *stubRouter) ForwardText(connectionID string, data []byte) error {
	if r.messages == nil {
		r.messages = make(map[string][][]byte)
	}
	r.messages[connectionID] = append(r.messages[connectionID], data)
	return nil
}

type stubFcmWaker struct {
	wokenDevices []string
}

func (w *stubFcmWaker) WakeDevice(_ context.Context, deviceID string) (string, error) {
	w.wokenDevices = append(w.wokenDevices, deviceID)
	return "fcm-msg-id", nil
}

func TestParseHeader_V2_Valid(t *testing.T) {
	streamID := uuid.New()
	frame := testFrameV2(streamID, 101, 1712345678000, []byte{0x12, 0x34})
	header, err := ParseHeader(frame)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if header.PacketType != PacketTypeAudioV2 {
		t.Errorf("expected packet type %d, got %d", PacketTypeAudioV2, header.PacketType)
	}
	if header.StreamID != streamID {
		t.Errorf("expected streamID %s, got %s", streamID, header.StreamID)
	}
	if header.Sequence != 101 {
		t.Errorf("expected sequence 101, got %d", header.Sequence)
	}
	if header.Timestamp != 1712345678000 {
		t.Errorf("expected timestamp 1712345678000, got %d", header.Timestamp)
	}
	if header.HeaderSize != HeaderSizeV2 {
		t.Errorf("expected HeaderSize %d, got %d", HeaderSizeV2, header.HeaderSize)
	}
}

func TestParseHeader_V2_TooSmall(t *testing.T) {
	shortFrame := make([]byte, 28)
	shortFrame[0] = PacketTypeAudioV2
	_, err := ParseHeader(shortFrame)
	if !errors.Is(err, ErrFrameTooSmall) {
		t.Errorf("expected ErrFrameTooSmall for 28-byte V2 frame, got %v", err)
	}
}

func TestValidateFrame_V2(t *testing.T) {
	streamID := uuid.New()
	validFrame := testFrameV2(streamID, 1, 0, []byte{0x01})
	if err := ValidateFrame(validFrame); err != nil {
		t.Fatalf("valid V2 frame rejected: %v", err)
	}

	shortFrame := testFrameV2(streamID, 1, 0, nil)[:28]
	if err := ValidateFrame(shortFrame); !errors.Is(err, ErrFrameTooSmall) {
		t.Fatalf("expected ErrFrameTooSmall, got %v", err)
	}
}

func TestRouteFrame_V2_DropsStaleUUID(t *testing.T) {
	repo := newStubListenerRepo()
	fwd := &stubForwarder{}
	svc := NewService(repo, fwd)

	activeUUID := uuid.New()
	staleUUID := uuid.New()

	// Register listener with activeUUID
	if err := svc.StartListening(context.Background(), "admin-conn-1", "HOST-V2", activeUUID); err != nil {
		t.Fatalf("StartListening failed: %v", err)
	}

	// Incoming frame with staleUUID should be dropped silently
	staleFrame := testFrameV2(staleUUID, 10, 1000, []byte{0xAA, 0xBB})
	if err := svc.RouteFrame(context.Background(), "HOST-V2", staleFrame); err != nil {
		t.Fatalf("RouteFrame returned error: %v", err)
	}

	if len(fwd.forwarded) != 0 {
		t.Errorf("expected 0 forwarded frames for stale UUID, got %d", len(fwd.forwarded))
	}

	// Incoming frame with activeUUID should be forwarded successfully
	validFrame := testFrameV2(activeUUID, 11, 1020, []byte{0xCC, 0xDD})
	if err := svc.RouteFrame(context.Background(), "HOST-V2", validFrame); err != nil {
		t.Fatalf("RouteFrame returned error: %v", err)
	}

	if len(fwd.forwarded) != 1 {
		t.Errorf("expected 1 forwarded frame for active UUID, got %d", len(fwd.forwarded))
	}
}

func TestHandleListen_VersionNegotiation(t *testing.T) {
	repo := newStubListenerRepo()
	fwd := &stubForwarder{}
	svc := NewService(repo, fwd)
	handler := NewHandler(svc)

	adminSession := &stubSession{connectionID: "admin-conn", isAdmin: true}

	// Unsupported audioVersion 99 should return 426 Upgrade Required
	unsupportedMsg := protocol.Message{
		Type:     protocol.TypeListen,
		Sequence: 1,
		Data:     []byte(`{"deviceId":"HOST-001","audioVersion":99}`),
	}
	resp, err := handler.HandleListen(context.Background(), adminSession, unsupportedMsg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp == nil {
		t.Fatalf("expected error response for unsupported version")
	}

	var errData protocol.ErrorMessage
	if err := resp.DecodeData(&errData); err != nil {
		t.Fatalf("failed to decode error data: %v", err)
	}
	if errData.Code != 426 {
		t.Errorf("expected code 426, got %d", errData.Code)
	}
}

func TestHandleListen_ServerAssignedStreamUUID(t *testing.T) {
	repo := newStubListenerRepo()
	fwd := &stubForwarder{}
	svc := NewService(repo, fwd)
	handler := NewHandler(svc)

	finder := &stubFinder{sessions: map[string]string{"HOST-001": "host-conn"}}
	router := &stubRouter{}
	handler.SetFinder(finder)
	handler.SetRouter(router)

	adminSession := &stubSession{connectionID: "admin-conn", isAdmin: true}

	// Admin connects without streamId, requesting audioVersion 2
	msg := protocol.Message{
		Type:     protocol.TypeListen,
		Sequence: 2,
		Data:     []byte(`{"deviceId":"HOST-001","audioVersion":2}`),
	}
	resp, err := handler.HandleListen(context.Background(), adminSession, msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp != nil {
		t.Fatalf("expected nil response on success, got %+v", resp)
	}

	// Verify that router forwarded the message to host with a server-generated streamId
	hostMsgs := router.messages["host-conn"]
	if len(hostMsgs) != 1 {
		t.Fatalf("expected 1 message relayed to host, got %d", len(hostMsgs))
	}

	var forwardedMsg protocol.Message
	if err := json.Unmarshal(hostMsgs[0], &forwardedMsg); err != nil {
		t.Fatalf("failed to unmarshal forwarded message: %v", err)
	}

	var listenData protocol.ListenMessage
	if err := forwardedMsg.DecodeData(&listenData); err != nil {
		t.Fatalf("failed to decode listen data: %v", err)
	}

	if listenData.StreamID == "" {
		t.Errorf("expected non-empty server-generated streamId")
	}
	parsedUUID, err := uuid.Parse(listenData.StreamID)
	if err != nil {
		t.Errorf("streamId %q is not a valid UUID: %v", listenData.StreamID, err)
	}

	// Verify activeStream in service matches the assigned UUID
	activeStream, ok := svc.ActiveStream("HOST-001")
	if !ok || activeStream != parsedUUID {
		t.Errorf("expected activeStream %s, got %s (ok=%v)", parsedUUID, activeStream, ok)
	}
}
