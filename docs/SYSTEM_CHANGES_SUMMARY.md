# Project Sentinel: Complete Audit, Fixes, and Architecture Summary

**Date:** 2026-10-09  
**Workspace:** `/Users/ayush/Desktop/Servillance`  
**Components:** `admin-app` (Android/Kotlin), `host-app` (Android/Kotlin), `server` (Go)

---

## 1. Executive Summary

This document consolidates all root causes identified, architectural hardening implemented, and code modifications across the Project Sentinel codebase. The investigations addressed multi-device telemetry collisions, audio streaming buffer issues, WebSocket session routing, authentication boundaries, and foreground service notification transparency.

---

## 2. Observations & Root Cause Analysis

### 2.1 Multi-Device Identity & Fleet Collision
* **Issue:** When multiple host devices connected using default configurations or shared base tokens (`HOST-001`), the server and Admin repository conflated device records. Telemetry from one device would overwrite state (location, battery, online status) for another device of the same model.
* **Root Cause:**
  - Fallback logic in `DeviceRepositoryImpl.kt` matched devices by broad criteria (model name, "any online host") when specific hardware IDs differed.
  - The Go backend permitted sessions to switch identities post-connection or conflated authenticated identities (`AuthenticatedDeviceID`) with registered hardware IDs (`DeviceID`).
  - Cache entries in the Admin app retained stale keys across restarts.

### 2.2 Audio Streaming & JitterBuffer Sequence Poisoning
* **Issue:** Admin app would occasionally display dropped frames or experience playback silence when starting or restarting an audio stream.
* **Root Cause:**
  - The RTP/packet sequence numbers start fresh upon each new capture session. If an admin stops and restarts listening quickly, late-arriving packets from the prior session could set the `playbackHead` to an older, high sequence value. When the new stream arrived with sequence `0, 1, ...`, the buffer calculated a negative distance exceeding the late threshold and dropped all subsequent packets until the sequence caught up.
  - On the backend, listener registration did not account for hardware alias variations (`HOST-001-VIVO-2D2C` vs `HOST-VIVO-2D2C` vs `HOST-001`), causing routing drops when callsigns differed in formatting.

### 2.3 Credential Hardening & Session Isolation
* **Issue:** Host and Admin sessions lacked strict role boundaries at the WebSocket gateway level. Hardcoded tokens were previously present in client source files, and server configuration lacked strict secret entropy validation.
* **Root Cause:**
  - The gateway allowed connections to register administrative capabilities regardless of namespace verification.
  - JWT secret configuration allowed fallback to weak or default keys.

### 2.4 Notification Transparency
* **Issue:** When audio capture initiated, notification disclosures were inconsistent.
* **Root Cause:**
  - Foreground service notification logic did not persistently display the active microphone status when other state updates arrived.

---

## 3. Detailed File and Code Modifications

### 3.1 Android Admin App (`admin-app`)

#### `admin-app/data/src/main/java/com/sentinel/admin/data/audio/JitterBuffer.kt`
* **Change:** Monotonic unwrapped sequence tracking, bounded PLC gap recovery, and elimination of the restart heuristic hack.
* **Details:**
  - Tracks unwrapped 64-bit monotonic sequences (`highestUnwrapped`) to prevent `TreeMap` uint32 rollover comparison contract bugs.
  - When gaps exceed `maxPlcGap` (10 frames = 200ms), fast-forwards `playbackHead = firstKey` and immediately delivers the live frame rather than emitting hundreds of missing/PLC frames that would lag live audio.
  - Dropped late packets strictly without rewinding the playback reference.
  - Added unit test regressions: `large gap exceeds maxPlcGap advances playback head without excessive missing frames`, `stale packet dropped without rewinding playback head`, and `sequence wrap-around across uint32 boundary delivered in correct order`.

#### `admin-app/data/src/main/java/com/sentinel/admin/data/repository/DeviceRepositoryImpl.kt`
* **Change:** Strict exact-identity matching and removal of ambiguous model-based fallbacks.
* **Details:**
  - Replaced broad candidate comparators and heuristic fallbacks with exact device ID matching (`exact = candidates.filter { it.deviceId == deviceId && deviceId != "HOST-001" }`).
  - If multiple devices match ambiguous criteria, telemetry updates are discarded rather than arbitrarily assigned.
  - Preserved permanent hardware IDs across telemetry updates and cleaned up superseded cached entries upon update.

#### `admin-app/data/src/test/java/com/sentinel/admin/data/repository/DeviceRepositoryImplTest.kt` & `DeviceRepositoryImplLiveUpdateTest.kt`
* **Change:** Updated unit test suites to validate strict isolation between multiple devices of the same model and verify that stale entries do not corrupt active devices.

---

### 3.2 Android Host App (`host-app`)

#### `host-app/data/src/main/java/com/sentinel/host/data/audio/AudioPipeline.kt`
* **Change:** Positive worker completion prerequisite, join failure isolation, and permanent restart prevention on incomplete teardown.
* **Details:**
  - Positive completion prerequisite: `workerConfirmedTerminated` is initialized to `false` when a capture job exists, and is set to `true` strictly if `withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { jobToWait.join(); true } == true`.
  - On join timeout OR exception during joining: `!workerConfirmedTerminated` triggers `isCleanupFailed = true`, preserves the unfinished session without closing native Opus encoder, skips `session.stop()`, and throws an explicit `IllegalStateException`.
  - In `start()`, checks `if (isCleanupFailed || captureJob?.isActive == true) return false`, permanently blocking restart after any unconfirmed teardown.
  - Added unit test regressions:
    - `stop handles join timeout by preserving session and blocking restart without destroying encoder`: Verifies uncooperative recorder unblock timeout preserves encoder and blocks restart.
    - `stop preserves session and blocks restart when join is interrupted or throws exception`: Verifies that join interruption/exception preserves encoder and blocks subsequent restart.

#### `host-app/service/src/main/java/com/sentinel/host/service/AudioStreamer.kt`
* **Change:** Monotonic clock injection, barrier synchronization hooks, uniform interruption handling, and unchanged absolute deadline recomputation across recovery paths.
* **Details:**
  - Injected monotonic clock: `clock: () -> Long = { SystemClock.elapsedRealtime() }` in constructor; all deadline calculations read `clock()`.
  - Added test barrier hooks: `internal var onWatchdogAboutToAcquireLock: (() -> Unit)? = null` called before lock acquisition, and `internal var onWatchdogFinished: (() -> Unit)? = null` called immediately upon exiting the watchdog synchronized block.
  - Uniform interruption handling: All watchdogs (`start`, `resume`, and `handleAudioFocusGain`) check `if ((isStreaming || isInterruptedByCall) && sessionGeneration == expectedGen)` and reset `isInterruptedByCall = false` upon expiration, ensuring call interruptions never allow sessions to run indefinitely past deadline.
  - Recovery deadline preservation: Recomputes remaining duration (`sessionDeadlineRealtimeMs - clock()`) from the unchanged absolute deadline *after* cleanup and capture startup, immediately before scheduling the watchdog in both `resume()` and `handleAudioFocusGain()`. Any time spent during hardware re-initialization or HAL startup does not extend the session deadline.

#### `host-app/service/src/test/java/com/sentinel/host/service/AudioStreamerTest.kt`
* **Change:** Genuine cross-thread synchronization with completion barrier, exact monotonic deadline assertions without `advanceUntilIdle()`, and restart-time consumption regressions.
* **Details:**
  - Multi-threaded race test with completion barrier: `already-fired watchdog waiting for lifecycle lock does not stop new session` executes watchdog on a dedicated background single-thread executor (`Executors.newSingleThreadScheduledExecutor().asCoroutineDispatcher()`) and confirms completion using `watchdogFinishedLatch.await(5, TimeUnit.SECONDS)` instead of `Thread.sleep(150)`.
  - Aligned test clock with monotonic deadline: Injected `clock = { testScheduler.currentTime }` across virtual-time tests.
  - Boundary assertions without `advanceUntilIdle()`: Tests advance to `999ms` (assert running), advance by `1ms` to `1000ms` (assert expired) using `testScheduler.runCurrent()`.
  - Added `reconnect watchdog auto-stops session if deadline expires while interrupted by audio focus loss`: Verifies that reconnect watchdog auto-terminates session even when paused by audio focus loss.
  - Added `reconnection restart work consuming time does not extend original monotonic deadline`: Simulates capture restart consuming 400ms during reconnect and asserts expiration occurs at exactly the original 2000ms deadline.
  - Added `audio focus recovery restart work consuming time does not extend original monotonic deadline`: Simulates hardware re-init consuming 350ms during focus gain and asserts expiration occurs at exactly the original 2000ms deadline.

#### `host-app/app/src/main/java/com/sentinel/host/service/SentinelForegroundService.kt`
* **Change:** Removed hardcoded JWT constant, enforced session storage for credentials, and standardized user disclosure notifications.
* **Details:**
  - Removed embedded `JWT_TOKEN` string constant.
  - Updated auto-repair connection loop to read server URL and token dynamically from `sessionManager`. If unconfigured, the connection waits rather than authenticating with a shared static key.
  - Updated notification content:
    - Service title set to `"Sentinel Host"`.
    - Live microphone active notification set to `"Microphone active — live audio streaming"`.
    - Maintained microphone disclosure text even when concurrent connection status updates arrive.

---

### 3.3 Go Backend (`server`)

#### `server/internal/audio/service.go` & `server/internal/audio/handler.go`
* **Change:** Enforced connection-level audio ownership, administrative authorization checks, and exact routing.
* **Details:**
  - `StopListening` now requires `adminConnectionID` and rejects unauthorized teardowns where another connection attempts to stop a session it does not own.
  - `HandleListen` and `HandleStop` enforce `session.IsAdmin()`, rejecting requests from non-administrative connections with 403 Forbidden.
  - Added unit test regressions: `TestStopListening_UnauthorizedConnection`, `TestHandleListen_NonAdminRejected`, and `TestHandleStop_NonAdminRejected`.

#### `server/internal/gateway/gateway.go`, `session.go`, `manager.go`, `writer.go`
* **Change:** Enforced strict identity segregation and WebSocket resource bounds.
* **Details:**
  - Stored authenticated principal separately from device registration data.
  - Prohibited re-authentication on active connections.
  - Enforced administrator identity verification (`ADMIN-` namespace) for privileged requests.
  - Added inbound message limit (2 MiB) and write deadlines (5 seconds) to prevent socket starvation.

#### `server/internal/admin/handler.go` & `service.go`
* **Change:** Protected fleet REST endpoints and metrics with administrator authentication checks.
* **Details:**
  - Blocked unauthenticated or host-credentialed access to device inventories and operational metrics.

#### `server/internal/auth/jwt.go`, `service.go`, & `config/config.go`
* **Change:** Hardened cryptographic configuration and token requirements.
* **Details:**
  - Rejected JWT signing secrets shorter than 32 characters or default development values during server boot.
  - Mandated `iss` (`project-sentinel`) and `exp` claims on inbound tokens.

#### `tools/token-generator/main.go`
* **Change:** Hardened administrative token utility.
* **Details:**
  - Accepts signing secret via `JWT_SECRET` environment variable.
  - Removed console printing of the secret key.
  - Default token expiration set to 24 hours.

---

## 4. Verification and Test Results

1. **Go Backend Tests:**
   - Ran `go test -count=1 -race ./...` across all packages (`server/internal/admin`, `server/internal/audio`, `server/internal/auth`, `server/internal/config`, `server/internal/dispatcher`, `server/internal/gateway`, `server/internal/metrics`).
   - All tests passed with zero data races detected.
   - Verified new audio regression tests:
     - `TestStartListening_AtomicSingleClaimRace`: 50 concurrent admin connections racing to claim the same device. Exactly 1 succeeded, 49 received `"already has active listener"`.
     - `TestStopListening_AtomicReleaseRace`: 50 concurrent calls to release a listener. Exactly 1 succeeded, 49 received `"no active listener"`.
     - `TestHandleListen_NonAdminRejected`: Confirmed 403 Forbidden when non-admin calls LISTEN.
     - `TestHandleStop_NonAdminRejected`: Confirmed 403 Forbidden when non-admin calls STOP.
     - `TestStopListening_UnauthorizedConnection`: Confirmed rejection when another admin connection attempts to terminate an active listener it does not own.

2. **Android Admin Unit Tests:**
   - Executed `./gradlew testDebugUnitTest --rerun-tasks` across all modules (`:app`, `:data`, `:service`, `:domain`, `:shared`). All 85 tasks passed cleanly.
   - Verified regressions in `JitterBufferTest`:
     - `large gap exceeds maxPlcGap advances playback head without excessive missing frames`: Verified fast-forward without delay.
     - `stale packet dropped without rewinding playback head`: Verified monotonic sequence reference preservation.
     - `sequence wrap-around across uint32 boundary delivered in correct order`: Verified clean delivery across uint32 rollover.

3. **Host App Tests & Build:**
   - Executed `./gradlew testDebugUnitTest --rerun-tasks` across `host-app` (`:data:testDebugUnitTest` and `:service:testDebugUnitTest`). All 82 tasks passed cleanly.
   - Verified regressions in `AudioPipelineTest` (14/14 passed):
     - `stop unblocks stuck audio read and terminates cleanly without hang`: Guaranteed blocking read entry barrier before calling stop; verified immediate clean unblock and encoder destruction without fallback timeout.
     - `stop handles join timeout by preserving session and blocking restart without destroying encoder`: Verified that when join times out, cleanup throws `IllegalStateException`, encoder is preserved from SIGABRT native destruction, and restart is blocked.
     - `stop preserves session and blocks restart when join is interrupted or throws exception`: Verified that join interruption/exception preserves encoder and permanently blocks restart.
   - Verified regressions in `AudioStreamerTest` (25/25 passed):
     - `already-fired watchdog waiting for lifecycle lock does not stop new session`: Verified with genuine cross-thread background executor + barrier latch + completion barrier confirming stale watchdog termination.
     - `session timeout expires at original monotonic deadline without relying on advanceUntilIdle`: Verified exact boundary at 999ms (running) and 1000ms (expired) using `runCurrent()`.
     - `reconnect watchdog auto-stops session if deadline expires while interrupted by audio focus loss`: Verified uniform interruption handling across reconnect watchdogs.
     - `reconnection restart work consuming time does not extend original monotonic deadline`: Verified restart consuming 400ms during resume still expires at original 2000ms deadline.
     - `audio focus recovery restart work consuming time does not extend original monotonic deadline`: Verified startup consuming 350ms during focus recovery still expires at original 2000ms deadline.
     - `audio focus gain after session deadline expired does not restart capture`: Verified focus gain cannot bypass deadline or resurrect capture after expiration.
     - `audio focus gain before deadline restores capture and watchdog`: Verified focus gain before deadline restores capture and reinstalls a watchdog for the exact remaining duration without `advanceUntilIdle()`.
     - `start with zero duration enforces bounded watchdog`: Proved 0 or negative duration cannot bypass watchdog or disable timeout.
     - `resume after explicit stop is no-op`: Proved explicit `stop()` prevents subsequent `resume()` from resurrecting capture.
     - `resume without paused session is no-op`: Verified resume requires an active paused session within monotonic deadline.

---

## 5. Architectural Status & Remaining Work

### 5.1 Hardening Pass Status: CLOSED (Partial Hardening; Full Audio Fix Incomplete)
This specific lifecycle hardening pass is closed. The overarching audio fix remains **incomplete** pending wire protocol upgrades and deployed-device verification.

### 5.2 Completed Code Hardening:
1. **Unchanged Absolute Monotonic Deadline Preservation:** [`AudioStreamer.kt`](file:///Users/ayush/Desktop/Servillance/host-app/service/src/main/java/com/sentinel/host/service/AudioStreamer.kt) recomputes remaining duration from the unchanged absolute deadline *after* cleanup and capture startup, immediately before scheduling watchdogs in both `resume()` and `handleAudioFocusGain()`. Latency spent during hardware/HAL startup is deducted from the remaining delay.
2. **Watchdog Completion Barrier:** [`AudioStreamerTest.kt`](file:///Users/ayush/Desktop/Servillance/host-app/service/src/test/java/com/sentinel/host/service/AudioStreamerTest.kt) replaces `Thread.sleep` with a deterministic latch barrier (`onWatchdogFinished`) confirming the stale watchdog has fully completed its execution on the background thread.
3. **Positive Worker Completion Prerequisite:** [`AudioPipeline.kt`](file:///Users/ayush/Desktop/Servillance/host-app/data/src/main/java/com/sentinel/host/data/audio/AudioPipeline.kt) requires positive confirmation (`workerConfirmedTerminated == true`) before resource disposal. Timeout or exception preserves unfinished session, retains native encoder, and permanently blocks restart.
4. **Uniform Watchdog Interruption Handling:** [`AudioStreamer.kt`](file:///Users/ayush/Desktop/Servillance/host-app/service/src/main/java/com/sentinel/host/service/AudioStreamer.kt) checks `(isStreaming || isInterruptedByCall)` uniformly across `start`, `resume`, and `handleAudioFocusGain` watchdogs, clearing `isInterruptedByCall` and halting capture upon expiration.
5. **Genuine Cross-Thread Verification:** Multi-threaded test in [`AudioStreamerTest.kt`](file:///Users/ayush/Desktop/Servillance/host-app/service/src/test/java/com/sentinel/host/service/AudioStreamerTest.kt) verifies watchdog lifecycle lock racing on a real background executor using `CountDownLatch` barrier synchronization.
6. **Virtual Test Clock Alignment:** Injected `clock = { testScheduler.currentTime }` in tests to align deadline tracking with virtual time, asserting exact deadline expiration at millisecond boundaries without relying on `advanceUntilIdle()`.
7. **Fleet Isolation & Exact Routing:** Removed all `HOST-001` alias fallback tables. Server routes audio and location strictly by exact registered hardware ID.
8. **Atomic Audio Ownership:** `StartListening` and `StopListening` are synchronized via mutex in Go `audio.Service`. Prohibits race conditions where concurrent START requests both see no listener or concurrent STOP calls interleave unsafely.
9. **JitterBuffer Unwrapping & Bounded Gaps:** Prevents `TreeMap` sequence rollover contract violations and bounds PLC gap recovery to 10 frames to avoid lagging live audio.

### 5.3 Local Test Status:
- **`host-app:service`**: 25/25 unit tests passing, including restart-delay regressions and multi-threaded watchdog completion barrier.
- **`host-app:data`**: 14/14 unit tests passing, including blocking read unblock, join timeout failure, and join exception handling.
- **`server`**: Go race detector passing cleanly (`go test -count=1 -race ./internal/audio/...` and `go test -race ./...`).

### 5.4 Required Remaining Work & Deployed-Device Verification:
1. **UUID-Based Frame Isolation with Protocol Negotiation:**
   - Extend `AudioFrameBuilder`, `AudioFrameParser`, and server `frame.go` to carry a 16-byte stream UUID directly in 29-byte binary frame headers.
   - Negotiate `AUDIO_V2` capability during initial handshake; return explicit unsupported-version errors to incompatible clients rather than dropping or corrupting audio streams.
2. **Visible On-Device Interactive Approval Flow:**
   - Enforce visible, interactive user consent dialog prior to foreground service elevation and microphone hardware allocation.
3. **Multi-Device Deployed End-to-End Verification:**
   - Multi-device matrix testing on physical hardware (e.g. Vivo iQOO 13, Samsung, Pixel).
   - Validating host capture restart, WebSocket reconnection under cellular packet loss, rejection of stale session frames, and concurrent administrative cancellation.
