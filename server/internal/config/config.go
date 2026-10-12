package config

import (
	"fmt"
	"github.com/spf13/viper"
	"strings"
)

type Config struct {
	App      AppConfig
	Server   ServerConfig
	Database DatabaseConfig
	Redis    RedisConfig
	JWT      JWTConfig
}

type AppConfig struct {
	Name string
	Env  string
}

type ServerConfig struct {
	Host string
	Port string
}

type DatabaseConfig struct {
	Host     string
	Port     string
	User     string
	Password string
	Name     string
}

type RedisConfig struct {
	Host               string
	Port               string
	Password           string
	DB                 int
	LocationTTLSeconds int
}

type JWTConfig struct {
	Secret string
}

func Load() (*Config, error) {

	viper.SetConfigFile(".env")
	viper.AutomaticEnv()

	_ = viper.ReadInConfig()

	cfg := &Config{
		App: AppConfig{
			Name: get("APP_NAME", "ProjectSentinel"),
			Env:  get("APP_ENV", "development"),
		},
		Server: ServerConfig{
			Host: get("SERVER_HOST", "0.0.0.0"),
			Port: getWithFallback("SERVER_PORT", "PORT", "8080"),
		},
		Database: DatabaseConfig{
			Host:     get("DB_HOST", ""),
			Port:     get("DB_PORT", "5432"),
			User:     get("DB_USER", "postgres"),
			Password: get("DB_PASSWORD", "postgres"),
			Name:     get("DB_NAME", ""),
		},
		Redis: RedisConfig{
			Host:               get("REDIS_HOST", "localhost"),
			Port:               get("REDIS_PORT", "6379"),
			Password:           get("REDIS_PASSWORD", ""),
			DB:                 getInt("REDIS_DB", 0),
			LocationTTLSeconds: getInt("REDIS_LOCATION_TTL_SECONDS", 300),
		},
		JWT: JWTConfig{
			Secret: get("JWT_SECRET", ""),
		},
	}

	if len(strings.TrimSpace(cfg.JWT.Secret)) < 32 || cfg.JWT.Secret == "change_me" {
		return nil, fmt.Errorf("JWT_SECRET must be explicitly configured with at least 32 characters")
	}
	return cfg, nil
}

func get(key, fallback string) string {

	if viper.IsSet(key) {
		return viper.GetString(key)
	}

	return fallback
}

func getInt(key string, fallback int) int {

	if viper.IsSet(key) {
		return viper.GetInt(key)
	}

	return fallback
}

// getWithFallback checks the primary key first, then a fallback key, then the default.
// Useful for PaaS compatibility (e.g., Render sets PORT, we use SERVER_PORT).
func getWithFallback(primary, fallback, defaultVal string) string {
	if viper.IsSet(primary) {
		return viper.GetString(primary)
	}
	if viper.IsSet(fallback) {
		return viper.GetString(fallback)
	}
	return defaultVal
}
