package database

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strings"
	"time"

	_ "github.com/lib/pq"
	"github.com/xaiop/project-sentinel/server/internal/config"
	"go.uber.org/zap"
)

// PostgresDB manages a connection pool to a PostgreSQL database.
type PostgresDB struct {
	db     *sql.DB
	logger *zap.Logger
}

// NewPostgresDB establishes a connection pool to PostgreSQL, pinging with timeout
// and running idempotent migrations.
func NewPostgresDB(cfg config.DatabaseConfig, logger *zap.Logger) (*PostgresDB, error) {
	connStr := buildConnectionString(cfg)

	db, err := sql.Open("postgres", connStr)
	if err != nil {
		return nil, fmt.Errorf("open postgres connection: %w", err)
	}

	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(15 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("ping postgres: %w", err)
	}

	pg := &PostgresDB{
		db:     db,
		logger: logger,
	}

	if err := pg.runMigrations(context.Background()); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("run postgres migrations: %w", err)
	}

	if logger != nil {
		logger.Info("connected to postgresql and verified schema")
	}

	return pg, nil
}

// DB returns the underlying *sql.DB handle.
func (p *PostgresDB) DB() *sql.DB {
	return p.db
}

// Close closes the underlying connection pool.
func (p *PostgresDB) Close() error {
	if p.db != nil {
		return p.db.Close()
	}
	return nil
}

// Ping checks if the database is reachable.
func (p *PostgresDB) Ping(ctx context.Context) error {
	if p.db == nil {
		return fmt.Errorf("database not initialized")
	}
	return p.db.PingContext(ctx)
}

func (p *PostgresDB) runMigrations(ctx context.Context) error {
	schema := `
	CREATE TABLE IF NOT EXISTS device_fcm_tokens (
		device_id VARCHAR(128) PRIMARY KEY,
		token TEXT NOT NULL,
		model VARCHAR(128) DEFAULT '',
		created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
		updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS enrolled_devices (
		device_id VARCHAR(128) PRIMARY KEY,
		device_name VARCHAR(255) NOT NULL,
		app_version VARCHAR(64) NOT NULL,
		model VARCHAR(128) NOT NULL,
		last_seen TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
		created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
		updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS device_battery_history (
		id BIGSERIAL PRIMARY KEY,
		device_id VARCHAR(128) NOT NULL,
		battery_percent INT NOT NULL,
		battery_temp_c DOUBLE PRECISION NOT NULL,
		battery_voltage_mv INT NOT NULL,
		current_now_ua INT NOT NULL DEFAULT 0,
		is_charging BOOLEAN NOT NULL DEFAULT FALSE,
		thermal_status VARCHAR(64) DEFAULT 'Nominal',
		recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS device_location_history (
		id BIGSERIAL PRIMARY KEY,
		device_id VARCHAR(128) NOT NULL,
		latitude DOUBLE PRECISION NOT NULL,
		longitude DOUBLE PRECISION NOT NULL,
		accuracy REAL NOT NULL DEFAULT 0,
		battery INT NOT NULL DEFAULT -1,
		network VARCHAR(128) NOT NULL DEFAULT '',
		recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS admin_access_control (
		token_sha256 VARCHAR(64) PRIMARY KEY,
		admin_id VARCHAR(64) NOT NULL,
		role VARCHAR(32) NOT NULL DEFAULT 'TEMP_OPERATOR',
		status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
		label VARCHAR(128) NOT NULL DEFAULT '',
		device_model VARCHAR(64) NOT NULL DEFAULT '',
		silicon_dna_hash VARCHAR(64) NOT NULL DEFAULT '',
		spki_pin_sha256 VARCHAR(64) NOT NULL DEFAULT '',
		permissions TEXT NOT NULL DEFAULT 'VIEW_TELEMETRY',
		issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
		expires_at TIMESTAMPTZ NOT NULL,
		last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
	);

	ALTER TABLE admin_access_control ADD COLUMN IF NOT EXISTS permissions TEXT NOT NULL DEFAULT 'VIEW_TELEMETRY';

	CREATE INDEX IF NOT EXISTS idx_device_fcm_tokens_model ON device_fcm_tokens(model);
	CREATE INDEX IF NOT EXISTS idx_enrolled_devices_last_seen ON enrolled_devices(last_seen DESC);
	CREATE INDEX IF NOT EXISTS idx_battery_history_device_time ON device_battery_history(device_id, recorded_at DESC);
	CREATE INDEX IF NOT EXISTS idx_location_history_device_time ON device_location_history(device_id, recorded_at ASC);
	CREATE INDEX IF NOT EXISTS idx_admin_access_control_admin_id ON admin_access_control(admin_id);

	ALTER TABLE device_fcm_tokens ENABLE ROW LEVEL SECURITY;
	ALTER TABLE enrolled_devices ENABLE ROW LEVEL SECURITY;
	ALTER TABLE device_battery_history ENABLE ROW LEVEL SECURITY;
	ALTER TABLE device_location_history ENABLE ROW LEVEL SECURITY;
	ALTER TABLE admin_access_control ENABLE ROW LEVEL SECURITY;
	`

	_, err := p.db.ExecContext(ctx, schema)
	return err
}

func buildConnectionString(cfg config.DatabaseConfig) string {
	// First priority: DATABASE_URL environment variable (Render standard)
	if envURL := strings.TrimSpace(os.Getenv("DATABASE_URL")); envURL != "" {
		return envURL
	}

	// Fallback to individual components
	host := cfg.Host
	if host == "" {
		host = "localhost"
	}
	port := cfg.Port
	if port == "" {
		port = "5432"
	}
	user := cfg.User
	if user == "" {
		user = "postgres"
	}
	dbName := cfg.Name
	if dbName == "" {
		dbName = "sentinel"
	}

	sslMode := "disable"
	if host != "localhost" && host != "127.0.0.1" {
		sslMode = "require"
	}

	return fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=%s",
		user, cfg.Password, host, port, dbName, sslMode,
	)
}
