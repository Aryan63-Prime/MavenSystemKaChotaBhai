package device

import (
	"context"
	"errors"
	"testing"

	"github.com/xaiop/project-sentinel/server/internal/protocol"
	"github.com/xaiop/project-sentinel/server/internal/repository"
)

type mockSession struct {
	authDeviceID string
	registered   bool
	regMsg       protocol.RegisterMessage
}

func (m *mockSession) AuthenticatedDeviceID() string { return m.authDeviceID }
func (m *mockSession) IsRegistered() bool            { return m.registered }
func (m *mockSession) SetRegistered(msg protocol.RegisterMessage) {
	m.registered = true
	m.regMsg = msg
}

type mockFailingDeviceRepo struct {
	*repository.MemoryDeviceRepository
	saveErr error
}

func (m *mockFailingDeviceRepo) SaveEnrolledDevice(ctx context.Context, dev repository.EnrolledDevice) error {
	if m.saveErr != nil {
		return m.saveErr
	}
	return m.MemoryDeviceRepository.SaveEnrolledDevice(ctx, dev)
}

func TestHandler_HandleSuccess(t *testing.T) {
	repo := repository.NewMemoryDeviceRepository()
	service := NewServiceWithRepository(repo)
	handler := NewHandler(service)

	session := &mockSession{authDeviceID: "HOST-001"}
	msg, err := protocol.NewMessage(protocol.TypeRegister, 1, protocol.RegisterMessage{
		DeviceID:   "HOST-001",
		DeviceName: "Test Node",
		AppVersion: "1.0.0",
		Model:      "TestModel",
	})
	if err != nil {
		t.Fatalf("failed to create register message: %v", err)
	}

	resp, err := handler.Handle(context.Background(), session, *msg)
	if err != nil {
		t.Fatalf("handler.Handle returned error: %v", err)
	}

	if resp.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected response type %s, got %s", protocol.TypeRegisterAck, resp.Type)
	}

	if !session.IsRegistered() {
		t.Fatal("expected session to be marked as registered")
	}

	enrolled, err := repo.GetEnrolledDevice(context.Background(), "HOST-001")
	if err != nil {
		t.Fatalf("failed to get enrolled device: %v", err)
	}
	if enrolled.DeviceName != "Test Node" {
		t.Fatalf("expected device name 'Test Node', got %s", enrolled.DeviceName)
	}
}

func TestHandler_HandleEnrollmentFailureRejectsRegistration(t *testing.T) {
	expectedErr := errors.New("database connection refused")
	repo := &mockFailingDeviceRepo{
		MemoryDeviceRepository: repository.NewMemoryDeviceRepository(),
		saveErr:                expectedErr,
	}
	service := NewServiceWithRepository(repo)
	handler := NewHandler(service)

	session := &mockSession{authDeviceID: "HOST-001"}
	msg, err := protocol.NewMessage(protocol.TypeRegister, 1, protocol.RegisterMessage{
		DeviceID:   "HOST-001",
		DeviceName: "Test Node",
		AppVersion: "1.0.0",
		Model:      "TestModel",
	})
	if err != nil {
		t.Fatalf("failed to create register message: %v", err)
	}

	resp, err := handler.Handle(context.Background(), session, *msg)
	if err == nil {
		t.Fatal("expected error from handler.Handle on enrollment failure, got nil")
	}
	if !errors.Is(err, expectedErr) {
		t.Fatalf("expected error %v, got %v", expectedErr, err)
	}
	if resp != nil {
		t.Fatalf("expected nil response on failure, got %+v", resp)
	}
	if session.IsRegistered() {
		t.Fatal("expected session NOT to be marked as registered when enrollment fails")
	}
}

type mockCountingFcmRegistrar struct {
	calls int
	err   error
}

func (m *mockCountingFcmRegistrar) RegisterToken(ctx context.Context, deviceID, fcmToken string) error {
	m.calls++
	return m.err
}

func TestHandler_HandleFCMRegistrationSuccessAndFailure(t *testing.T) {
	repo := repository.NewMemoryDeviceRepository()
	service := NewServiceWithRepository(repo)
	handler := NewHandler(service)

	// 1. Success case: FCM token persisted exactly once
	fcmMock := &mockCountingFcmRegistrar{}
	handler.SetFcmRegistrar(fcmMock)

	session := &mockSession{authDeviceID: "HOST-001"}
	msg, err := protocol.NewMessage(protocol.TypeRegister, 1, protocol.RegisterMessage{
		DeviceID:   "HOST-001",
		DeviceName: "Test Node",
		AppVersion: "1.0.0",
		Model:      "TestModel",
		FcmToken:   "sample-token",
	})
	if err != nil {
		t.Fatalf("failed to create register message: %v", err)
	}

	resp, err := handler.Handle(context.Background(), session, *msg)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if resp == nil || resp.Type != protocol.TypeRegisterAck {
		t.Fatalf("expected TypeRegisterAck, got %+v", resp)
	}
	if !session.IsRegistered() {
		t.Fatal("expected session to be marked as registered")
	}
	if fcmMock.calls != 1 {
		t.Fatalf("expected fcm registration called exactly once, got %d", fcmMock.calls)
	}

	// 2. Failure case: FCM token persistence fails -> aborts registration, session unregistered
	fcmFailing := &mockCountingFcmRegistrar{err: errors.New("fcm db write error")}
	handler.SetFcmRegistrar(fcmFailing)

	sessionFail := &mockSession{authDeviceID: "HOST-002"}
	msgFail, _ := protocol.NewMessage(protocol.TypeRegister, 2, protocol.RegisterMessage{
		DeviceID:   "HOST-002",
		DeviceName: "Test Node 2",
		AppVersion: "1.0.0",
		Model:      "TestModel",
		FcmToken:   "sample-token-2",
	})

	respFail, errFail := handler.Handle(context.Background(), sessionFail, *msgFail)
	if errFail == nil {
		t.Fatal("expected error when FCM persistence fails, got nil")
	}
	if respFail != nil {
		t.Fatalf("expected nil response on failure, got %+v", respFail)
	}
	if sessionFail.IsRegistered() {
		t.Fatal("session must NOT be registered when FCM persistence fails")
	}
	if fcmFailing.calls != 1 {
		t.Fatalf("expected fcm registration called exactly once, got %d", fcmFailing.calls)
	}
	// Verify device was NOT enrolled when FCM token persistence failed!
	if _, err := repo.GetEnrolledDevice(context.Background(), "HOST-002"); err == nil {
		t.Fatal("device HOST-002 must NOT be enrolled when FCM token persistence fails")
	}
}
