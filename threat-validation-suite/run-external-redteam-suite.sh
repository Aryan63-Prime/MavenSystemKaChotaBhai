#!/usr/bin/env bash
# ==============================================================================
# Sentinel Threat-Validation-Suite: External Red-Team Threat Generation
# 100% External Real-World Threat Emulation (Zero mock code in production apps)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="/tmp/sentinel_redteam_pids.txt"
DEFAULT_TARGET_DEVICE="10.11.15.111:40611"
TARGET_DEVICE="${ANDROID_SERIAL:-$DEFAULT_TARGET_DEVICE}"

usage() {
    echo "Usage: $0 [--arm-external] [--clean] [device_serial]"
    echo ""
    echo "Commands:"
    echo "  --arm-external   Launch external screen recording on target device,"
    echo "                   broadcast rogue mDNS SSH node on LAN, and play"
    echo "                   over-the-air 20kHz ultrasonic carrier beacon."
    echo "  --clean          Tear down external test harnesses, stop screen recording,"
    echo "                   kill rogue mDNS broadcast, and delete test captures."
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

ACTION="$1"
if [[ $# -ge 2 ]]; then
    TARGET_DEVICE="$2"
fi

if [[ "$ACTION" == "--arm-external" ]]; then
    echo "======================================================================"
    echo " [!] ARMING EXTERNAL RED-TEAM THREAT HARNESS"
    echo " Target Device: $TARGET_DEVICE"
    echo "======================================================================"

    # 1. Start external screen recording on target device via ADB
    # Creates an active secondary Virtual Display inside Android's DisplayManager
    echo "[1/3] Triggering real Android Virtual Display tap (screenrecord)..."
    adb -s "$TARGET_DEVICE" shell "screenrecord --time-limit 30 /sdcard/Download/redteam_screen_tap.mp4 >/dev/null 2>&1 &" || true
    echo "      ↳ screenrecord running on $TARGET_DEVICE (Virtual Display active)"

    # 2. Start macOS rogue mDNS zero-conf broadcast
    echo "[2/3] Broadcasting rogue mDNS ZeroConf service on local subnet..."
    # Kill any existing dns-sd first
    pkill -f "dns-sd -R Rogue-Kali-DropBox-SSH" || true
    dns-sd -R "Rogue-Kali-DropBox-SSH" _ssh._tcp . 2222 >/dev/null 2>&1 &
    DNS_SD_PID=$!
    echo "$DNS_SD_PID" > "$PID_FILE"
    echo "      ↳ dns-sd broadcasting 'Rogue-Kali-DropBox-SSH._ssh._tcp' (PID: $DNS_SD_PID)"

    # 3. Generate & play 20 kHz ultrasonic beacon through macOS speakers
    echo "[3/3] Generating & transmitting 20 kHz ultrasonic beacon over-the-air..."
    python3 "$SCRIPT_DIR/generate_ultrasonic_beacon.py"
    WAV_FILE="$SCRIPT_DIR/ultrasonic_20khz_beacon.wav"
    if [[ -f "$WAV_FILE" ]] && command -v afplay >/dev/null 2>&1; then
        afplay "$WAV_FILE" &
        AFPLAY_PID=$!
        echo "$AFPLAY_PID" >> "$PID_FILE"
        echo "      ↳ afplay transmitting 20 kHz carrier (PID: $AFPLAY_PID)"
    fi

    echo "======================================================================"
    echo " [✓] RED-TEAM THREATS ARMED. REFRESH ADMIN APP TELEMETRY TO VERIFY:"
    echo "     • DisplayManager: 🚨 SCREEN RECORDING ACTIVE"
    echo "     • ZeroConf mDNS: Rogue-Kali-DropBox-SSH detected"
    echo "     • Ultrasonic Goertzel: 20.0 kHz beacon detection"
    echo "======================================================================"

elif [[ "$ACTION" == "--clean" ]]; then
    echo "======================================================================"
    echo " [*] CLEANING EXTERNAL RED-TEAM HARNESSES"
    echo "======================================================================"

    # 1. Stop screen recording on target device
    echo "[1/3] Terminating Android screenrecord processes on $TARGET_DEVICE..."
    adb -s "$TARGET_DEVICE" shell "pkill -f screenrecord || killall screenrecord" 2>/dev/null || true
    adb -s "$TARGET_DEVICE" shell "rm -f /sdcard/Download/redteam_screen_tap.mp4" 2>/dev/null || true
    echo "      ↳ Virtual Display terminated and capture files purged."

    # 2. Terminate rogue mDNS broadcast
    echo "[2/3] Terminating rogue mDNS broadcasts..."
    pkill -f "dns-sd -R Rogue-Kali-DropBox-SSH" 2>/dev/null || true
    echo "      ↳ Rogue mDNS broadcaster halted."

    # 3. Terminate ultrasonic audio playback
    echo "[3/3] Terminating acoustic ultrasonic players..."
    pkill -f "afplay.*ultrasonic" 2>/dev/null || true
    if [[ -f "$PID_FILE" ]]; then
        while read -r pid; do
            kill "$pid" 2>/dev/null || true
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi
    echo "      ↳ Acoustic playback stopped and PID file cleared."

    echo "======================================================================"
    echo " [✓] RED-TEAM CLEAN COMPLETE. Telemetry returning to Verified Clean."
    echo "======================================================================"

else
    usage
fi
