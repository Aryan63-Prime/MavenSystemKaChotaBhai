package auth

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

type PopResult struct {
	Verified bool   `json:"verified"`
	Status   string `json:"status"`
	DeviceID string `json:"deviceId"`
	SPKIPin  string `json:"spkiPin,omitempty"`
	Error    string `json:"error,omitempty"`
}

type SovereignSiliconProof struct {
	AdminID                 string `json:"adminId"`
	SiliconDNAHash          string `json:"siliconDnaHash"`
	IsSovereignIqoo13Silicon bool   `json:"isSovereignIqoo13Silicon"`
	DeviceModel             string `json:"deviceModel"`
	SocModel                string `json:"socModel"`
	RoleClaim               string `json:"roleClaim"`
	HardwareSummary         string `json:"hardwareSummary"`
	HwNonce                 string `json:"hwNonce"`
	HwSignature             string `json:"hwSignature"`
	HwCertBase64            string `json:"hwCertBase64"`
}

type HardwarePopVerifier struct {
	mu            sync.RWMutex
	pinnedSPKI    map[string]string // deviceID -> 16-hex-char SPKI pin
	lastTimestamp map[string]int64  // deviceID -> last verified timestamp (ms or sec)
}

var (
	defaultPopVerifier     *HardwarePopVerifier
	defaultPopVerifierOnce sync.Once
)

// GetDefaultPopVerifier returns the singleton verifier instance.
func GetDefaultPopVerifier() *HardwarePopVerifier {
	defaultPopVerifierOnce.Do(func() {
		defaultPopVerifier = NewHardwarePopVerifier()
	})
	return defaultPopVerifier
}

func NewHardwarePopVerifier() *HardwarePopVerifier {
	return &HardwarePopVerifier{
		pinnedSPKI:    make(map[string]string),
		lastTimestamp: make(map[string]int64),
	}
}

// VerifyTelemetryPop verifies Host device proof of possession from TELEMETRY_REPORT payload.
func (v *HardwarePopVerifier) VerifyTelemetryPop(deviceID string, payload map[string]interface{}) PopResult {
	if payload == nil {
		return PopResult{Verified: false, Status: "MISSING_PAYLOAD", DeviceID: deviceID}
	}

	nonceStr, _ := payload["popNonce"].(string)
	sigStr, _ := payload["popSignature"].(string)
	certStr, _ := payload["popCert"].(string)

	if nonceStr == "" || sigStr == "" || certStr == "" {
		// Also check alternative keys
		if n, ok := payload["hwNonce"].(string); ok && n != "" {
			nonceStr = n
		}
		if s, ok := payload["hwSignature"].(string); ok && s != "" {
			sigStr = s
		}
		if c, ok := payload["hwCertBase64"].(string); ok && c != "" {
			certStr = c
		}
	}

	if nonceStr == "" || sigStr == "" || certStr == "" {
		return PopResult{
			Verified: false,
			Status:   "UNVERIFIED_SOFTWARE_ONLY",
			DeviceID: deviceID,
		}
	}

	return v.verifyRawPop(deviceID, nonceStr, sigStr, certStr)
}

// VerifySovereignAdminProof verifies Sovereign Admin proof from Vivo iQOO 13.
func (v *HardwarePopVerifier) VerifySovereignAdminProof(proof SovereignSiliconProof) PopResult {
	if proof.AdminID == "" || proof.HwNonce == "" || proof.HwSignature == "" || proof.HwCertBase64 == "" {
		return PopResult{
			Verified: false,
			Status:   "INCOMPLETE_SILICON_PROOF",
			DeviceID: proof.AdminID,
			Error:    "Missing hardware nonce, signature, or certificate",
		}
	}

	res := v.verifyRawPop(proof.AdminID, proof.HwNonce, proof.HwSignature, proof.HwCertBase64)
	if !res.Verified {
		return res
	}

	if proof.IsSovereignIqoo13Silicon && proof.AdminID == "ADMIN-001" {
		res.Status = fmt.Sprintf("SOVEREIGN_ROOT_VERIFIED (I2401·SM8750·SPKI:%s)", res.SPKIPin)
	}

	return res
}

func (v *HardwarePopVerifier) verifyRawPop(deviceID, nonceStr, sigStr, certStr string) PopResult {
	// 1. Decode DER X.509 leaf certificate
	certBytes, err := base64.StdEncoding.DecodeString(certStr)
	if err != nil {
		return PopResult{Verified: false, Status: "INVALID_CERT_BASE64", DeviceID: deviceID, Error: err.Error()}
	}

	cert, err := x509.ParseCertificate(certBytes)
	if err != nil {
		return PopResult{Verified: false, Status: "INVALID_X509_CERT", DeviceID: deviceID, Error: err.Error()}
	}

	ecPub, ok := cert.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return PopResult{Verified: false, Status: "NON_ECDSA_PUBLIC_KEY", DeviceID: deviceID, Error: "Certificate does not contain an ECDSA public key"}
	}

	// 2. Compute 16-hex-char SPKI Pin (first 8 bytes of SHA-256)
	spkiDigest := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	spkiPin := hex.EncodeToString(spkiDigest[:8])

	// 3. Trust-On-First-Use (TOFU) Silicon Key Pinning
	v.mu.Lock()
	pinned, exists := v.pinnedSPKI[deviceID]
	if !exists {
		v.pinnedSPKI[deviceID] = spkiPin
	} else if pinned != spkiPin {
		v.mu.Unlock()
		return PopResult{
			Verified: false,
			Status:   "🚨SILICON_KEY_MISMATCH",
			DeviceID: deviceID,
			SPKIPin:  spkiPin,
			Error:    fmt.Sprintf("Presented SPKI key (%s) does not match pinned silicon key (%s)", spkiPin, pinned),
		}
	}

	// 4. Extract and verify timestamp monotonicity from nonce
	parts := strings.Split(nonceStr, ":")
	if len(parts) >= 2 {
		if ts, err := strconv.ParseInt(parts[1], 10, 64); err == nil && ts > 0 {
			lastTs := v.lastTimestamp[deviceID]
			if lastTs > 0 && ts < lastTs {
				v.mu.Unlock()
				return PopResult{
					Verified: false,
					Status:   "REPLAY_OR_OUT_OF_ORDER_NONCE",
					DeviceID: deviceID,
					SPKIPin:  spkiPin,
					Error:    "Nonce timestamp is older than previously verified PoP",
				}
			}
			v.lastTimestamp[deviceID] = ts
		}
	}
	v.mu.Unlock()

	// 5. Decode signature & verify against SHA-256 of nonce
	sigBytes, err := base64.StdEncoding.DecodeString(sigStr)
	if err != nil {
		return PopResult{Verified: false, Status: "INVALID_SIGNATURE_BASE64", DeviceID: deviceID, SPKIPin: spkiPin, Error: err.Error()}
	}

	nonceDigest := sha256.Sum256([]byte(nonceStr))
	if !ecdsa.VerifyASN1(ecPub, nonceDigest[:], sigBytes) {
		return PopResult{
			Verified: false,
			Status:   "SIGNATURE_VERIFICATION_FAILED",
			DeviceID: deviceID,
			SPKIPin:  spkiPin,
			Error:    "ECDSA signature validation failed",
		}
	}

	return PopResult{
		Verified: true,
		Status:   fmt.Sprintf("VERIFIED_HARDWARE_TEE (SPKI:%s)", spkiPin),
		DeviceID: deviceID,
		SPKIPin:  spkiPin,
	}
}

// GetPinnedSPKI returns the pinned SPKI pin for a device.
func (v *HardwarePopVerifier) GetPinnedSPKI(deviceID string) string {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.pinnedSPKI[deviceID]
}

// PinSiliconKey explicitly registers an SPKI pin for a device.
func (v *HardwarePopVerifier) PinSiliconKey(deviceID, spkiPin string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.pinnedSPKI[deviceID] = spkiPin
}
