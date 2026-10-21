package repository

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"sync"
	"time"
)

var (
	ErrDeviceNotFound = errors.New("device not found")
	ErrTokenNotFound  = errors.New("fcm token not found")
)

// EnrolledDevice represents a registered host device persisted in the database.
type EnrolledDevice struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	AppVersion string    `json:"appVersion"`
	Model      string    `json:"model"`
	LastSeen   time.Time `json:"lastSeen"`
	CreatedAt  time.Time `json:"createdAt"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

// BatteryHistoryEntry represents a point-in-time battery and thermal reading.
type BatteryHistoryEntry struct {
	ID               int64     `json:"id"`
	DeviceID         string    `json:"deviceId"`
	BatteryPercent   int       `json:"batteryPercent"`
	BatteryTempC     float64   `json:"batteryTempC"`
	BatteryVoltageMv int       `json:"batteryVoltageMv"`
	CurrentNowUa     int       `json:"currentNowUa"`
	IsCharging       bool      `json:"isCharging"`
	ThermalStatus    string    `json:"thermalStatus"`
	RecordedAt       time.Time `json:"recordedAt"`
}

// DeviceRepository defines durable storage for FCM tokens, enrolled host metadata, and battery history.
type DeviceRepository interface {
	SaveFcmToken(ctx context.Context, deviceID, token, model string) error
	GetFcmToken(ctx context.Context, deviceID string) (string, error)
	SaveEnrolledDevice(ctx context.Context, dev EnrolledDevice) error
	SaveEnrolledDeviceWithToken(ctx context.Context, dev EnrolledDevice, token string) error
	GetEnrolledDevice(ctx context.Context, deviceID string) (*EnrolledDevice, error)
	ListEnrolledDevices(ctx context.Context) ([]EnrolledDevice, error)
	SaveBatteryHistory(ctx context.Context, entry BatteryHistoryEntry) error
	GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]BatteryHistoryEntry, error)
	SaveLocationHistory(ctx context.Context, loc Location) error
	GetLocationHistory(ctx context.Context, deviceID string, from, to time.Time, limit int) ([]Location, error)
}

// PostgresDeviceRepository implements DeviceRepository backed by PostgreSQL.
type PostgresDeviceRepository struct {
	db *sql.DB
}

// NewPostgresDeviceRepository creates a new PostgresDeviceRepository.
func NewPostgresDeviceRepository(db *sql.DB) *PostgresDeviceRepository {
	return &PostgresDeviceRepository{db: db}
}

// SaveFcmToken upserts an FCM token for a device into device_fcm_tokens.
func (r *PostgresDeviceRepository) SaveFcmToken(ctx context.Context, deviceID, token, model string) error {
	deviceID = strings.TrimSpace(deviceID)
	token = strings.TrimSpace(token)
	if deviceID == "" || token == "" {
		return nil
	}

	query := `
	INSERT INTO device_fcm_tokens (device_id, token, model, created_at, updated_at)
	VALUES ($1, $2, $3, NOW(), NOW())
	ON CONFLICT (device_id) DO UPDATE
	SET token = EXCLUDED.token,
	    model = CASE WHEN EXCLUDED.model <> '' THEN EXCLUDED.model ELSE device_fcm_tokens.model END,
	    updated_at = NOW();
	`

	_, err := r.db.ExecContext(ctx, query, deviceID, token, model)
	return err
}

// GetFcmToken retrieves the FCM token for a device ID using strict exact matching.
func (r *PostgresDeviceRepository) GetFcmToken(ctx context.Context, deviceID string) (string, error) {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return "", ErrTokenNotFound
	}

	var token string
	err := r.db.QueryRowContext(ctx,
		"SELECT token FROM device_fcm_tokens WHERE device_id = $1 LIMIT 1",
		deviceID,
	).Scan(&token)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", ErrTokenNotFound
		}
		return "", err
	}

	return token, nil
}

// SaveEnrolledDevice upserts device enrollment metadata into enrolled_devices.
func (r *PostgresDeviceRepository) SaveEnrolledDevice(ctx context.Context, dev EnrolledDevice) error {
	dev.DeviceID = strings.TrimSpace(dev.DeviceID)
	if dev.DeviceID == "" {
		return errors.New("empty device id")
	}

	query := `
	INSERT INTO enrolled_devices (device_id, device_name, app_version, model, last_seen, created_at, updated_at)
	VALUES ($1, $2, $3, $4, NOW(), NOW(), NOW())
	ON CONFLICT (device_id) DO UPDATE
	SET device_name = EXCLUDED.device_name,
	    app_version = EXCLUDED.app_version,
	    model = EXCLUDED.model,
	    last_seen = NOW(),
	    updated_at = NOW();
	`

	_, err := r.db.ExecContext(ctx, query, dev.DeviceID, dev.DeviceName, dev.AppVersion, dev.Model)
	return err
}

// SaveEnrolledDeviceWithToken atomically commits enrolled device metadata and FCM token in a single PostgreSQL transaction.
func (r *PostgresDeviceRepository) SaveEnrolledDeviceWithToken(ctx context.Context, dev EnrolledDevice, token string) error {
	dev.DeviceID = strings.TrimSpace(dev.DeviceID)
	if dev.DeviceID == "" {
		return errors.New("empty device id")
	}
	token = strings.TrimSpace(token)

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	enrollQuery := `
	INSERT INTO enrolled_devices (device_id, device_name, app_version, model, last_seen, created_at, updated_at)
	VALUES ($1, $2, $3, $4, NOW(), NOW(), NOW())
	ON CONFLICT (device_id) DO UPDATE
	SET device_name = EXCLUDED.device_name,
	    app_version = EXCLUDED.app_version,
	    model = EXCLUDED.model,
	    last_seen = NOW(),
	    updated_at = NOW();
	`
	if _, err := tx.ExecContext(ctx, enrollQuery, dev.DeviceID, dev.DeviceName, dev.AppVersion, dev.Model); err != nil {
		return err
	}

	if token != "" {
		tokenQuery := `
		INSERT INTO device_fcm_tokens (device_id, token, model, created_at, updated_at)
		VALUES ($1, $2, $3, NOW(), NOW())
		ON CONFLICT (device_id) DO UPDATE
		SET token = EXCLUDED.token,
		    model = CASE WHEN EXCLUDED.model <> '' THEN EXCLUDED.model ELSE device_fcm_tokens.model END,
		    updated_at = NOW();
		`
		if _, err := tx.ExecContext(ctx, tokenQuery, dev.DeviceID, token, dev.Model); err != nil {
			return err
		}
	}

	return tx.Commit()
}

// GetEnrolledDevice retrieves a single enrolled device by its device ID.
func (r *PostgresDeviceRepository) GetEnrolledDevice(ctx context.Context, deviceID string) (*EnrolledDevice, error) {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, ErrDeviceNotFound
	}

	query := `
	SELECT device_id, device_name, app_version, model, last_seen, created_at, updated_at
	FROM enrolled_devices
	WHERE device_id = $1
	LIMIT 1;
	`

	var dev EnrolledDevice
	err := r.db.QueryRowContext(ctx, query, deviceID).Scan(
		&dev.DeviceID,
		&dev.DeviceName,
		&dev.AppVersion,
		&dev.Model,
		&dev.LastSeen,
		&dev.CreatedAt,
		&dev.UpdatedAt,
	)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrDeviceNotFound
		}
		return nil, err
	}

	return &dev, nil
}

// ListEnrolledDevices returns all enrolled devices ordered by last seen.
func (r *PostgresDeviceRepository) ListEnrolledDevices(ctx context.Context) ([]EnrolledDevice, error) {
	query := `
	SELECT device_id, device_name, app_version, model, last_seen, created_at, updated_at
	FROM enrolled_devices
	ORDER BY last_seen DESC;
	`

	rows, err := r.db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var devices []EnrolledDevice
	for rows.Next() {
		var dev EnrolledDevice
		if err := rows.Scan(
			&dev.DeviceID,
			&dev.DeviceName,
			&dev.AppVersion,
			&dev.Model,
			&dev.LastSeen,
			&dev.CreatedAt,
			&dev.UpdatedAt,
		); err != nil {
			return nil, err
		}
		devices = append(devices, dev)
	}

	return devices, rows.Err()
}

// SaveBatteryHistory inserts a historical battery and thermal record into device_battery_history.
func (r *PostgresDeviceRepository) SaveBatteryHistory(ctx context.Context, entry BatteryHistoryEntry) error {
	entry.DeviceID = strings.TrimSpace(entry.DeviceID)
	if entry.DeviceID == "" {
		return errors.New("empty device id")
	}

	query := `
	INSERT INTO device_battery_history (device_id, battery_percent, battery_temp_c, battery_voltage_mv, current_now_ua, is_charging, thermal_status, recorded_at)
	VALUES ($1, $2, $3, $4, $5, $6, $7, $8);
	`
	if entry.RecordedAt.IsZero() {
		entry.RecordedAt = time.Now().UTC()
	}

	_, err := r.db.ExecContext(ctx, query,
		entry.DeviceID, entry.BatteryPercent, entry.BatteryTempC,
		entry.BatteryVoltageMv, entry.CurrentNowUa, entry.IsCharging,
		entry.ThermalStatus, entry.RecordedAt,
	)
	return err
}

// GetBatteryHistory queries device_battery_history ordered by recorded_at DESC.
func (r *PostgresDeviceRepository) GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]BatteryHistoryEntry, error) {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, errors.New("empty device id")
	}
	if limit <= 0 || limit > 1000 {
		limit = 100
	}

	query := `
	SELECT id, device_id, battery_percent, battery_temp_c, battery_voltage_mv, current_now_ua, is_charging, thermal_status, recorded_at
	FROM device_battery_history
	WHERE device_id = $1
	ORDER BY recorded_at DESC
	LIMIT $2;
	`

	rows, err := r.db.QueryContext(ctx, query, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var entries []BatteryHistoryEntry
	for rows.Next() {
		var e BatteryHistoryEntry
		if err := rows.Scan(
			&e.ID, &e.DeviceID, &e.BatteryPercent, &e.BatteryTempC,
			&e.BatteryVoltageMv, &e.CurrentNowUa, &e.IsCharging,
			&e.ThermalStatus, &e.RecordedAt,
		); err != nil {
			return nil, err
		}
		entries = append(entries, e)
	}

	return entries, rows.Err()
}

// SaveLocationHistory inserts a breadcrumb into device_location_history.
func (r *PostgresDeviceRepository) SaveLocationHistory(ctx context.Context, loc Location) error {
	loc.DeviceID = strings.TrimSpace(loc.DeviceID)
	if loc.DeviceID == "" {
		return errors.New("empty device id")
	}
	if loc.RecordedAt.IsZero() {
		loc.RecordedAt = time.Now().UTC()
	}

	query := `
	INSERT INTO device_location_history (device_id, latitude, longitude, accuracy, battery, network, recorded_at)
	VALUES ($1, $2, $3, $4, $5, $6, $7);
	`
	_, err := r.db.ExecContext(ctx, query,
		loc.DeviceID, loc.Latitude, loc.Longitude,
		loc.Accuracy, loc.Battery, loc.Network, loc.RecordedAt,
	)
	return err
}

// GetLocationHistory retrieves chronological location breadcrumbs between from and to.
func (r *PostgresDeviceRepository) GetLocationHistory(ctx context.Context, deviceID string, from, to time.Time, limit int) ([]Location, error) {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, errors.New("empty device id")
	}
	if limit <= 0 || limit > 5000 {
		limit = 2000
	}
	if from.IsZero() {
		from = time.Now().UTC().Add(-24 * time.Hour)
	}
	if to.IsZero() {
		to = time.Now().UTC().Add(time.Hour)
	}

	query := `
	SELECT device_id, latitude, longitude, accuracy, battery, network, recorded_at
	FROM device_location_history
	WHERE device_id = $1 AND recorded_at >= $2 AND recorded_at <= $3
	ORDER BY recorded_at ASC
	LIMIT $4;
	`
	rows, err := r.db.QueryContext(ctx, query, deviceID, from, to, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var locations []Location
	for rows.Next() {
		var l Location
		if err := rows.Scan(
			&l.DeviceID, &l.Latitude, &l.Longitude,
			&l.Accuracy, &l.Battery, &l.Network, &l.RecordedAt,
		); err != nil {
			return nil, err
		}
		locations = append(locations, l)
	}
	if locations == nil {
		locations = []Location{}
	}
	return locations, rows.Err()
}

// MemoryDeviceRepository provides an in-memory thread-safe implementation of DeviceRepository.
type MemoryDeviceRepository struct {
	mu              sync.RWMutex
	tokens          map[string]string                 // device_id -> token
	devices         map[string]EnrolledDevice         // device_id -> EnrolledDevice
	batteryHistory  map[string][]BatteryHistoryEntry // device_id -> entries
	locationHistory map[string][]Location            // device_id -> locations
}

// NewMemoryDeviceRepository creates a new in-memory DeviceRepository.
func NewMemoryDeviceRepository() *MemoryDeviceRepository {
	return &MemoryDeviceRepository{
		tokens:          make(map[string]string),
		devices:         make(map[string]EnrolledDevice),
		batteryHistory:  make(map[string][]BatteryHistoryEntry),
		locationHistory: make(map[string][]Location),
	}
}

func (m *MemoryDeviceRepository) SaveFcmToken(ctx context.Context, deviceID, token, model string) error {
	_ = ctx
	_ = model
	deviceID = strings.TrimSpace(deviceID)
	token = strings.TrimSpace(token)
	if deviceID == "" || token == "" {
		return nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[deviceID] = token
	return nil
}

func (m *MemoryDeviceRepository) GetFcmToken(ctx context.Context, deviceID string) (string, error) {
	_ = ctx
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return "", ErrTokenNotFound
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	if tok, ok := m.tokens[deviceID]; ok && tok != "" {
		return tok, nil
	}

	return "", ErrTokenNotFound
}

func (m *MemoryDeviceRepository) SaveEnrolledDevice(ctx context.Context, dev EnrolledDevice) error {
	_ = ctx
	dev.DeviceID = strings.TrimSpace(dev.DeviceID)
	if dev.DeviceID == "" {
		return errors.New("empty device id")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if existing, ok := m.devices[dev.DeviceID]; ok {
		dev.CreatedAt = existing.CreatedAt
	} else {
		dev.CreatedAt = now
	}
	dev.LastSeen = now
	dev.UpdatedAt = now

	m.devices[dev.DeviceID] = dev
	return nil
}

func (m *MemoryDeviceRepository) SaveEnrolledDeviceWithToken(ctx context.Context, dev EnrolledDevice, token string) error {
	_ = ctx
	dev.DeviceID = strings.TrimSpace(dev.DeviceID)
	if dev.DeviceID == "" {
		return errors.New("empty device id")
	}
	token = strings.TrimSpace(token)

	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if existing, ok := m.devices[dev.DeviceID]; ok {
		dev.CreatedAt = existing.CreatedAt
	} else {
		dev.CreatedAt = now
	}
	dev.LastSeen = now
	dev.UpdatedAt = now

	m.devices[dev.DeviceID] = dev
	if token != "" {
		m.tokens[dev.DeviceID] = token
	}
	return nil
}

func (m *MemoryDeviceRepository) GetEnrolledDevice(ctx context.Context, deviceID string) (*EnrolledDevice, error) {
	_ = ctx
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, ErrDeviceNotFound
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	if dev, ok := m.devices[deviceID]; ok {
		copyDev := dev
		return &copyDev, nil
	}

	return nil, ErrDeviceNotFound
}

func (m *MemoryDeviceRepository) ListEnrolledDevices(ctx context.Context) ([]EnrolledDevice, error) {
	_ = ctx
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]EnrolledDevice, 0, len(m.devices))
	for _, dev := range m.devices {
		list = append(list, dev)
	}

	return list, nil
}

func (m *MemoryDeviceRepository) SaveBatteryHistory(ctx context.Context, entry BatteryHistoryEntry) error {
	_ = ctx
	entry.DeviceID = strings.TrimSpace(entry.DeviceID)
	if entry.DeviceID == "" {
		return errors.New("empty device id")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if entry.RecordedAt.IsZero() {
		entry.RecordedAt = time.Now().UTC()
	}
	entry.ID = int64(len(m.batteryHistory[entry.DeviceID]) + 1)

	// Prepend entry (newest first)
	m.batteryHistory[entry.DeviceID] = append([]BatteryHistoryEntry{entry}, m.batteryHistory[entry.DeviceID]...)
	return nil
}

func (m *MemoryDeviceRepository) GetBatteryHistory(ctx context.Context, deviceID string, limit int) ([]BatteryHistoryEntry, error) {
	_ = ctx
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, errors.New("empty device id")
	}
	if limit <= 0 || limit > 1000 {
		limit = 100
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	history, ok := m.batteryHistory[deviceID]
	if !ok || len(history) == 0 {
		return []BatteryHistoryEntry{}, nil
	}

	if limit > len(history) {
		limit = len(history)
	}
	res := make([]BatteryHistoryEntry, limit)
	copy(res, history[:limit])
	return res, nil
}

func (m *MemoryDeviceRepository) SaveLocationHistory(ctx context.Context, loc Location) error {
	_ = ctx
	loc.DeviceID = strings.TrimSpace(loc.DeviceID)
	if loc.DeviceID == "" {
		return errors.New("empty device id")
	}
	if loc.RecordedAt.IsZero() {
		loc.RecordedAt = time.Now().UTC()
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.locationHistory[loc.DeviceID] = append(m.locationHistory[loc.DeviceID], loc)
	return nil
}

func (m *MemoryDeviceRepository) GetLocationHistory(ctx context.Context, deviceID string, from, to time.Time, limit int) ([]Location, error) {
	_ = ctx
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return nil, errors.New("empty device id")
	}
	if limit <= 0 || limit > 5000 {
		limit = 2000
	}
	if from.IsZero() {
		from = time.Now().UTC().Add(-24 * time.Hour)
	}
	if to.IsZero() {
		to = time.Now().UTC().Add(time.Hour)
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	history, ok := m.locationHistory[deviceID]
	if !ok || len(history) == 0 {
		return []Location{}, nil
	}

	var res []Location
	for _, loc := range history {
		if !loc.RecordedAt.Before(from) && !loc.RecordedAt.After(to) {
			res = append(res, loc)
			if len(res) >= limit {
				break
			}
		}
	}
	if res == nil {
		res = []Location{}
	}
	return res, nil
}
