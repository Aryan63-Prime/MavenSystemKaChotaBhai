package fcm

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"go.uber.org/zap"
)

type mockTokenStore struct {
	mu              sync.Mutex
	tokens          map[string]string
	err             error
	saveDelay       time.Duration
	getDelay        time.Duration
	saveCallback    func(deviceID, token string)
	onGetTokenHook  func(deviceID string)
	onSaveTokenHook func(deviceID, token string)
}

func newMockTokenStore() *mockTokenStore {
	return &mockTokenStore{tokens: make(map[string]string)}
}

func (m *mockTokenStore) SaveFcmToken(ctx context.Context, deviceID, token, model string) error {
	if m.saveDelay > 0 {
		time.Sleep(m.saveDelay)
	}
	m.mu.Lock()
	if m.onSaveTokenHook != nil {
		m.onSaveTokenHook(deviceID, token)
	}
	if m.saveCallback != nil {
		m.saveCallback(deviceID, token)
	}
	err := m.err
	if err == nil {
		m.tokens[deviceID] = token
	}
	m.mu.Unlock()
	return err
}

func (m *mockTokenStore) GetFcmToken(ctx context.Context, deviceID string) (string, error) {
	if m.onGetTokenHook != nil {
		m.onGetTokenHook(deviceID)
	}
	if m.getDelay > 0 {
		time.Sleep(m.getDelay)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if tok, ok := m.tokens[deviceID]; ok {
		return tok, nil
	}
	return "", nil
}

func TestService_TokenRegistrationAndDurableRecovery(t *testing.T) {
	logger := zap.NewNop()
	svc := &Service{
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}

	store := newMockTokenStore()
	svc.SetTokenStore(store)

	// 1. Register token synchronously
	ctx := context.Background()
	err := svc.RegisterToken(ctx, "HOST-001", "fcm-token-12345")
	if err != nil {
		t.Fatalf("unexpected error registering token: %v", err)
	}

	// Verify in-memory retrieval immediately (synchronous persistence, no sleep needed)
	tok, ok := svc.GetToken("HOST-001")
	if !ok || tok != "fcm-token-12345" {
		t.Fatalf("expected fcm-token-12345, got %s (ok=%v)", tok, ok)
	}

	// 2. Strict exact match: loose prefix must NOT match
	if otherTok, found := svc.GetToken("HOST-001-other"); found {
		t.Fatalf("expected no match for HOST-001-other, got %s", otherTok)
	}
	if otherTok, found := svc.GetToken("HOST-001_Pixel9"); found {
		t.Fatalf("expected no match for HOST-001_Pixel9, got %s", otherTok)
	}

	// 3. Simulate server restart: new Service instance without in-memory tokens
	restartedSvc := &Service{
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}
	restartedSvc.SetTokenStore(store)

	// Verify token is recovered from durable storage on restart
	recoveredTok, ok := restartedSvc.GetToken("HOST-001")
	if !ok || recoveredTok != "fcm-token-12345" {
		t.Fatalf("expected recovered token fcm-token-12345, got %s (ok=%v)", recoveredTok, ok)
	}

	// Verify prefix does NOT match on restarted service either
	if otherTok, found := restartedSvc.GetToken("HOST-001-other"); found {
		t.Fatalf("expected no match for HOST-001-other on restarted service, got %s", otherTok)
	}

	// Verify it was cached in memory after recovery
	restartedSvc.tokenLock.RLock()
	cachedTok := restartedSvc.tokens["HOST-001"]
	restartedSvc.tokenLock.RUnlock()
	if cachedTok != "fcm-token-12345" {
		t.Fatalf("expected token to be cached in memory, got %s", cachedTok)
	}
}

func TestService_RegisterTokenDoesNotCacheOnFailure(t *testing.T) {
	logger := zap.NewNop()
	svc := &Service{
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}

	store := newMockTokenStore()
	store.err = errors.New("database disk full")
	svc.SetTokenStore(store)

	ctx := context.Background()
	err := svc.RegisterToken(ctx, "HOST-001", "fcm-token-12345")
	if err == nil {
		t.Fatal("expected RegisterToken to propagate store error, got nil")
	}

	// Verify cache was NOT polluted with unpersisted token
	svc.tokenLock.RLock()
	cachedTok, exists := svc.tokens["HOST-001"]
	svc.tokenLock.RUnlock()
	if exists || cachedTok != "" {
		t.Fatalf("cache must not contain token when persistence fails, got %s", cachedTok)
	}

	tok, found := svc.GetToken("HOST-001")
	if found || tok != "" {
		t.Fatalf("GetToken must return not found, got %s", tok)
	}
}

func TestService_ConcurrentRegistrationsSerializedPerDevice(t *testing.T) {
	logger := zap.NewNop()
	svc := &Service{
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}

	store := newMockTokenStore()
	svc.SetTokenStore(store)

	const goroutines = 20
	var wg sync.WaitGroup
	wg.Add(goroutines)

	// Explicit barrier: all goroutines wait on startBarrier before executing
	startBarrier := make(chan struct{})
	var mu sync.Mutex
	registeredTokens := make([]string, 0, goroutines)

	for i := 0; i < goroutines; i++ {
		go func(idx int) {
			defer wg.Done()
			uniqueToken := "token-unique-" + string(rune('A'+idx))
			<-startBarrier // Wait for explicit start release
			ctx := context.Background()
			if err := svc.RegisterToken(ctx, "HOST-SHARED", uniqueToken); err == nil {
				mu.Lock()
				registeredTokens = append(registeredTokens, uniqueToken)
				mu.Unlock()
			}
		}(i)
	}

	// Release all goroutines simultaneously
	close(startBarrier)
	wg.Wait()

	if len(registeredTokens) != goroutines {
		t.Fatalf("expected %d successful registrations, got %d", goroutines, len(registeredTokens))
	}

	// Monotonic version must equal exact number of serialized updates
	svc.tokenLock.RLock()
	ver := svc.versions["HOST-SHARED"]
	latestToken := svc.tokens["HOST-SHARED"]
	svc.tokenLock.RUnlock()

	if ver != uint64(goroutines) {
		t.Fatalf("expected version %d after serialized updates, got %d", goroutines, ver)
	}

	// Token in DB store must match cache token
	store.mu.Lock()
	dbToken := store.tokens["HOST-SHARED"]
	store.mu.Unlock()

	if dbToken != latestToken {
		t.Fatalf("expected DB token %s to match cached token %s", dbToken, latestToken)
	}
}

func TestService_RecoveryDoesNotOverwriteNewerToken(t *testing.T) {
	logger := zap.NewNop()
	svc := &Service{
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}

	store := newMockTokenStore()
	// Pre-populate DB with an old token
	store.tokens["HOST-001"] = "old-stale-token"

	// Explicit barriers: capture DB recovery in flight, rotate token, then release recovery
	recoveryStarted := make(chan struct{})
	releaseRecovery := make(chan struct{})

	store.onGetTokenHook = func(deviceID string) {
		select {
		case <-recoveryStarted:
			// Already closed
		default:
			close(recoveryStarted)
		}
		<-releaseRecovery // Block until explicit release from test
	}

	svc.SetTokenStore(store)

	var recoveredToken string
	var recoveryFound bool
	var wg sync.WaitGroup
	wg.Add(1)

	// Goroutine 1: Starts slow recovery
	go func() {
		defer wg.Done()
		recoveredToken, recoveryFound = svc.GetToken("HOST-001")
	}()

	// Wait explicitly until recovery query has entered DB lookup
	<-recoveryStarted

	// Rotate token while DB recovery query is in flight
	err := svc.RegisterToken(context.Background(), "HOST-001", "new-rotated-token")
	if err != nil {
		t.Fatalf("RegisterToken failed: %v", err)
	}

	// Now release the DB recovery to finish and attempt to publish its stale snapshot
	close(releaseRecovery)
	wg.Wait()

	// Verify that the stale DB result did NOT overwrite the new-rotated-token in cache!
	tok, ok := svc.GetToken("HOST-001")
	if !ok || tok != "new-rotated-token" {
		t.Fatalf("expected cache to retain new-rotated-token, got %s", tok)
	}

	if !recoveryFound || recoveredToken != "new-rotated-token" {
		t.Fatalf("expected recovery to return new-rotated-token, got %s", recoveredToken)
	}
}
