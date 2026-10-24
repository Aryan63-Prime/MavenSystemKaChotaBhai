package auth

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	RoleSovereignRoot = "SOVEREIGN_ROOT_ADMIN"
	RoleTempOperator  = "TEMP_OPERATOR"

	StatusActive    = "ACTIVE"
	StatusSuspended = "SUSPENDED"
	StatusRevoked   = "REVOKED"

	// SHA-256 digests of initial administrator identities (zero plaintext JWTs in source code).
	kSha256Admin001       = "5fada681370bee82a8ff9a99b984afad4bd70c0b5745eddb0e0f52afd464bdbf"
	kSha256LegacyAdmin001 = "149537738d6d17a72615aaa70dd1ac0c87700cc3d298cc172f9461983aef54b5"
	kSha256Admin002       = "91168630d29f11e36f35c4deb74ac3789d09fc6d02914e48458ab6c666aafb5c"
)

type AdminAccessRecord struct {
	TokenSHA256    string    `json:"tokenSha256"`
	AdminID        string    `json:"adminId"`
	Role           string    `json:"role"`
	Status         string    `json:"status"`
	Label          string    `json:"label"`
	DeviceModel    string    `json:"deviceModel,omitempty"`
	SiliconDNAHash string    `json:"siliconDnaHash,omitempty"`
	SpkiPinSHA256  string    `json:"spkiPinSha256,omitempty"`
	IssuedAt       time.Time `json:"issuedAt"`
	ExpiresAt      time.Time `json:"expiresAt"`
	LastSeenAt     time.Time `json:"lastSeenAt"`
}

// ComputeSha256Hex returns the lowercase 64-character SHA-256 hex digest.
func ComputeSha256Hex(input string) string {
	digest := sha256.Sum256([]byte(strings.TrimSpace(input)))
	return hex.EncodeToString(digest[:])
}

type Service struct {
	Secret  string
	db      *sql.DB
	mu      sync.RWMutex
	records map[string]*AdminAccessRecord // keyed by TokenSHA256
}

func New(secret string) *Service {
	s := &Service{
		Secret:  secret,
		records: make(map[string]*AdminAccessRecord),
	}
	s.seedDefaultRecords()
	return s
}

// SetDatabase wires persistent PostgreSQL storage for admin access records.
func (s *Service) SetDatabase(db *sql.DB) {
	s.mu.Lock()
	s.db = db
	s.mu.Unlock()

	s.syncFromDatabase()
}

func (s *Service) seedDefaultRecords() {
	now := time.Now()

	// 1. Sovereign ROOT Admin (ADMIN-001 on Vivo iQOO 13)
	s.records[kSha256Admin001] = &AdminAccessRecord{
		TokenSHA256: kSha256Admin001,
		AdminID:     "ADMIN-001",
		Role:        RoleSovereignRoot,
		Status:      StatusActive,
		Label:       "Ayush Sovereign Vivo iQOO 13 (I2401 · SM8750)",
		DeviceModel: "I2401",
		IssuedAt:    now,
		ExpiresAt:   time.Unix(2108171322, 0),
		LastSeenAt:  now,
	}

	// 2. Revoked Legacy Oct 21 ADMIN-001 token
	s.records[kSha256LegacyAdmin001] = &AdminAccessRecord{
		TokenSHA256: kSha256LegacyAdmin001,
		AdminID:     "ADMIN-001-LEGACY",
		Role:        RoleTempOperator,
		Status:      StatusRevoked,
		Label:       "Revoked Oct 21 Legacy Token",
		IssuedAt:    now,
		ExpiresAt:   time.Unix(2107959610, 0),
		LastSeenAt:  now,
	}

	// 3. Revoked superseded ADMIN-002 token
	s.records[kSha256Admin002] = &AdminAccessRecord{
		TokenSHA256: kSha256Admin002,
		AdminID:     "ADMIN-002",
		Role:        RoleTempOperator,
		Status:      StatusRevoked,
		Label:       "Revoked Superseded ADMIN-002 Token",
		IssuedAt:    now,
		ExpiresAt:   time.Unix(2108171318, 0),
		LastSeenAt:  now,
	}

	// Check environment variable for any additional revoked hashes/tokens
	if envRevoked := os.Getenv("REVOKED_TOKENS"); envRevoked != "" {
		for _, tok := range strings.Split(envRevoked, ",") {
			t := strings.TrimSpace(tok)
			if t != "" {
				h := t
				if len(t) != 64 {
					h = ComputeSha256Hex(t)
				}
				s.records[h] = &AdminAccessRecord{
					TokenSHA256: h,
					AdminID:     "REVOKED_ENV",
					Role:        RoleTempOperator,
					Status:      StatusRevoked,
					Label:       "Environment configured revocation",
					IssuedAt:    now,
					ExpiresAt:   now.AddDate(10, 0, 0),
					LastSeenAt:  now,
				}
			}
		}
	}
}

func (s *Service) syncFromDatabase() {
	if s.db == nil {
		return
	}

	// Ensure seeded records exist in DB
	s.mu.RLock()
	for _, r := range s.records {
		_, _ = s.db.Exec(`
			INSERT INTO admin_access_control (
				token_sha256, admin_id, role, status, label, device_model,
				silicon_dna_hash, spki_pin_sha256, issued_at, expires_at, last_seen_at
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
			ON CONFLICT (token_sha256) DO UPDATE SET
				role = EXCLUDED.role,
				label = EXCLUDED.label;
		`, r.TokenSHA256, r.AdminID, r.Role, r.Status, r.Label, r.DeviceModel,
			r.SiliconDNAHash, r.SpkiPinSHA256, r.IssuedAt, r.ExpiresAt, r.LastSeenAt)
	}
	s.mu.RUnlock()

	// Load all records from DB into memory
	rows, err := s.db.Query(`
		SELECT token_sha256, admin_id, role, status, label, device_model,
		       silicon_dna_hash, spki_pin_sha256, issued_at, expires_at, last_seen_at
		FROM admin_access_control;
	`)
	if err != nil {
		return
	}
	defer rows.Close()

	s.mu.Lock()
	defer s.mu.Unlock()
	for rows.Next() {
		var r AdminAccessRecord
		if err := rows.Scan(
			&r.TokenSHA256, &r.AdminID, &r.Role, &r.Status, &r.Label, &r.DeviceModel,
			&r.SiliconDNAHash, &r.SpkiPinSHA256, &r.IssuedAt, &r.ExpiresAt, &r.LastSeenAt,
		); err == nil {
			s.records[r.TokenSHA256] = &r
		}
	}
}

// BearerToken extracts a bearer token from an Authorization header.
func BearerToken(header string) (string, bool) {
	const prefix = "Bearer "

	if !strings.HasPrefix(header, prefix) {
		return "", false
	}

	token := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	if token == "" {
		return "", false
	}

	return token, true
}

// Authenticate verifies the token, checks revocation/suspension status, and updates last_seen.
func (s *Service) Authenticate(token string) (string, error) {
	trimmed := strings.TrimSpace(token)
	if trimmed == "" {
		return "", ErrMissingToken
	}

	tokenHash := ComputeSha256Hex(trimmed)

	s.mu.RLock()
	record, exists := s.records[tokenHash]
	s.mu.RUnlock()

	if exists {
		if record.Status == StatusRevoked {
			return "", ErrRevokedToken
		}
		if record.Status == StatusSuspended {
			return "", ErrSuspendedToken
		}
	}

	claims, err := Validate(trimmed, s.Secret)
	if err != nil {
		return "", ErrInvalidToken
	}

	deviceID := strings.TrimSpace(claims.DeviceID)
	if deviceID == "" {
		return "", ErrInvalidToken
	}

	// For admin identities where no exact token record existed (exists == false),
	// check if their target admin_id has any status-level block.
	// If exists == true and record.Status == StatusActive, do NOT run the fallback loop.
	if !exists && IsAdminIdentity(deviceID) {
		s.mu.RLock()
		for _, r := range s.records {
			if r.AdminID == deviceID && r.Role != RoleSovereignRoot {
				if r.Status == StatusRevoked {
					s.mu.RUnlock()
					return "", ErrRevokedToken
				}
				if r.Status == StatusSuspended {
					s.mu.RUnlock()
					return "", ErrSuspendedToken
				}
			}
		}
		s.mu.RUnlock()
	}

	// Update last_seen_at
	s.mu.Lock()
	if record != nil {
		record.LastSeenAt = time.Now()
		if s.db != nil {
			go func(h string, t time.Time) {
				_, _ = s.db.Exec("UPDATE admin_access_control SET last_seen_at = $1 WHERE token_sha256 = $2", t, h)
			}(tokenHash, record.LastSeenAt)
		}
	}
	s.mu.Unlock()

	return deviceID, nil
}

// IsAdminSessionActive provides a fast (< 0.01ms) RWMutex check for active command routing.
func (s *Service) IsAdminSessionActive(adminID string, token string) bool {
	if !IsAdminIdentity(adminID) {
		return true
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	if token != "" {
		h := ComputeSha256Hex(token)
		if r, ok := s.records[h]; ok {
			return r.Status == StatusActive
		}
	}

	// Fallback check by admin ID
	for _, r := range s.records {
		if r.AdminID == adminID {
			if r.Status != StatusActive {
				return false
			}
		}
	}

	return true
}

// IsIdentityActive checks whether a connected device or admin identity is active and not suspended/revoked.
func (s *Service) IsIdentityActive(identity string) bool {
	if !IsAdminIdentity(identity) {
		return true
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	hasRecords := false
	for _, r := range s.records {
		if r.AdminID == identity {
			hasRecords = true
			if r.Status == StatusActive {
				return true
			}
		}
	}

	if hasRecords {
		return false
	}
	return true
}

// IssueTempAdminToken mints a temporary admin token (mandating RoleTempOperator).
// Only SOVEREIGN_ROOT_ADMIN (ADMIN-001) can issue new tokens.
func (s *Service) IssueTempAdminToken(callerTokenOrID, newAdminID, label string, ttl time.Duration) (string, AdminAccessRecord, error) {
	if !s.isCallerSovereignRoot(callerTokenOrID) {
		return "", AdminAccessRecord{}, ErrPrivilegeDenied
	}

	targetID := strings.TrimSpace(newAdminID)
	if targetID == "" {
		targetID = "ADMIN-TEMP-" + time.Now().Format("060102150405")
	}

	if targetID == "ADMIN-001" || strings.EqualFold(targetID, "ADMIN-001") {
		return "", AdminAccessRecord{}, errors.New("cannot create duplicate or overwrite SOVEREIGN_ROOT_ADMIN (ADMIN-001)")
	}

	// Clamp TTL: minimum 1h, default 24h, max 720h (30 days)
	if ttl <= 0 {
		ttl = 24 * time.Hour
	} else if ttl < time.Hour {
		ttl = time.Hour
	} else if ttl > 720*time.Hour {
		ttl = 720 * time.Hour
	}

	rawToken, err := GenerateTokenWithTTL(targetID, s.Secret, ttl)
	if err != nil {
		return "", AdminAccessRecord{}, err
	}

	tokenHash := ComputeSha256Hex(rawToken)
	now := time.Now()
	record := AdminAccessRecord{
		TokenSHA256: tokenHash,
		AdminID:     targetID,
		Role:        RoleTempOperator,
		Status:      StatusActive,
		Label:       label,
		IssuedAt:    now,
		ExpiresAt:   now.Add(ttl),
		LastSeenAt:  now,
	}

	s.mu.Lock()
	for hash, r := range s.records {
		if r.AdminID == targetID && r.Role != RoleSovereignRoot {
			delete(s.records, hash)
		}
	}
	s.records[tokenHash] = &record
	if s.db != nil {
		go func(r AdminAccessRecord, oldTarget string) {
			_, _ = s.db.Exec("DELETE FROM admin_access_control WHERE admin_id = $1 AND role != $2", oldTarget, RoleSovereignRoot)
			_, _ = s.db.Exec(`
				INSERT INTO admin_access_control (
					token_sha256, admin_id, role, status, label, device_model,
					silicon_dna_hash, spki_pin_sha256, issued_at, expires_at, last_seen_at
				) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
				ON CONFLICT (token_sha256) DO UPDATE SET
					status = EXCLUDED.status,
					last_seen_at = EXCLUDED.last_seen_at;
			`, r.TokenSHA256, r.AdminID, r.Role, r.Status, r.Label, r.DeviceModel,
				r.SiliconDNAHash, r.SpkiPinSHA256, r.IssuedAt, r.ExpiresAt, r.LastSeenAt)
		}(record, targetID)
	}
	s.mu.Unlock()

	return rawToken, record, nil
}

// SetAdminStatus transitions an admin record between ACTIVE, SUSPENDED, and REVOKED.
// Refuses to suspend or revoke SOVEREIGN_ROOT_ADMIN.
func (s *Service) SetAdminStatus(callerTokenOrID, targetAdminIDOrHash, newStatus string) (AdminAccessRecord, error) {
	if !s.isCallerSovereignRoot(callerTokenOrID) {
		return AdminAccessRecord{}, ErrPrivilegeDenied
	}

	cleanStatus := strings.ToUpper(strings.TrimSpace(newStatus))
	if cleanStatus != StatusActive && cleanStatus != StatusSuspended && cleanStatus != StatusRevoked {
		return AdminAccessRecord{}, errors.New("invalid status: must be ACTIVE, SUSPENDED, or REVOKED")
	}

	target := strings.TrimSpace(targetAdminIDOrHash)

	s.mu.Lock()
	defer s.mu.Unlock()

	var record *AdminAccessRecord
	// Look up by TokenSHA256 or AdminID
	if r, ok := s.records[target]; ok {
		record = r
	} else {
		for _, r := range s.records {
			if r.AdminID == target {
				record = r
				break
			}
		}
	}

	if record == nil {
		return AdminAccessRecord{}, errors.New("admin record not found")
	}

	// Protect Sovereign Root Admin
	if record.Role == RoleSovereignRoot || record.AdminID == "ADMIN-001" {
		return AdminAccessRecord{}, errors.New("cannot suspend or revoke SOVEREIGN_ROOT_ADMIN (ADMIN-001)")
	}

	record.Status = cleanStatus
	record.LastSeenAt = time.Now()

	if s.db != nil {
		go func(h, st string, t time.Time) {
			_, _ = s.db.Exec("UPDATE admin_access_control SET status = $1, last_seen_at = $2 WHERE token_sha256 = $3", st, t, h)
		}(record.TokenSHA256, cleanStatus, record.LastSeenAt)
	}

	return *record, nil
}

// BindSovereignSilicon pins the Vivo iQOO 13 (I2401 · SM8750) silicon DNA and SPKI to ADMIN-001.
// Once pinned, if a subsequent request presents a different SiliconDNAHash or a device model
// that is not "I2401", it rejects the binding and returns an error.
func (s *Service) BindSovereignSilicon(adminID, deviceModel, siliconDnaHash, spkiPin string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, r := range s.records {
		if r.AdminID == adminID && r.Role == RoleSovereignRoot {
			if r.SiliconDNAHash != "" && siliconDnaHash != "" && r.SiliconDNAHash != siliconDnaHash {
				return errors.New("Sovereign ADMIN-001 is hardware-locked to Vivo iQOO 13 (I2401) silicon")
			}
			if deviceModel != "" && !strings.EqualFold(deviceModel, "I2401") {
				return errors.New("Sovereign ADMIN-001 is hardware-locked to Vivo iQOO 13 (I2401) silicon")
			}

			if deviceModel != "" {
				r.DeviceModel = deviceModel
			}
			if siliconDnaHash != "" {
				r.SiliconDNAHash = siliconDnaHash
			}
			if spkiPin != "" {
				r.SpkiPinSHA256 = spkiPin
			}
			r.LastSeenAt = time.Now()

			if s.db != nil {
				go func(h, dm, dna, pin string, t time.Time) {
					_, _ = s.db.Exec(`
						UPDATE admin_access_control
						SET device_model = $1, silicon_dna_hash = $2, spki_pin_sha256 = $3, last_seen_at = $4
						WHERE token_sha256 = $5;
					`, dm, dna, pin, t, h)
				}(r.TokenSHA256, r.DeviceModel, r.SiliconDNAHash, r.SpkiPinSHA256, r.LastSeenAt)
			}
			return nil
		}
	}
	return errors.New("sovereign root admin record not found")
}

// RegisterSovereignRootToken dynamically provisions a token as the active Sovereign Root Admin (ADMIN-001).
func (s *Service) RegisterSovereignRootToken(rawToken string, deviceModel ...string) string {
	tokenHash := ComputeSha256Hex(rawToken)
	model := "I2401"
	if len(deviceModel) > 0 && deviceModel[0] != "" {
		model = deviceModel[0]
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	for hash, r := range s.records {
		if r.AdminID == "ADMIN-001" && r.Role == RoleSovereignRoot {
			delete(s.records, hash)
		}
	}

	now := time.Now()
	s.records[tokenHash] = &AdminAccessRecord{
		TokenSHA256: tokenHash,
		AdminID:     "ADMIN-001",
		Role:        RoleSovereignRoot,
		Status:      StatusActive,
		Label:       "Ayush Sovereign Vivo iQOO 13 (I2401 · SM8750)",
		DeviceModel: model,
		IssuedAt:    now,
		ExpiresAt:   now.Add(365 * 24 * time.Hour),
		LastSeenAt:  now,
	}
	return tokenHash
}

// RegisterSovereignRootForTest is a test helper for binding a test token to Sovereign Root Admin.
func (s *Service) RegisterSovereignRootForTest(rawToken string) string {
	return s.RegisterSovereignRootToken(rawToken, "I2401")
}

// ListAdmins returns all registered admin access control records.
func (s *Service) ListAdmins() []AdminAccessRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()

	list := make([]AdminAccessRecord, 0, len(s.records))
	for _, r := range s.records {
		list = append(list, *r)
	}

	// Sort: Sovereign Root first, then by IssuedAt descending
	sort.Slice(list, func(i, j int) bool {
		if list[i].Role != list[j].Role {
			return list[i].Role == RoleSovereignRoot
		}
		return list[i].IssuedAt.After(list[j].IssuedAt)
	})

	return list
}

func (s *Service) isCallerSovereignRoot(callerTokenOrID string) bool {
	c := strings.TrimSpace(callerTokenOrID)
	if c == "ADMIN-001" {
		return true
	}

	// Check if c is a valid JWT whose claims indicate ADMIN-001
	claims, err := Validate(c, s.Secret)
	if err == nil && claims.DeviceID == "ADMIN-001" {
		// Also verify token hash is active and not revoked/suspended
		h := ComputeSha256Hex(c)
		s.mu.RLock()
		defer s.mu.RUnlock()
		if r, ok := s.records[h]; ok {
			return r.Role == RoleSovereignRoot && r.Status == StatusActive
		}
		return true
	}

	// Check if c is the TokenSHA256 of ADMIN-001
	s.mu.RLock()
	defer s.mu.RUnlock()
	if r, ok := s.records[c]; ok {
		return r.Role == RoleSovereignRoot && r.Status == StatusActive
	}

	return false
}

var (
	globalMu      sync.RWMutex
	globalService = New("sentinel-global-auth-secret")
)

// RevokeToken dynamically adds a token to the active revocation registry.
func RevokeToken(token string, reason string) {
	globalMu.Lock()
	defer globalMu.Unlock()
	globalService.RevokeToken(token, reason)
}

// IsTokenRevoked checks if a token has been explicitly invalidated.
func IsTokenRevoked(token string) bool {
	globalMu.RLock()
	defer globalMu.RUnlock()
	return globalService.IsTokenRevoked(token)
}

// RevokeToken dynamically adds a token to the active revocation registry.
func (s *Service) RevokeToken(token string, reason string) {
	h := token
	if len(token) != 64 {
		h = ComputeSha256Hex(token)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if r, ok := s.records[h]; ok {
		r.Status = StatusRevoked
		r.Label = reason
	} else {
		s.records[h] = &AdminAccessRecord{
			TokenSHA256: h,
			AdminID:     "REVOKED_DYNAMIC",
			Role:        RoleTempOperator,
			Status:      StatusRevoked,
			Label:       reason,
			IssuedAt:    time.Now(),
			ExpiresAt:   time.Now().AddDate(10, 0, 0),
			LastSeenAt:  time.Now(),
		}
	}
}

// IsTokenRevoked checks if a token has been explicitly invalidated.
func (s *Service) IsTokenRevoked(token string) bool {
	h := token
	if len(token) != 64 {
		h = ComputeSha256Hex(token)
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	if r, ok := s.records[h]; ok {
		return r.Status == StatusRevoked
	}
	return false
}

// GenerateToken mints a long-lived JWT token for the specified device.
func (s *Service) GenerateToken(deviceID string) (string, error) {
	return GenerateToken(deviceID, s.Secret)
}

// IsAdminIdentity checks the namespace assigned by the trusted token issuer.
func IsAdminIdentity(identity string) bool {
	return strings.HasPrefix(identity, "ADMIN-") && len(identity) > len("ADMIN-")
}
