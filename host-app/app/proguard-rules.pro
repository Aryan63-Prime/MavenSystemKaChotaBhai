-keepattributes Signature
-keepattributes *Annotation*

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Moshi
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Tink (used by security-crypto / EncryptedSharedPreferences)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**

# =========================================================================
# APEX SENTINEL PHASE-14: R8 METADATA ANNIHILATION & JNI BRIDGE RULES
# =========================================================================

-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Strip verbose, debug, info, and warning logs from release bytecode
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

-repackageclasses "o"

# CRITICAL: Preserve exact JNI classes and native method signatures for RegisterNatives & NDK
-keep class com.sentinel.host.data.device.SystemInfoProvider {
    private static native <methods>;
    public static native <methods>;
}
-keep class com.sentinel.host.data.audio.OpusJni {
    private static native <methods>;
    public static native <methods>;
}
-keep class com.sentinel.host.data.device.RootDetectionManager {
    private static native <methods>;
    public static native <methods>;
}
-keep class com.sentinel.host.data.device.AppSurfaceInspector {
    private static native <methods>;
    public static native <methods>;
}

# Preserve Android framework entry points
-keep class com.sentinel.host.SentinelApp { *; }
-keep class com.sentinel.host.service.SentinelForegroundService { *; }
-keep class com.sentinel.host.receiver.** { *; }
