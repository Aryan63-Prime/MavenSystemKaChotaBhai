# Feature Specification: Tactical Fleet Intelligence & Online ML Engine

**Version:** 2.4.0  
**Status:** Implemented & Verified  
**Date:** 2026-10-21  

---

## 1. Executive Architecture

Project Sentinel's Tactical Fleet Intelligence upgrade equips monitored host devices and admin command consoles with onboard machine learning, Bayesian threat modeling, kinematic motion prediction, and out-of-band resilience mechanisms.

```mermaid
graph TD
    subgraph Host Device ("host-app")
        Sensors["Hardware Sensors (IMU, RF, Baro, Mag, Battery, USB)"] --> Welford["Online Welford Model (μ, M2)"]
        Sensors --> SysInfo["System & Security Inspector"]
        Welford --> Defcon["Bayesian Logistic DEFCON Engine"]
        SysInfo --> Defcon
        Defcon --> Telemetry["TELEMETRY_REPORT Envelope"]
        SMS["SMS Receiver (#SENTINEL:*)"] --> MdmLock["Offline MDM Lock / Service Wake"]
    end

    subgraph Server ("server")
        Telemetry --> Gateway["WebSocket Gateway & Dispatcher"]
        Gateway --> AliasResolver["Bidirectional Callsign Resolver"]
        AliasResolver --> PG["PostgreSQL (Location & Route History)"]
        AliasResolver --> Redis["Redis (Realtime Ephemeral State)"]
    end

    subgraph Admin Console ("admin-app")
        Gateway --> AdminVM["DeviceDetailViewModel"]
        AdminVM --> ZeroTrust["Independent DEFCON Verifier (minOf)"]
        AdminVM --> Kalman["Kinematic α-β Kalman Filter (+15m Intercept)"]
        AdminVM --> AudioDSP["4D Acoustic DSP (Speech / Hum / Impulse)"]
        AdminVM --> MapLibre["Tactical Route Polyline & Auto-Play Replay"]
        Biometric["BiometricPromptHelper"] --> HighPrivCmd["Signed Command Dispatch (ECDSA)"]
    end
```

---

## 2. Online Welford Self-Learning Anomaly Model

Monitored mobile devices operate in constantly changing electromagnetic, cellular, and physical environments. Rather than using static thresholds, `SystemInfoProvider.kt` implements an online self-learning Gaussian distribution model via Welford’s Algorithm for real-time running variance.

### 2.1 Tracked Channels
1. **RF Link Strength:** Cellular / Wi-Fi RSSI in dBm.
2. **Geomagnetic Flux:** Total 3-axis ambient magnetic intensity in $\mu\text{T}$.
3. **Micro-Seismic Vibration:** High-frequency delta acceleration in mG ($10^{-3} \text{g}$).

### 2.2 Algorithm & Contamination Prevention
$$\mu_n = \mu_{n-1} + \frac{x_n - \mu_{n-1}}{n}$$
$$M_{2, n} = M_{2, n-1} + (x_n - \mu_{n-1})(x_n - \mu_n)$$
$$\sigma_n = \sqrt{\frac{M_{2, n}}{n - 1}}$$

1. **Pre-Update Scoring (Zero Target Contamination):** Anomaly Z-scores are evaluated against prior statistics $(\mu_{n-1}, \sigma_{n-1})$ *before* $x_n$ is incorporated into memory:
   $$Z_{\text{feature}} = \frac{|x_n - \mu_{n-1}|}{\sigma_{n-1}}$$
   $$Z_{\text{learned}} = \max(Z_{\text{RF}}, Z_{\text{Mag}}, Z_{\text{Vib}})$$
2. **15-Second Update Throttling:** Baseline parameter updates occur at most once every 15 seconds, preventing frequent UI refresh polling from overfitting.
3. **$3.0\sigma$ Winsorization:** Active physical attacks or sudden interference spikes are clamped to $[\mu - 3.0\sigma, \mu + 3.0\sigma]$ before computing $M_{2, n}$, preventing attackers from corrupting the baseline.

---

## 3. Calibrated 12-Feature Bayesian DEFCON Engine

The DEFCON classification engine maps endpoint threat vectors into a calibrated probability $P \in [0.1\%, 99.9\%]$ using logistic log-odds.

### 3.1 Mathematical Calibration
$$\text{log-odds} = \ln\left(\frac{P}{1 - P}\right) = \beta_0 + \sum_{i=1}^{k} w_i \cdot x_i$$
$$P_{\text{threat}} = \frac{1}{1 + e^{-\text{log-odds}}} \times 100\%$$

* **Baseline Prior ($\beta_0$):** Set to $-3.8$, establishing a nominal clean baseline risk of:
  $$P_{\text{nominal}} = \frac{1}{1 + e^{3.8}} \times 100\% \approx 2.18\% \implies \mathbf{DEFCON\ 5}$$

### 3.2 Feature Weights & Activation Thresholds

| Threat Vector | Feature Weight ($w_i$) | Resulting $P$ (Single Threat) | DEFCON Level |
| :--- | :--- | :--- | :--- |
| **Root / Kernel Compromise** | $+5.6$ | $85.8\%$ | **DEFCON 1** ($\ge 80\%$) |
| **Physical USB HID Injection** | $+5.3$ | $81.7\%$ | **DEFCON 1** ($\ge 80\%$) |
| **SELinux Permissive Mode** | $+4.3$ | $62.2\%$ | **DEFCON 2** ($\ge 50\%$) |
| **Rogue 2G / IMSI Catcher** | $+4.2$ | $59.8\%$ | **DEFCON 2** ($\ge 50\%$) |
| **GNSS Mock / Spoofing** | $+4.1$ | $57.4\%$ | **DEFCON 2** ($\ge 50\%$) |
| **SIM Roaming / Mismatch** | $+2.8$ | $26.9\%$ | **DEFCON 3** ($\ge 20\%$) |
| **Active USB ADB Connection** | $+2.7$ | $25.0\%$ | **DEFCON 3** ($\ge 20\%$) |
| **VPN / Proxy Tunnel Active** | $+2.5$ | $21.4\%$ | **DEFCON 3** ($\ge 20\%$) |
| **High EMF (>120 $\mu\text{T}$)** | $+1.4$ | $8.3\%$ | **DEFCON 4** ($\ge 6\%$) |
| **ADB / DevOpts Enabled** | $+1.3$ | $7.6\%$ | **DEFCON 4** ($\ge 6\%$) |
| **Online ML Anomaly ($Z > 3.0\sigma$)** | $+0.85 \cdot (Z - 2.5)$ | Variable (up to $+3.0$) | Scaled Threat Boost |

### 3.3 Zero-Trust Verification
The Admin application computes an independent threat probability `adminVerifiedDefcon` from unvarnished telemetry properties. The display enforces:
$$\text{DEFCON}_{\text{final}} = \min(\text{DEFCON}_{\text{host}}, \text{DEFCON}_{\text{admin\_verified}})$$
Even if an attacker gains root access and forces the host payload to output `"defconLevel": 5`, the admin console identifies the root flag and overrides the display to **DEFCON 1**.

---

## 4. Kinematic $\alpha$-$\beta$ Kalman Intercept Filter

In tactical tracking, GNSS fixes suffer from civilian jitter, multipath reflection, and latency. The tracking engine implements a sequential $\alpha$-$\beta$ kinematic filter to derive velocity vectors and project forward intercepts.

### 4.1 Filter Formulation
$$\hat{x}_{k|k-1} = \hat{x}_{k-1|k-1} + v_{k-1|k-1} \Delta t$$
$$r_k = z_k - \hat{x}_{k|k-1}$$
$$\hat{x}_{k|k} = \hat{x}_{k|k-1} + \alpha r_k$$
$$v_{k|k} = v_{k|k-1} + \frac{\beta}{\Delta t} r_k$$

* **Filter Gains:** $\alpha = 0.65$, $\beta = 0.35$.
* **Initial Velocity Derivation:** Unlike standard filters that assume initial velocity $v_0 = 0$ (which causes velocity drag across short tracks), the filter initializes velocity directly from the initial segment:
  $$v_{\text{initial}} = \frac{p_1 - p_0}{\Delta t_0}$$
* **Projection (+15 min):** Calculates projected geographic coordinates $(p_{\text{lat}}, p_{\text{lon}})$, Course Over Ground (COG in degrees), speed in km/h, and a statistical confidence score ($55\% - 96\%$).

---

## 5. Tactical Route Polyline & Auto-Play Replay

* **Interactive Polyline Rendering:** Implemented on top of MapLibre Android SDK using GeoJSON `LineString` sources and `LineLayer` styling (`#38BDF8`, width `3.5f`, opacity `0.85f`).
* **Multi-Day Range Filtering:** Dynamic queries for 24 hours, 7 days, 30 days, or custom calendar date intervals with instant client-side date arithmetic.
* **Auto-Play Speed Modes:** Smooth continuous playback at `1x`, `2x`, or `4x` multiplier with interactive Play/Pause controls.
* **Dwell Point Clustering:** Identifies stops where fixes dwell within $\le 40\text{m}$ for $\ge 3$ consecutive breadcrumbs, annotating dwell duration in minutes.

---

## 6. Out-of-Band Offline SMS Wake & Recovery

When cellular packet data is disabled or firewalled, `SentinelSmsReceiver.kt` provides zero-internet tactical wake and control:

* **Broadcast Listener:** Captures `Telephony.Sms.Intents.SMS_RECEIVED_ACTION`.
* **Wake Lock Protection:** Holds a `PowerManager.PARTIAL_WAKE_LOCK` for 15,000ms.
* **Supported Tactical Commands:**
  * `#SENTINEL:WAKE`: Forces `SentinelForegroundService` to immediately restart uplink retry cycles.
  * `#SENTINEL:PING`: Forces an immediate location and network report.
  * `#SENTINEL:LOCK`: Triggers immediate hardware lock via `MdmManager.lockDeviceNow()`.

---

## 7. 4D Acoustic Signal DSP Stream Classifier

Audio incoming from host microphones is processed frame-by-frame (20ms PCM windows at 16 kHz) in `AudioMonitor.kt`:

1. **RMS Amplitude & dBFS:** Computes root-mean-square power mapped to $[-60\text{ dBFS}, 0\text{ dBFS}]$.
2. **Peak Crest Factor:** Evaluates $\text{Peak} / \text{RMS}$ to differentiate continuous noise from sharp transient impulses.
3. **Zero-Crossing Rate (ZCR):** Derives primary spectral frequency $f_{\text{dominant}} = \frac{\text{ZCR} \cdot 16000}{2 \cdot N}$.
4. **Lag-1 Autocorrelation ($R_{xx}(1)$):** Measures waveform periodicity and pitch continuity.

### DSP Decision Boundaries:
* $\text{dBFS} \le -46 \implies$ `QUIET ROOM`
* $\text{Crest} > 5.2 \text{ and } \text{dBFS} > -22 \implies$ `SHARP IMPULSE / KNOCK`
* $\text{dBFS} > -12 \implies$ `LOUD / HIGH SPL`
* $R_{xx}(1) > 0.68 \text{ and } f \in [85, 450]\text{Hz} \text{ and } \text{dBFS} > -42 \implies$ `HUMAN SPEECH (VOICED)`
* $f \in [1, 109]\text{Hz} \text{ and } \text{dBFS} > -38 \implies$ `LOW-FREQ HUM / ENGINE`
* Default $\implies$ `AMBIENT SOUND`
