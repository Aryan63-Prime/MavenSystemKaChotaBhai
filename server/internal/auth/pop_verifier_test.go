package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"fmt"
	"math/big"
	"testing"
	"time"
)

func generateTestECKeyAndCert(t *testing.T) (*ecdsa.PrivateKey, string) {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate EC key: %v", err)
	}

	template := x509.Certificate{
		SerialNumber: big.NewInt(1337),
		Subject: pkix.Name{
			CommonName: "Sentinel Test Hardware Key",
		},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}

	certDer, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("failed to create certificate: %v", err)
	}

	return priv, base64.StdEncoding.EncodeToString(certDer)
}

func signNonce(t *testing.T, priv *ecdsa.PrivateKey, nonce string) string {
	t.Helper()
	digest := sha256.Sum256([]byte(nonce))
	sig, err := ecdsa.SignASN1(rand.Reader, priv, digest[:])
	if err != nil {
		t.Fatalf("failed to sign nonce: %v", err)
	}
	return base64.StdEncoding.EncodeToString(sig)
}

func TestVerifyTelemetryPopSuccess(t *testing.T) {
	verifier := NewHardwarePopVerifier()
	priv, certB64 := generateTestECKeyAndCert(t)

	ts := time.Now().UnixMilli()
	nonce := fmt.Sprintf("HOST-001-VIVO:%d:0:A1B2C3D4E5F67890", ts)
	sigB64 := signNonce(t, priv, nonce)

	payload := map[string]interface{}{
		"popNonce":     nonce,
		"popSignature": sigB64,
		"popCert":      certB64,
	}

	res := verifier.VerifyTelemetryPop("HOST-001-VIVO", payload)
	if !res.Verified {
		t.Fatalf("expected verification success, got error: %s (status: %s)", res.Error, res.Status)
	}
	if res.SPKIPin == "" {
		t.Fatalf("expected non-empty SPKI pin")
	}
}

func TestVerifyTelemetryPopMissingProof(t *testing.T) {
	verifier := NewHardwarePopVerifier()
	payload := map[string]interface{}{}

	res := verifier.VerifyTelemetryPop("HOST-001-VIVO", payload)
	if res.Verified {
		t.Fatalf("expected unverified for missing proof")
	}
	if res.Status != "UNVERIFIED_SOFTWARE_ONLY" {
		t.Fatalf("expected UNVERIFIED_SOFTWARE_ONLY, got %s", res.Status)
	}
}

func TestVerifyTelemetryPopSignatureFailure(t *testing.T) {
	verifier := NewHardwarePopVerifier()
	priv, certB64 := generateTestECKeyAndCert(t)

	ts := time.Now().UnixMilli()
	nonce := fmt.Sprintf("HOST-001-VIVO:%d:0:A1B2", ts)
	sigB64 := signNonce(t, priv, nonce)

	// Tamper with the nonce
	tamperedNonce := fmt.Sprintf("HOST-001-VIVO:%d:1:A1B2", ts)

	payload := map[string]interface{}{
		"popNonce":     tamperedNonce,
		"popSignature": sigB64,
		"popCert":      certB64,
	}

	res := verifier.VerifyTelemetryPop("HOST-001-VIVO", payload)
	if res.Verified {
		t.Fatalf("expected signature verification failure for tampered nonce")
	}
	if res.Status != "SIGNATURE_VERIFICATION_FAILED" {
		t.Fatalf("expected SIGNATURE_VERIFICATION_FAILED, got %s", res.Status)
	}
}

func TestVerifyTelemetryPopSiliconKeyMismatch(t *testing.T) {
	verifier := NewHardwarePopVerifier()
	priv1, certB64_1 := generateTestECKeyAndCert(t)
	priv2, certB64_2 := generateTestECKeyAndCert(t)

	ts1 := time.Now().UnixMilli()
	nonce1 := fmt.Sprintf("HOST-001-VIVO:%d:0:CERT1", ts1)
	sig1 := signNonce(t, priv1, nonce1)

	// First connection pins key 1
	res1 := verifier.VerifyTelemetryPop("HOST-001-VIVO", map[string]interface{}{
		"popNonce":     nonce1,
		"popSignature": sig1,
		"popCert":      certB64_1,
	})
	if !res1.Verified {
		t.Fatalf("first verification should succeed: %s", res1.Error)
	}

	// Second connection with different keypair should trigger mismatch!
	ts2 := ts1 + 1000
	nonce2 := fmt.Sprintf("HOST-001-VIVO:%d:0:CERT2", ts2)
	sig2 := signNonce(t, priv2, nonce2)

	res2 := verifier.VerifyTelemetryPop("HOST-001-VIVO", map[string]interface{}{
		"popNonce":     nonce2,
		"popSignature": sig2,
		"popCert":      certB64_2,
	})
	if res2.Verified {
		t.Fatalf("expected verification failure due to silicon key mismatch")
	}
	if res2.Status != "🚨SILICON_KEY_MISMATCH" {
		t.Fatalf("expected 🚨SILICON_KEY_MISMATCH, got %s", res2.Status)
	}
}

func TestVerifySovereignAdminProof(t *testing.T) {
	verifier := NewHardwarePopVerifier()
	priv, certB64 := generateTestECKeyAndCert(t)

	ts := time.Now().Unix()
	dna := "5FADA681370BEE82A8FF9A99B984AFAD4BD70C0B5745EDDB0E0F52AFD464BDBF"
	nonce := fmt.Sprintf("ADMIN-001:%s:I2401:SM8750:%d", dna[:32], ts)
	sigB64 := signNonce(t, priv, nonce)

	proof := SovereignSiliconProof{
		AdminID:                 "ADMIN-001",
		SiliconDNAHash:          dna,
		IsSovereignIqoo13Silicon: true,
		DeviceModel:             "I2401",
		SocModel:                "SM8750",
		RoleClaim:               "SOVEREIGN_ROOT_ADMIN",
		HardwareSummary:         "Vivo iQOO 13 I2401 (SM8750)",
		HwNonce:                 nonce,
		HwSignature:             sigB64,
		HwCertBase64:            certB64,
	}

	res := verifier.VerifySovereignAdminProof(proof)
	if !res.Verified {
		t.Fatalf("expected sovereign admin proof verification to succeed: %s", res.Error)
	}
	if res.Status == "" || res.SPKIPin == "" {
		t.Fatalf("expected valid status and SPKI pin")
	}
}
