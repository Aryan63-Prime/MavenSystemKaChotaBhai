package fcm

import (
	"context"
	"fmt"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"go.uber.org/zap"
	"google.golang.org/api/option"
)

// TokenStore persists FCM tokens durably across server restarts (e.g. in PostgreSQL).
type TokenStore interface {
	SaveFcmToken(ctx context.Context, deviceID, token, model string) error
	GetFcmToken(ctx context.Context, deviceID string) (string, error)
}

// Service manages FCM tokens and high-priority push messages to wake dormant Android devices.
type Service struct {
	client      *messaging.Client
	logger      *zap.Logger
	tokenLock   sync.RWMutex
	tokens      map[string]string // deviceID -> fcmToken (in-memory fast cache)
	versions    map[string]uint64 // deviceID -> revision version
	deviceLocks sync.Map          // deviceID -> *sync.Mutex (serializes updates per device)
	tokenStore  TokenStore        // durable PostgreSQL backing
}

// NewService instantiates Firebase Admin messaging client from service account credentials.
// It searches in order:
// 1. FCM_SERVICE_ACCOUNT_JSON environment variable (direct JSON string)
// 2. GOOGLE_APPLICATION_CREDENTIALS environment variable
// 3. /etc/secrets/serviceAccountKey.json (Render Secret File standard path)
// 4. credentialsFile parameter (e.g. local serviceAccountKey.json)
func NewService(credentialsFile string, logger *zap.Logger) (*Service, error) {
	var opt option.ClientOption
	ctx := context.Background()

	if rawJSON := os.Getenv("FCM_SERVICE_ACCOUNT_JSON"); rawJSON != "" {
		opt = option.WithCredentialsJSON([]byte(rawJSON))
		logger.Info("FCM service initialized via FCM_SERVICE_ACCOUNT_JSON environment variable")
	} else {
		targetPath := ""
		for _, candidate := range []string{
			os.Getenv("GOOGLE_APPLICATION_CREDENTIALS"),
			"/etc/secrets/serviceAccountKey.json",
			credentialsFile,
		} {
			if candidate != "" {
				if _, err := os.Stat(candidate); err == nil {
					targetPath = candidate
					break
				}
			}
		}

		if targetPath == "" {
			logger.Warn("FCM credentials not found (checked env FCM_SERVICE_ACCOUNT_JSON, /etc/secrets/serviceAccountKey.json, and local file). FCM push wakeup disabled.")
			return &Service{
				logger:   logger,
				tokens:   make(map[string]string),
				versions: make(map[string]uint64),
			}, nil
		}

		opt = option.WithCredentialsFile(targetPath)
		logger.Info("FCM high-priority messaging service initialized from credentials file", zap.String("path", targetPath))
	}

	app, err := firebase.NewApp(ctx, nil, opt)
	if err != nil {
		return nil, fmt.Errorf("init firebase app: %w", err)
	}

	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, fmt.Errorf("init messaging client: %w", err)
	}

	return &Service{
		client:   client,
		logger:   logger,
		tokens:   make(map[string]string),
		versions: make(map[string]uint64),
	}, nil
}

// SetTokenStore binds a durable token store (e.g. PostgreSQL repository) to the FCM service.
func (s *Service) SetTokenStore(ts TokenStore) {
	s.tokenLock.Lock()
	defer s.tokenLock.Unlock()
	s.tokenStore = ts
}

func (s *Service) getDeviceMutex(deviceID string) *sync.Mutex {
	val, _ := s.deviceLocks.LoadOrStore(deviceID, &sync.Mutex{})
	return val.(*sync.Mutex)
}

// RegisterToken maps a device ID to an FCM registration token synchronously with strict durability.
// Updates are serialized per device so older concurrent registrations cannot overwrite newer ones.
func (s *Service) RegisterToken(ctx context.Context, deviceID, fcmToken string) error {
	if deviceID == "" || fcmToken == "" {
		return nil
	}

	// 1. Serialize updates per device to prevent older registrations from overwriting newer ones (Issue 2)
	devLock := s.getDeviceMutex(deviceID)
	devLock.Lock()
	defer devLock.Unlock()

	s.tokenLock.RLock()
	store := s.tokenStore
	s.tokenLock.RUnlock()

	// 2. Persist to durable store FIRST before publishing to memory cache (Issue 1)
	if store != nil {
		if err := store.SaveFcmToken(ctx, deviceID, fcmToken, ""); err != nil {
			s.logger.Warn("failed to persist fcm token to durable store",
				zap.String("device_id", deviceID),
				zap.Error(err),
			)
			// Return error WITHOUT polluting the cache with an unpersisted token
			return err
		}
	}

	// 3. Only publish into in-memory cache and increment revision AFTER persistence succeeds
	s.tokenLock.Lock()
	s.tokens[deviceID] = fcmToken
	if s.versions == nil {
		s.versions = make(map[string]uint64)
	}
	s.versions[deviceID]++
	s.tokenLock.Unlock()

	s.logger.Info("registered and persisted fcm token for device", zap.String("device_id", deviceID))
	return nil
}

var hostSeqRegex = regexp.MustCompile(`^HOST\d+-`)

func (s *Service) resolveAliasCandidates(deviceID string) []string {
	var candidates []string

	// 1. If strings.HasPrefix(deviceID, "HOST-") && !strings.HasPrefix(deviceID, "HOST-001-") -> try "HOST-001-" + strings.TrimPrefix(deviceID, "HOST-")
	if strings.HasPrefix(deviceID, "HOST-") && !strings.HasPrefix(deviceID, "HOST-001-") {
		candidates = append(candidates, "HOST-001-"+strings.TrimPrefix(deviceID, "HOST-"))
	}

	// 2. Match Regex("^HOST\\d+-") (e.g. HOST002-VIVO-CFAE -> "HOST-001-VIVO-CFAE")
	if hostSeqRegex.MatchString(deviceID) {
		suffix := hostSeqRegex.ReplaceAllString(deviceID, "")
		candidates = append(candidates, "HOST-001-"+suffix, "HOST-"+suffix)
	}

	// 3. If strings.HasPrefix(deviceID, "HOST-001-") -> try "HOST-" + strings.TrimPrefix(deviceID, "HOST-001-")
	if strings.HasPrefix(deviceID, "HOST-001-") {
		candidates = append(candidates, "HOST-"+strings.TrimPrefix(deviceID, "HOST-001-"))
	}

	// 4. If strings.Contains(deviceID, "_") -> try strings.SplitN(deviceID, "_", 2)[0] (when not "HOST-001")
	if strings.Contains(deviceID, "_") {
		base := strings.SplitN(deviceID, "_", 2)[0]
		if base != "" && base != "HOST-001" {
			candidates = append(candidates, base)
			if strings.HasPrefix(base, "HOST-") && !strings.HasPrefix(base, "HOST-001-") {
				candidates = append(candidates, "HOST-001-"+strings.TrimPrefix(base, "HOST-"))
			}
		}
	}

	return candidates
}

func (s *Service) lookupToken(deviceID string) (string, bool) {
	s.tokenLock.RLock()
	t, ok := s.tokens[deviceID]
	store := s.tokenStore
	startVer := uint64(0)
	if s.versions != nil {
		startVer = s.versions[deviceID]
	}
	s.tokenLock.RUnlock()

	if ok && t != "" {
		return t, true
	}

	// Fallback to durable TokenStore (PostgreSQL) using exact lookup
	if store != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		tok, err := store.GetFcmToken(ctx, deviceID)
		if err == nil && tok != "" {
			s.tokenLock.Lock()
			currentVer := uint64(0)
			if s.versions != nil {
				currentVer = s.versions[deviceID]
			}
			currentTok := s.tokens[deviceID]

			// Ownership / version check:
			// If a newer token was registered while recovery was in flight (currentTok != "" or currentVer > startVer),
			// do NOT overwrite the cache with the recovered stale token! (Issue 3)
			if currentTok != "" || currentVer > startVer {
				tok = currentTok
				ok = (currentTok != "")
			} else {
				s.tokens[deviceID] = tok
				if s.versions == nil {
					s.versions = make(map[string]uint64)
				}
				s.versions[deviceID] = startVer + 1
				s.logger.Info("recovered fcm token from durable storage", zap.String("device_id", deviceID))
				ok = true
			}
			s.tokenLock.Unlock()
			return tok, ok
		}
	}

	return "", false
}

// GetToken returns the registered FCM token for a device ID using strict exact matching and version-guarded recovery.
func (s *Service) GetToken(deviceID string) (string, bool) {
	if deviceID == "" {
		return "", false
	}

	if tok, ok := s.lookupToken(deviceID); ok && tok != "" {
		return tok, true
	}

	for _, cand := range s.resolveAliasCandidates(deviceID) {
		if cand != "" && cand != deviceID {
			if tok, ok := s.lookupToken(cand); ok && tok != "" {
				return tok, true
			}
		}
	}

	return "", false
}

// WakeDevice dispatches a high-priority FCM data message to wake an Android host from Doze mode.
func (s *Service) WakeDevice(ctx context.Context, deviceID string) (string, error) {
	if s.client == nil {
		return "", fmt.Errorf("fcm client not initialized")
	}

	token, ok := s.GetToken(deviceID)
	if !ok || token == "" {
		return "", fmt.Errorf("no fcm token registered for device %s", deviceID)
	}

	msg := &messaging.Message{
		Token: token,
		Data: map[string]string{
			"action":    "WAKE_UP",
			"deviceId":  deviceID,
			"timestamp": fmt.Sprintf("%d", time.Now().UnixMilli()),
		},
		Android: &messaging.AndroidConfig{
			Priority: "high", // High priority is required to wake from Android Doze mode
		},
	}

	msgID, err := s.client.Send(ctx, msg)
	if err != nil {
		s.logger.Error("failed to send fcm wakeup message",
			zap.String("device_id", deviceID),
			zap.Error(err),
		)
		return "", err
	}

	s.logger.Info("sent high-priority fcm wakeup message",
		zap.String("device_id", deviceID),
		zap.String("message_id", msgID),
	)
	return msgID, nil
}
