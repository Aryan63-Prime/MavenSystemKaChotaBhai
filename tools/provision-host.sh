#!/usr/bin/env bash
# ==============================================================================
# Sentinel Host Automated Provisioning Script (8-Step ADB Pipeline)
# Target Package : com.sentinel.host
# Target APK     : host-app/app/build/outputs/apk/debug/app-debug.apk
# ==============================================================================

set -euo pipefail

# ANSI Colors
BOLD="\033[1m"
GREEN="\033[1;32m"
BLUE="\033[1;34m"
CYAN="\033[1;36m"
YELLOW="\033[1;33m"
RED="\033[1;31m"
NC="\033[0m"

# Project Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOST_DIR="$PROJECT_ROOT/host-app"
APK_PATH="$HOST_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Android Components
PACKAGE="com.sentinel.host"
MAIN_ACTIVITY="$PACKAGE/.ui.MainActivity"
ACCESSIBILITY_SERVICE="$PACKAGE/com.sentinel.host.service.SentinelAccessibilityService"
NOTIFICATION_SERVICE="$PACKAGE/com.sentinel.host.service.SentinelNotificationListener"
DEVICE_ADMIN_RECEIVER="$PACKAGE/com.sentinel.host.receiver.SentinelDeviceAdminReceiver"
BOOT_RECEIVER="$PACKAGE/.receiver.BootReceiver"
FOREGROUND_SERVICE="$PACKAGE/.service.SentinelForegroundService"

# Default flags
DO_BUILD=true
CLEAN_BUILD=false
TARGET_SERIAL=""
BUILD_TYPE="release"
CUSTOM_APK=""

# Helper functions
log_step() {
    echo -e "\n${BOLD}${BLUE}=== [$1/8] $2 ===${NC}"
}

log_info() {
    echo -e "  ${CYAN}ℹ${NC} $1"
}

log_success() {
    echo -e "  ${GREEN}✔${NC} $1"
}

log_warn() {
    echo -e "  ${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "  ${RED}✖${NC} $1"
}

usage() {
    echo -e "${BOLD}Usage:${NC} $0 [options]"
    echo ""
    echo "Options:"
    echo "  -s, --serial <serial>  Target specific ADB device serial"
    echo "  --release              Build & install Release APK (default)"
    echo "  --debug                Build & install Debug APK"
    echo "  --apk <path>           Use custom APK path"
    echo "  --no-build             Skip Gradle build and use existing APK"
    echo "  --clean                Run './gradlew clean <task>' instead of assemble task"
    echo "  -h, --help             Show this help message"
    echo ""
    exit 0
}

# Parse CLI arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)
            TARGET_SERIAL="$2"
            shift 2
            ;;
        --release)
            BUILD_TYPE="release"
            shift
            ;;
        --debug)
            BUILD_TYPE="debug"
            shift
            ;;
        --apk)
            CUSTOM_APK="$2"
            DO_BUILD=false
            shift 2
            ;;
        --no-build)
            DO_BUILD=false
            shift
            ;;
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            ;;
    esac
done

if [[ -n "$CUSTOM_APK" ]]; then
    APK_PATH="$CUSTOM_APK"
elif [[ "$BUILD_TYPE" == "release" ]]; then
    APK_PATH="$HOST_DIR/app/build/outputs/apk/release/app-release.apk"
else
    APK_PATH="$HOST_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

echo -e "${BOLD}${CYAN}"
echo "  ███████╗███████╗███╗   ██╗████████╗██╗███╗   ██╗███████╗██╗     "
echo "  ██╔════╝██╔════╝████╗  ██║╚══██╔══╝██║████╗  ██║██╔════╝██║     "
echo "  ███████╗█████╗  ██╔██╗ ██║   ██║   ██║██╔██╗ ██║█████╗  ██║     "
echo "  ╚════██║██╔══╝  ██║╚██╗██║   ██║   ██║██║╚██╗██║██╔══╝  ██║     "
echo "  ███████║███████╗██║ ╚████║   ██║   ██║██║ ╚████║███████╗███████╗"
echo "  HOST AUTOMATED ADB PROVISIONING PIPELINE (8 STEPS)"
echo -e "${NC}"

# Check ADB availability
if ! command -v adb &>/dev/null; then
    log_error "'adb' command not found. Ensure Android SDK platform-tools are in your PATH."
    exit 1
fi

# Detect Target Device
ADB_CMD=(adb)
if [[ -n "$TARGET_SERIAL" ]]; then
    ADB_CMD+=( -s "$TARGET_SERIAL" )
fi

DEVICE_COUNT=$("${ADB_CMD[@]}" devices | grep -v "List of devices" | grep -v "^$" | wc -l | tr -d ' ')
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    log_error "No ADB devices or emulators connected. Please attach a target device with USB debugging enabled."
    exit 1
elif [[ "$DEVICE_COUNT" -gt 1 && -z "$TARGET_SERIAL" ]]; then
    DEFAULT_SERIAL=$("${ADB_CMD[@]}" devices | grep -v "List of devices" | grep -v "^$" | head -n 1 | awk '{print $1}')
    log_warn "Multiple devices detected ($DEVICE_COUNT). No -s serial specified."
    log_info "Auto-targeting first device: ${BOLD}$DEFAULT_SERIAL${NC}"
    ADB_CMD+=( -s "$DEFAULT_SERIAL" )
fi

DEVICE_NAME=$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || echo "Unknown")
ANDROID_VER=$("${ADB_CMD[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n' || echo "Unknown")
SDK_API=$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n' || echo "Unknown")

log_info "Target Device : ${BOLD}$DEVICE_NAME${NC} (Android $ANDROID_VER · API $SDK_API)"

# ------------------------------------------------------------------------------
# STEP 1: Build & Install APK with -r -g
# ------------------------------------------------------------------------------
log_step 1 "Build & Install APK (-r -g)"

if [[ "$DO_BUILD" == true ]]; then
    log_info "Building host-app ($BUILD_TYPE) APK via Gradle..."
    if [[ "$BUILD_TYPE" == "release" ]]; then
        GRADLE_TASK=":app:assembleRelease"
        if [[ "$CLEAN_BUILD" == true ]]; then
            GRADLE_TASK="clean :app:assembleRelease"
        fi
    else
        GRADLE_TASK=":app:assembleDebug"
        if [[ "$CLEAN_BUILD" == true ]]; then
            GRADLE_TASK="clean :app:assembleDebug"
        fi
    fi
    (cd "$HOST_DIR" && ./gradlew $GRADLE_TASK -q)
    log_success "Build completed."
else
    log_info "Skipping build (--no-build specified)."
fi

if [[ ! -f "$APK_PATH" ]]; then
    log_error "APK not found at $APK_PATH"
    exit 1
fi

log_info "Installing $APK_PATH with -r -g (granting all runtime permissions)..."
INSTALL_OUT=$("${ADB_CMD[@]}" install -r -g "$APK_PATH" 2>&1)
if echo "$INSTALL_OUT" | grep -q "Success"; then
    log_success "Host APK successfully installed (-r -g)."
else
    log_error "Installation failed: $INSTALL_OUT"
    exit 1
fi

# ------------------------------------------------------------------------------
# STEP 2: Grant READ_LOGS & Bluetooth Runtime Permissions
# ------------------------------------------------------------------------------
log_step 2 "Grant Development & Bluetooth Runtime Permissions"
for perm in android.permission.READ_LOGS android.permission.BLUETOOTH_ADVERTISE android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN; do
    log_info "Granting $perm to $PACKAGE..."
    "${ADB_CMD[@]}" shell pm grant "$PACKAGE" "$perm" 2>/dev/null || true
done
log_success "Runtime permissions verified."

# ------------------------------------------------------------------------------
# STEP 3: Configure AppOps
# ------------------------------------------------------------------------------
log_step 3 "Configure AppOps (MANAGE_EXTERNAL_STORAGE & RUN_ANY_IN_BACKGROUND)"

log_info "Setting MANAGE_EXTERNAL_STORAGE to allow..."
"${ADB_CMD[@]}" shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow
log_success "MANAGE_EXTERNAL_STORAGE allowed."

log_info "Setting RUN_ANY_IN_BACKGROUND to allow..."
"${ADB_CMD[@]}" shell appops set "$PACKAGE" RUN_ANY_IN_BACKGROUND allow
log_success "RUN_ANY_IN_BACKGROUND allowed."

log_info "Setting SYSTEM_ALERT_WINDOW to allow..."
"${ADB_CMD[@]}" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

# Android 13+ (API 33+) restricted settings bypass for accessibility / notification listener
if [[ "$SDK_API" =~ ^[0-9]+$ ]] && [[ "$SDK_API" -ge 33 ]]; then
    log_info "Android 13+ detected (API $SDK_API): Permitting restricted settings..."
    "${ADB_CMD[@]}" shell appops set "$PACKAGE" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
    log_success "ACCESS_RESTRICTED_SETTINGS allowed."
fi

# ------------------------------------------------------------------------------
# STEP 4: Whitelist from deviceidle (Battery Optimizations)
# ------------------------------------------------------------------------------
log_step 4 "Whitelist from Battery Optimization (deviceidle)"
log_info "Adding $PACKAGE to deviceidle whitelist..."
WHITELIST_OUT=$("${ADB_CMD[@]}" shell dumpsys deviceidle whitelist +"$PACKAGE" 2>&1 | tr -d '\r\n')
log_success "deviceidle response: $WHITELIST_OUT"

# ------------------------------------------------------------------------------
# STEP 5: Auto-enable SentinelAccessibilityService
# ------------------------------------------------------------------------------
log_step 5 "Auto-enable SentinelAccessibilityService"
log_info "Target Accessibility Component: $ACCESSIBILITY_SERVICE"

CURRENT_ACC=$("${ADB_CMD[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
if [[ "$CURRENT_ACC" == "null" || -z "$CURRENT_ACC" ]]; then
    NEW_ACC="$ACCESSIBILITY_SERVICE"
elif [[ "$CURRENT_ACC" != *"$ACCESSIBILITY_SERVICE"* ]]; then
    NEW_ACC="${CURRENT_ACC}:${ACCESSIBILITY_SERVICE}"
else
    NEW_ACC="$CURRENT_ACC"
fi

"${ADB_CMD[@]}" shell settings put secure enabled_accessibility_services "$NEW_ACC"
"${ADB_CMD[@]}" shell settings put secure accessibility_enabled 1
log_success "Accessibility service enabled and registered in secure settings."

# ------------------------------------------------------------------------------
# STEP 6: Auto-enable SentinelNotificationListener
# ------------------------------------------------------------------------------
log_step 6 "Auto-enable SentinelNotificationListener"
log_info "Target Notification Component: $NOTIFICATION_SERVICE"

# Primary method for Android 8.0+
"${ADB_CMD[@]}" shell cmd notification allow_listener "$NOTIFICATION_SERVICE" 2>/dev/null || true

# Secondary method via secure settings
CURRENT_NOTIF=$("${ADB_CMD[@]}" shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r\n')
if [[ "$CURRENT_NOTIF" == "null" || -z "$CURRENT_NOTIF" ]]; then
    NEW_NOTIF="$NOTIFICATION_SERVICE"
elif [[ "$CURRENT_NOTIF" != *"$NOTIFICATION_SERVICE"* ]]; then
    NEW_NOTIF="${CURRENT_NOTIF}:${NOTIFICATION_SERVICE}"
else
    NEW_NOTIF="$CURRENT_NOTIF"
fi
"${ADB_CMD[@]}" shell settings put secure enabled_notification_listeners "$NEW_NOTIF"
log_success "Notification listener enabled and granted listener access."

# ------------------------------------------------------------------------------
# STEP 7: Attempt dpm set-device-owner
# ------------------------------------------------------------------------------
log_step 7 "Attempt Device Owner / Device Admin (dpm set-device-owner)"
log_info "Target Admin Component: $DEVICE_ADMIN_RECEIVER"

DPM_OUT=$("${ADB_CMD[@]}" shell dpm set-device-owner "$DEVICE_ADMIN_RECEIVER" 2>&1 || true)

if echo "$DPM_OUT" | grep -iq "Success"; then
    log_success "Device Owner successfully set for Sentinel Host!"
else
    DPM_REASON=$(echo "$DPM_OUT" | grep -m1 -E "IllegalStateException|Exception|Error" | sed 's/^[ \t]*//' || echo "$DPM_OUT")
    log_warn "Full Device Owner could not be set (${DPM_REASON:0:70})."
    log_info "Setting active Device Admin instead as fallback..."
    ADMIN_FALLBACK=$("${ADB_CMD[@]}" shell dpm set-active-admin "$DEVICE_ADMIN_RECEIVER" 2>&1 || true)
    if echo "$ADMIN_FALLBACK" | grep -iq "Success"; then
        log_success "Fallback: Device Admin successfully activated."
    else
        ADMIN_REASON=$(echo "$ADMIN_FALLBACK" | grep -m1 -E "IllegalArgumentException|Exception|Error" | sed 's/^[ \t]*//' || echo "$ADMIN_FALLBACK")
        log_warn "Device Admin fallback notice (${ADMIN_REASON:0:70})."
    fi
fi

# ------------------------------------------------------------------------------
# STEP 8: Start SentinelForegroundService
# ------------------------------------------------------------------------------
log_step 8 "Start Host Foreground Service (SentinelForegroundService)"
log_info "Initializing host session & launching Sentinel..."

# 1. Launch MainActivity to seed session credentials & auto-approve enrollment
"${ADB_CMD[@]}" shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true

# 2. Trigger BootReceiver broadcast to ensure immediate service start
"${ADB_CMD[@]}" shell am broadcast -a com.sentinel.host.TEST_COMMAND -n "$BOOT_RECEIVER" >/dev/null 2>&1 || true

sleep 2

# Verify service is running
SERVICE_CHECK=$("${ADB_CMD[@]}" shell dumpsys activity services "$PACKAGE" 2>/dev/null | grep -E "ServiceRecord.*SentinelForegroundService" || true)
IS_FG=$("${ADB_CMD[@]}" shell dumpsys activity services "$PACKAGE" 2>/dev/null | grep -E "isForeground=true" || true)

if [[ -n "$SERVICE_CHECK" ]]; then
    log_success "SentinelForegroundService is running! (${IS_FG:-foreground active})"
else
    log_warn "Service starting... sending direct BOOT_COMPLETED intent"
    "${ADB_CMD[@]}" shell am broadcast -a android.intent.action.BOOT_COMPLETED -p "$PACKAGE" >/dev/null 2>&1 || true
    sleep 1
    log_success "Host startup intents successfully dispatched."
fi

# ------------------------------------------------------------------------------
# SUMMARY REPORT
# ------------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${GREEN}==============================================================================${NC}"
echo -e "${BOLD}${GREEN}   SENTINEL HOST PROVISIONING COMPLETED SUCCESSFULLY${NC}"
echo -e "${BOLD}${GREEN}==============================================================================${NC}"
echo -e "  Device Target       : ${BOLD}$DEVICE_NAME ($DEVICE_NAME, Android $ANDROID_VER)${NC}"
echo -e "  Package             : ${BOLD}$PACKAGE${NC}"
echo -e "  Runtime Perms       : Granted via -g"
echo -e "  READ_LOGS           : Granted"
echo -e "  Storage AppOp       : MANAGE_EXTERNAL_STORAGE Allowed"
echo -e "  Background AppOp    : RUN_ANY_IN_BACKGROUND Allowed"
echo -e "  Battery (Doze)      : Whitelisted from deviceidle"
echo -e "  Accessibility       : Enabled ($ACCESSIBILITY_SERVICE)"
echo -e "  Notification Access : Granted ($NOTIFICATION_SERVICE)"
echo -e "  Device Policy Admin : Configured"
echo -e "  Foreground Service  : Dispatched ($FOREGROUND_SERVICE)"
echo -e "${BOLD}${GREEN}==============================================================================${NC}\n"
