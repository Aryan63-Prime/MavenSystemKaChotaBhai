# Sentinel Threat-Validation-Suite (External Red-Team Harness)

This suite contains **100% external, pure black-box red-team validation tools** designed to trigger and prove Sentinel's Phase-9 production counter-surveillance engines without introducing mock code or test flags into the shipping Android apps.

---

## 1. Inventory of Phase-9 Defensive Detection Vectors

1. **Vector #1 (Native C++ ELF64 `.text` RAM-vs-Disk Verifier)**:
   Scans `/proc/self/maps` for `/libc.so` execution mapping and performs `memcmp` against disk binary at the file offset to detect in-memory trampolines and inline hooks (ShadowHook / Dobby).
2. **Vector #2 (Native C++ Kernel `Seccomp-BPF` & `struct cred` Auditor)**:
   Parses `/proc/self/status` in C++ for `Seccomp: 2` and `CapEff: 0000000000000000` to catch kernel privilege-escalation exploits (DirtyPipe / DirtyCred).
3. **Vector #3 (Native C++ Connected UDP / QUIC / WireGuard Socket Hunter)**:
   Queries `getsockopt(fd, SOL_SOCKET, SO_TYPE)` to catch connected UDP (`SOCK_DGRAM`) tunnels, QUIC HTTP/3 flows, and VPN peers.
4. **Vector #4 (ART Virtual Machine `ArtMethod` Native-Flag Hook Detector)**:
   Reflectively audits critical Java methods (`Location.isMock`, `Settings.Secure.getString`, `KeyStore.getCertificateChain`) to ensure their bytecode modifiers haven't been flipped to `Modifier.NATIVE` (0x0100) by ART hook engines (LSPlant / SandHook / Pine).
5. **Vector #5 (`ServiceManager.sCache` IBinder IPC Proxy Hijack Detector)**:
   Audits Android `android.os.ServiceManager.sCache` for dynamic `java.lang.reflect.Proxy` interceptors used by sandboxes and cloning tools (VirtualApp / LSPatch).
6. **Vector #6 (`DisplayManager` Covert Screen-Recording & Virtual-Display Detector)**:
   Queries `DisplayManager.displays` for secondary/virtual displays (`displayId != 0`), catching `screenrecord`, MediaProjection, TeamViewer, AnyDesk, and VNC frame-grabbers.
7. **Vector #7 (`AudioManager` `TYPE_REMOTE_SUBMIX` Internal Audio-Tap Detector)**:
   Audits audio routing sinks for device type 25 (`TYPE_REMOTE_SUBMIX`), flagging background call recording and internal audio loopbacks.
8. **Vector #8 (Cross-App Background Data Exfiltration Detector)**:
   Calculates $(TotalTx - MyUidTx) / \Delta t$ to measure the upload rate of non-Sentinel background apps while the phone is stationary.
9. **Vector #9 (Raw GNSS Baseband TCXO Clock Discontinuity & Drift Auditor)**:
   Subscribes to `GnssMeasurementsEvent.Callback` to monitor `clock.driftNanosPerSecond` and `hardwareClockDiscontinuityCount` for over-the-air RF spoofing (HackRF / BladeRF).
10. **Vector #10 (Deep `/proc/self/mountinfo` & Mount-Namespace Inode Dissector)**:
    Parses `/proc/self/mountinfo` for hidden OverlayFS upperdirs (`upperdir=`, `kernelsu`, `apatch`, `magisk`) and records the mount namespace inode (`/proc/self/ns/mnt`).
11. **Vector #11 (File-Based Encryption FBE & Keyguard Hardware Lock Auditor)**:
    Audits `DevicePolicyManager.storageEncryptionStatus` and `KeyguardManager.isDeviceSecure`.
12. **Vector #12 (Standalone External Threat Harness)**:
    This standalone red-team test directory.

---

## 2. Usage Instructions

### Arm External Red-Team Threats
```bash
./threat-validation-suite/run-external-redteam-suite.sh --arm-external [optional_device_serial]
```
This command performs 3 real external attacks:
- Launches `screenrecord` on the target Android phone (activating an actual secondary Virtual Display in `DisplayManager`).
- Broadcasts a rogue macOS `_ssh._tcp` service instance named `"Rogue-Kali-DropBox-SSH"` via `dns-sd`.
- Generates and plays a 20,000 Hz ultrasonic sine wave through the computer speakers via `afplay`.

### Verify Telemetry
Open the **Admin Console** on the Android device and inspect the **MR. ROBOT NETWORKINT** tab:
- **`ELF .TEXT, BINDER IPC, SCREEN-TAP & EXFIL GUARD`** tile will trigger:
  `🚨 SCREEN RECORDING ACTIVE (#<id>:'screenrecord')`
- **`PASSIVE mDNS / ZEROCONF LAN DISCOVERY`** tile will list:
  `• Rogue-Kali-DropBox-SSH  ➔  _ssh._tcp`
- **`ULTRASONIC BEACON FFT`** tile will detect:
  `🚨 ULTRASONIC BEACON (20.0 kHz)`

### Clean Up External Threats
```bash
./threat-validation-suite/run-external-redteam-suite.sh --clean [optional_device_serial]
```
Terminates the external `screenrecord`, rogue `dns-sd`, and `afplay` processes, returning all telemetry indicators to `Verified Clean`.
