package repository

import (
	"context"
	"fmt"
	"time"
)

// ListenerStore is the Redis operation subset required by listener persistence.
type ListenerStore interface {
	Set(ctx context.Context, key string, value []byte, ttl time.Duration) error
	Get(ctx context.Context, key string) ([]byte, bool, error)
	Delete(ctx context.Context, key string) error
}

// ListenerRepository manages audio listener mappings.
type ListenerRepository interface {
	SetListener(ctx context.Context, deviceID string, connectionID string) error
	GetListener(ctx context.Context, deviceID string) (string, bool, error)
	RemoveListener(ctx context.Context, deviceID string) error
}

// RedisListenerRepository stores listener mappings in Redis.
type RedisListenerRepository struct {
	client ListenerStore
}

// NewRedisListenerRepository creates a Redis listener repository.
func NewRedisListenerRepository(client ListenerStore) *RedisListenerRepository {
	return &RedisListenerRepository{client: client}
}

// SetListener stores the admin connection listening to a device.
func (r *RedisListenerRepository) SetListener(ctx context.Context, deviceID string, connectionID string) error {
	if r == nil || r.client == nil {
		return nil
	}

	if err := r.client.Set(ctx, listenerKey(deviceID), []byte(connectionID), 10*time.Minute); err != nil {
		return fmt.Errorf("set listener in redis: %w", err)
	}

	return nil
}

// GetListener returns the admin connection listening to a device.
func (r *RedisListenerRepository) GetListener(ctx context.Context, deviceID string) (string, bool, error) {
	if r == nil || r.client == nil {
		return "", false, nil
	}

	payload, found, err := r.client.Get(ctx, listenerKey(deviceID))
	if err != nil {
		return "", false, fmt.Errorf("get listener from redis: %w", err)
	}
	if !found {
		return "", false, nil
	}

	return string(payload), true, nil
}

// RemoveListener removes the listener mapping for a device.
func (r *RedisListenerRepository) RemoveListener(ctx context.Context, deviceID string) error {
	if r == nil || r.client == nil {
		return nil
	}

	if err := r.client.Delete(ctx, listenerKey(deviceID)); err != nil {
		return fmt.Errorf("remove listener from redis: %w", err)
	}

	return nil
}

func listenerKey(deviceID string) string {
	return "listener:" + deviceID
}
