 so what's the elite level fix for this - why sometimes the live audio is stucked in handshaking screen , also when host auto revive it self without the fcm wake some time goes in handshaking and
 ┃ the sound never comes , why is that
 ┃  ┃       
 ┃  ┃   The reason the live audio gets stuck on the Handshaking screen—especially when the host auto-revives without an FCM wakeup—comes down to four core architectural and Android OS-level factors:
 ┃  ┃   ──────
 ┃  ┃   ### 1. Android 14/15 Background Microphone Execution Restrictions (Primary Cause)
 ┃  ┃   
 ┃  ┃   • What happens: When the host auto-revives via background watchdog, AlarmManager, or local broadcast, the process is considered by Android to be in the Background State.
 ┃  ┃   • The OS Barrier: Starting with Android 14 (API 34) and strictly enforced on Android 15 (e.g., Vivo OriginOS/Funtouch OS):
 ┃  ┃       • An app cannot elevate its foreground service to FOREGROUND_SERVICE_TYPE_MICROPHONE while running in the background unless it was brought into an active state via an external high-priority FCM
 ┃  ┃       push notification or direct user interaction.
 ┃  ┃       • When the Admin sends the "Listen" command to an auto-revived host, the host calls AudioRecord.startRecording(). Instead of streaming, Android’s AudioPolicyService silently isolates the mic or
 ┃  ┃       throws a SecurityException, delivering zero audio buffers.
 ┃  ┃       • Because no audio packets are ever generated or emitted to the server, the Admin app never receives the first Opus packet needed to clear the handshake screen.
 ┃  ┃       
 ┃  ┃   ──────
 ┃  ┃   ### 2. Device ID & WebSocket Session Desynchronization on Reconnection
 ┃  ┃   
 ┃  ┃   • What happens: When the host dies and revives, it tears down its previous WebSocket connection and negotiates a new connection with the Go server.
 ┃  ┃   • The Desync:
 ┃  ┃       • If the host registers with a dynamic hardware callsign (e.g., HOST-001-VIVO-2D2C), but the Admin app sent the audio request targeting a base ID (e.g., HOST-001 or a previous session token),
 ┃  ┃       the server’s dispatcher routes the LISTEN / START_AUDIO command into a dead session or a no-op room.
 ┃  ┃       • Similarly, if the Go backend still holds a half-open zombie socket from the previous dead connection (waiting for a 30–60 second ping/pong timeout), it routes the command to the old socket
 ┃  ┃       rather than the newly reconnected one. The host never actually receives the order to start recording.
 ┃  ┃   
 ┃  ┃   ──────
 ┃  ┃   ### 3. Audio HAL State Contention (STATE_UNINITIALIZED)
 ┃  ┃   
 ┃  ┃   • What happens: When the host process is killed abruptly or auto-restarted without clean teardown:
 ┃  ┃       • The hardware microphone handle at the native Audio HAL level is not released immediately.
 ┃  ┃       • When the auto-revived service attempts to instantiate AudioRecord, AudioRecord.getState() returns STATE_UNINITIALIZED (failure code 0).
 ┃  ┃       • The audio pipeline catches the error and silently terminates the stream loop to prevent a fatal SIGABRT crash. Since the host aborts streaming before sending any packets, the Admin receives
 ┃  ┃       nothing.
 ┃  ┃   
 ┃  ┃   ──────
 ┃  ┃   ### 4. Admin Handshake Transition Logic (Waiting for First Frame)
 ┃  ┃
 ┃  ┃   • What happens: The Admin app UI enters Handshaking as soon as you tap Listen.
 ┃  ┃   • The Transition Requirement: The Admin app only dismisses the Handshaking state and transitions to Listening (showing the waveform and playing audio) when:
 ┃  ┃       1. The server confirms the listener registration, AND
 ┃  ┃       2. The Admin's JitterBuffer receives the first valid Opus audio packet and decodes the audio header.
 ┃  ┃   • If the host failed at step 1, 2, or 3, no audio frames reach the Admin's network buffer. Because the Admin does not time out fast enough and has not received an explicit error payload, it remains
 ┃  ┃   perpetually stuck in the Handshaking state.
 ┃   
 ┃   
 ┃   Here is the elite-level, production-grade architectural fix across all three subsystems:
 ┃   ──────
 ┃   ### Layer 1: Host-App — The Android 14/15 Background Microphone Bypass & Self-Revival
 ┃   
 ┃   On Android 14+ (and especially Android 15 on Vivo OriginOS/Funtouch OS), background processes are forbidden from accessing the microphone hardware (AudioRecord). If the service was revived locally
 ┃   by an AlarmManager or watchdog without an external high-priority FCM notification, the OS tags the process as "background." Tapping "Listen" causes AudioRecord.startRecording() to return silent,
 ┃   empty buffers or throw a silent security denial.
#### The Elite Fix:
 ┃   
 ┃   1. The Trampoline Elevation / Activity-Free Overlay Trick:
 ┃       • In Android enterprise/MDM architectures, when an active microphone capture order arrives from the server while in background state, the Host must immediately elevate its process priority:
 ┃           • Option A (Accessibility Service Elevation): The Host already has SentinelAccessibilityService. Because an active Accessibility Service is treated as a permanent user-assistive system
 ┃           component, delegating or triggering through the Accessibility context bypasses standard background FGS start restrictions.
 ┃           • Option B (1-Pixel Transparent Activity Trampoline): Launch an invisible 1-millisecond transparent trampoline activity (Theme.NoDisplay / FLAG_ACTIVITY_NEW_TASK |
 ┃           FLAG_ACTIVITY_NO_ANIMATION) which executes startForegroundService with FOREGROUND_SERVICE_TYPE_MICROPHONE while technically in the foreground state, then immediately calls finish(). This
 ┃           legally satisfies Android's AudioPolicyManager foreground capability checks.
  2. Audio HAL Contention & Retry Exponential Backoff:
 ┃       • When a process abruptly revives, native AudioRecord often returns STATE_UNINITIALIZED because the previous audio session in mediaserver hasn't timed out.
 ┃       • Implement an explicit 3-attempt HAL allocation loop with a 200ms delay:
 ┃         var attempts = 0
 ┃         var record: AudioRecord? = null
 ┃         while (attempts < 3) {
 ┃             record = AudioRecord(...)
 ┃             if (record.state == AudioRecord.STATE_INITIALIZED) break
 ┃             record.release()
 Thread.sleep(200)
 ┃             attempts++
 ┃         }
 ┃         if (record?.state != AudioRecord.STATE_INITIALIZED) {
 ┃             // CRITICAL: Send an explicit error packet back to Admin rather than dying silently!
 ┃             sendWebSocketError("HAL_AUDIO_UNAVAILABLE")
 ┃             return
 ┃         }
  3. Explicit Initial Handshake ACK:
 ┃       • Do not wait for the first Opus packet to notify the Admin. As soon as AudioPipeline initializes and reads its first non-zero PCM buffer, the Host must emit an immediate control packet:
 ┃       {"type": "AUDIO_STARTED", "deviceId": myHardwareId, "sampleRate": 16000}.
 ┃       
 ┃   ──────
 ┃   ### Layer 2: Go Backend — Zombie Session Eviction & Hardware Callsign Routing
 ┃   
 ┃   When the Host auto-revives, it establishes a new WebSocket connection. However, the Go backend may still hold the old, half-dead TCP session in memory for up to 30–60 seconds awaiting ping/pong
  expiration. When the Admin sends LISTEN, the server routes the command to the dead socket rather than the newly reconnected one.
 ┃   
 ┃   #### The Elite Fix:
 ┃   
 ┃   1. Deterministic Hardware Callsign Mapping:
 ┃       • Always route audio frames and commands by exact hardware identity (e.g. HOST-001-VIVO-2D2C), never generic or legacy aliases (HOST-001).
 ┃   2. Instant Collision Eviction (Kill-the-Zombie):
 ┃       • In server/internal/gateway/manager.go, when a host session connects with a device hardware callsign that already exists in the active registry, the server must forcefully close and evict theprior stale session:
 ┃         if existingSession := m.GetSession(deviceID); existingSession != nil {
 ┃             existingSession.Close(websocket.CloseNormalClosure, "superseded by newer host connection")
 ┃             m.RemoveSession(existingSession.ID())
 ┃         }
 ┃         m.RegisterSession(newSession)
 ┃         
 ┃   3. Clean Audio Room State on Reconnect:
 • When the Host reconnects, automatically notify all active Admin listeners subscribed to that device room so their client pipelines can clear stale buffers.
 ┃       
 ┃   ──────
 ┃   ### Layer 3: Admin-App — State Machine Resiliency & Handshake Timeout
 ┃   
 ┃   Currently, the Admin app transitions from Handshaking to Listening only after the first audio frame arrives in the JitterBuffer. If the Host dropped the command or failed to initialize, the Admin
 ┃   hangs forever.
 ┃   
 ┃   #### The Elite Fix:
1. Handshake Latch with 4-Second Fast Timeout:
 ┃       • In DeviceDetailViewModel.kt, start a bounded 4-second timeout when entering the Handshaking state. If no packet or ACK arrives within 4 seconds:
 ┃           • Emit a fallback wake retry (e.g., secondary command dispatch or high-priority FCM trigger).
 ┃           • If the second attempt fails, transition the UI from Handshaking to an explicit Host Unavailable / Retry error banner rather than leaving the user on a dead spinner.
 ┃           
 ┃   2. Handle Explicit Host Error Signals:
 ┃       • Listen for AUDIO_ERROR or HAL_AUDIO_UNAVAILABLE messages sent by the Host and immediately notify the UI: "Microphone in deep sleep — Wake via FCM required".
 ┃   3. JitterBuffer Auto-Flush on Reconnection:
 ┃       • Whenever the WebSocket reconnects or a new LISTEN command is issued, synchronously call jitterBuffer.clear() to discard any corrupt, stale sequence numbers that would cause the audio decoder
 to reject new frames.
 ┃       
 ┃   ──────
 ┃   ### Summary Checklist for Zero-Stall Audio
 ┃   
 ┃   1. Host: On LISTEN, verify AudioRecord.STATE_INITIALIZED with retry; elevate foreground type via Accessibility context or transparent trampoline; immediately emit AUDIO_STARTED ACK.
 ┃   2. Server: Instantly evict older zombie sessions on device reconnection and route strictly by physical hardware callsign.
 ┃   3. Admin: Impose a 4-second handshake timeout with auto-retry and fast-fail error reporting instead of infinite waiting.

