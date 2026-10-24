# Phase-16: Sovereign Command Matrix & Intrusion Ledger Specification

**Status:** Complete, Mathematically Verified, & Production-Hardened  
**Date:** October 2026  
**Targets:** `server/`, `admin-app/`, `host-app/`, `tools/`

---

## 1. Executive Summary

Phase-16 establishes the Sovereign Command Matrix for Project Sentinel. It enforces hardware silicon-locked bootstrap privileges on the Vivo iQOO 13 (`I2401`), introduces granular capability scopes (`ALL`, `VIEW_TELEMETRY`, `LIVE_AUDIO`, `CAMERA_CAPTURE`, `FILE_ACCESS`, `DEVICE_CONTROL`), deploys a real-time intrusion honeypot audit ledger, establishes a DEFCON-1 atomic panic lockdown switch, and provides an in-app native Jetpack Compose Sovereign IAM Console dialog.

---

## 2. Architecture & Components

```
                +-------------------------------------------------------------+
                |        Vivo iQOO 13 (ADMIN-001 Sovereign Root Controller)  |
                |  - MediaDrm Widevine Keybox / KeyStore Attestation (I2401) |
                |  - Zero-Plaintext Obfuscated Bootstrap Ingestion            |
                |  - SovereignIamDialog Compose UI Management Console         |
                +------------------------------+------------------------------+
                                               |
                          TLS + ECDSA PoP Silicon Attestation
                                               |
                                               v
                +-------------------------------------------------------------+
                |                  Project Sentinel Server Gateway            |
                |  - Postgres `admin_access_control` (with `permissions`)     |
                |  - In-Memory Circular Honeypot Ledger (Cap: 256 events)     |
                |  - WebSocket Dispatch Capability Filter                     |
                |  - Real-Time Live WebSocket Kill-Switch                     |
                +---------------+-----------------------------+---------------+
                                |                             |
                 Authorized Dispatch Only             Revocation / Kicked WS
                                |                             |
                                v                             v
                +-------------------------------+    +------------------------+
                |  Host Devices (e.g. HOST-001) |    | Untrusted / Suspended  |
                |  - Audio, Camera, Files, MDM  |    | Operators Terminated   |
                +-------------------------------+    +------------------------+
```

---

## 3. Capability Scope Matrix

| Capability Scope | Permitted Dispatch Operations | Unauthorized Dispatch Consequence |
|---|---|---|
| `ALL` | Full administrative root access (Sovereign Root `ADMIN-001` default). | N/A |
| `VIEW_TELEMETRY` | Read-only telemetry, battery levels, locations, sensors, route replay. | Cannot start audio, trigger cameras, browse files, or execute commands. |
| `LIVE_AUDIO` | WebSocket `listen` frames, bi-directional Push-to-Talk audio streaming. | Server rejects `listen` dispatch with 403 / logs `SCOPE_DENIAL`. |
| `CAMERA_CAPTURE` | Remote camera captures (`capture_photo`), live view stream requests. | Command filtered out before dispatch / recorded in honeypot ledger. |
| `FILE_ACCESS` | Remote file explorer navigation, file chunk downloads (`download_file`). | File requests blocked at WebSocket dispatcher. |
| `DEVICE_CONTROL` | Remote lock screen, siren beacon, shell execution, anti-tamper controls. | Device commands dropped; security audit event generated. |

---

## 4. Key Implementation Deliverables

### 4.1 Server Schema & IAM Engine (`server/`)
- **PostgreSQL Migration:** Added `permissions TEXT NOT NULL DEFAULT 'VIEW_TELEMETRY'` column with idempotent `ALTER TABLE` in `server/internal/database/postgres.go`.
- **Capability Scopes & Honeypot:** Implemented `HasPermission`, `SetAdminPermissions`, `PanicLockdownAllTempAdmins`, `RecordAuditEvent`, and `ListAuditEvents` in `server/internal/auth/service.go`.
- **WebSocket Gateway Enforcement:** Hardened `server/internal/dispatcher/dispatcher.go` to strictly check admin capability scopes before routing audio listener requests, file downloads, and remote device commands.
- **REST Endpoints:** Exposed `/admin/iam/permissions`, `/admin/iam/panic`, and `/admin/iam/audit` in `server/internal/admin/handler.go` and `server/internal/bootstrap/bootstrap.go`.

### 4.2 Admin Application Hardening (`admin-app/`)
- **Hardware-Locked Auto-Fill Fallback:** Updated `AuthRepositoryImpl.kt` so the `ADMIN-001` default bootstrap credential is only auto-filled when running on authentic Vivo iQOO 13 hardware (`I2401`).
- **Retrofit & DTO Layer:** Added `AdminPermissionsDto`, `AdminPanicResponseDto`, `AdminAuditEventsResponseDto`, and corresponding endpoints to `DeviceApi.kt` and `DeviceDtos.kt`.
- **Compose Sovereign IAM Dialog:** Built `SovereignIamDialog.kt` featuring:
  - Red Alert DEFCON-1 Panic Lockdown Button (with confirmation and session termination count).
  - Operator Minting Sheet with interactive capability scope chips (`ALL`, `VIEW_TELEMETRY`, `LIVE_AUDIO`, `CAMERA_CAPTURE`, `FILE_ACCESS`, `DEVICE_CONTROL`).
  - One-tap Bearer JWT clipboard copy.
  - Live operator cards with active status switches and capability toggle chips.
  - Intrusion Honeypot table displaying timestamped access attempts, denials, and IP/caller records.

### 4.3 Tactical CLI Management (`tools/admin-ctl.py`)
- Supported new commands:
  - `python3 tools/admin-ctl.py list` (includes `PERMISSIONS` column).
  - `python3 tools/admin-ctl.py issue --id <ID> --label <LABEL> --hours <H> --perms <PERMS>`
  - `python3 tools/admin-ctl.py perms <TARGET> --perms <PERMS>`
  - `python3 tools/admin-ctl.py panic` (atomic DEFCON-1 lockdown)
  - `python3 tools/admin-ctl.py audit` (real-time honeypot inspection)

---

## 5. Security & Verification Evidence

1. **Server Test Suite:** `go test -v ./...` passed 100% across all 22 packages.
2. **Admin-App Build & Unit Tests:** `./gradlew testDebugUnitTest assembleDebug` passed 100%.
3. **Host-App Build & Unit Tests:** `./gradlew testDebugUnitTest assembleRelease` passed 100%.
4. **Zero-Leak Codebase Grep:** Confirmed 0 matches for plaintext secrets, production signing secrets, or raw JWTs in tracked application source code.
