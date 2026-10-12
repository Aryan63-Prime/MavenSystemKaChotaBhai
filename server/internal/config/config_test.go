package config

import (
	"testing"
)

func TestLoad_ReturnsValidConfig(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg == nil {
		t.Fatal("expected non-nil config")
	}
}

func TestLoad_Defaults(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.App.Name == "" {
		t.Error("expected non-empty app name")
	}
	if cfg.Server.Host == "" {
		t.Error("expected non-empty server host")
	}
	if cfg.Server.Port == "" {
		t.Error("expected non-empty server port")
	}
	if cfg.Redis.LocationTTLSeconds <= 0 {
		t.Errorf("expected positive location TTL, got %d", cfg.Redis.LocationTTLSeconds)
	}
}

func TestLoad_EnvOverride(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	t.Setenv("SERVER_PORT", "9999")
	t.Setenv("APP_NAME", "TestSentinel")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Server.Port != "9999" {
		t.Errorf("expected port 9999, got %s", cfg.Server.Port)
	}
	if cfg.App.Name != "TestSentinel" {
		t.Errorf("expected app name TestSentinel, got %s", cfg.App.Name)
	}
}

func TestLoad_RedisEnvOverride(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	t.Setenv("REDIS_HOST", "redis.example.com")
	t.Setenv("REDIS_PORT", "6380")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Redis.Host != "redis.example.com" {
		t.Errorf("expected redis host redis.example.com, got %s", cfg.Redis.Host)
	}
	if cfg.Redis.Port != "6380" {
		t.Errorf("expected redis port 6380, got %s", cfg.Redis.Port)
	}
}

func TestGet_Fallback(t *testing.T) {
	result := get("SENTINEL_TEST_NONEXISTENT_KEY", "fallback_value")
	if result != "fallback_value" {
		t.Errorf("expected fallback_value, got %s", result)
	}
}

func TestGetInt_Fallback(t *testing.T) {
	result := getInt("SENTINEL_TEST_NONEXISTENT_INT_KEY", 42)
	if result != 42 {
		t.Errorf("expected 42, got %d", result)
	}
}

func TestLoad_JWTConfig(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.JWT.Secret != "test-only-signing-secret-at-least-32-characters" {
		t.Errorf("expected JWT secret test-only-signing-secret-at-least-32-characters, got %s", cfg.JWT.Secret)
	}
}

func TestLoad_DatabaseConfig(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-only-signing-secret-at-least-32-characters")
	t.Setenv("DB_HOST", "db.example.com")
	t.Setenv("DB_PORT", "5433")
	t.Setenv("DB_NAME", "testdb")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Database.Host != "db.example.com" {
		t.Errorf("expected db host db.example.com, got %s", cfg.Database.Host)
	}
	if cfg.Database.Port != "5433" {
		t.Errorf("expected db port 5433, got %s", cfg.Database.Port)
	}
	if cfg.Database.Name != "testdb" {
		t.Errorf("expected db name testdb, got %s", cfg.Database.Name)
	}
}

func TestLoadRejectsUnsafeJWTSecret(t *testing.T) {
	for _, secret := range []string{"", "change_me", "short"} {
		t.Setenv("JWT_SECRET", secret)
		if _, err := Load(); err == nil {
			t.Fatal("unsafe JWT secret accepted")
		}
	}
}
