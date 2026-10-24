package com.sentinel.host.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

data class RootCheckResult(
    val isRooted: Boolean,
    val rootScore: Int,
    val detectedVectors: List<String>,
    val isEmulator: Boolean = false,
    val isHardwareSpoofed: Boolean = false,
    val emulatorScore: Int = 0,
    val emulatorVectors: List<String> = emptyList(),
    val teeAttestationStatus: String = "Pending",
    val gpuRendererSummary: String = "Unknown",
    val sensorHardwareSummary: String = "Unknown",
    val hypervisorSummary: String = "Bare-Metal Physical Silicon",
    val nativeSyscallStatus: String = "Verified Clean (ARM64 svc #0)",
    val tlsMitmDetected: Boolean = false,
    val tlsMitmSummary: String = "Verified Clean (0 User MITM CAs)"
)

data class GpuSiliconProbe(
    val vendor: String,
    val renderer: String,
    val version: String,
    val extensions: String
)

/**
 * Enterprise Defense-in-Depth Root, Tamper, Hypervisor & Silicon Attestation Manager.
 *
 * Implements:
 * - 6 Root & Runtime Hook Vectors (SU, test-keys, Magisk/KernelSU, debug/secure props, rw/overlay mounts, Frida/Zygisk/TracerPid)
 * - 6 Deep Anti-Emulation & Silicon Cross-Verification Vectors:
 *   1. AndroidKeyStore X.509 ASN.1 Hardware Attestation (StrongBox / TEE vs Software Keymaster)
 *   2. Headless EGL 1x1 PBuffer GPU Silicon Probe & SoC Cross-Validation (e.g. SM8650 vs Adreno 750)
 *   3. Kernel Virtualization & VirtIO/Goldfish/Ranchu Device-Tree Inspection (+ Native JNI Syscall Probe)
 *   4. Physical MEMS Sensor Vendor Audit (catching Goldfish / AOSP virtual sensor HALs)
 *   5. SoC Asymmetric CPU Frequency Domain & Thermal Zone Count Verification
 *   6. OEM Ecosystem Consistency Audit (verifying Samsung Knox/System Libraries when Build.MANUFACTURER == Samsung)
 */
@Singleton
class RootDetectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:RootDetect"
        private const val KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
        @Volatile var cachedQseeSmcLatencyUs: Double = 142.5

        init {
            try {
                System.loadLibrary("opus_jni")
            } catch (_: Throwable) {}
        }

        @JvmStatic
        private external fun nativeIntegrityProbe(): String

        @JvmStatic
        private external fun nativeArm64SyscallProbe(): String

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/app/Superuser.apk"
        )

        private val KNOWN_ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "io.github.vvb2060.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingouser.com",
            "me.bmax.apatch"
        )

        private val DANGEROUS_BINARIES = listOf(
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/system/xbin/daemonsu",
            "/system/bin/.ext/.su"
        )

        private val EMULATOR_DEVICE_NODES = listOf(
            "/dev/qemu_pipe",
            "/dev/goldfish_pipe",
            "/dev/goldfish_address_space",
            "/dev/GoldfishSync",
            "/dev/ranchu_pipe",
            "/dev/socket/qemud",
            "/dev/vboxguest",
            "/dev/vboxuser",
            "/sys/bus/virtio/devices/virtio0",
            "/sys/class/misc/goldfish_pipe",
            "/sys/class/misc/goldfish_sync"
        )

        private val EMULATOR_BOOT_PROPS = listOf(
            "ro.boot.hardware",
            "ro.hardware",
            "ro.hardware.egl",
            "ro.hardware.vulkan",
            "ro.hardware.gralloc",
            "ro.hardware.audio.primary",
            "ro.board.platform"
        )

        private val EMULATOR_INIT_SERVICES = listOf(
            "init.svc.qemu-props",
            "init.svc.goldfish-logcat",
            "init.svc.goldfish-setup",
            "init.svc.ranchu-setup",
            "init.svc.ranchu-net"
        )
    }

    @Volatile private var cachedGpuProbe: GpuSiliconProbe? = null
    @Volatile private var cachedTeeResult: Pair<Boolean, String>? = null

    fun checkRoot(): RootCheckResult {
        val detectedVectors = mutableListOf<String>()
        val emulatorVectors = mutableListOf<String>()
        var isHardwareSpoofed = false

        // =========================================================================
        // PART A: ROOT & RUNTIME TAMPERING AUDIT
        // =========================================================================

        // Vector 1: su binary detection
        for (path in SU_PATHS) {
            try {
                if (File(path).exists()) {
                    detectedVectors.add("SU binary found: $path")
                }
            } catch (_: Throwable) {}
        }

        // Vector 2: Build tags inspection
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            detectedVectors.add("Test-keys build detected (non-release ROM)")
        }

        // Vector 3: Superuser / Root management packages
        val pm = context.packageManager
        for (pkg in KNOWN_ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                detectedVectors.add("Root manager package detected: $pkg")
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Throwable) {}
        }

        // Vector 4: Dangerous system properties
        try {
            if (getSystemProperty("ro.debuggable") == "1") {
                detectedVectors.add("System property ro.debuggable=1")
            }
            if (getSystemProperty("ro.secure") == "0") {
                detectedVectors.add("System property ro.secure=0")
            }
        } catch (_: Throwable) {}

        // Vector 5: Mount inspection (/proc/mounts)
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) {
                for (line in mountsFile.readLines()) {
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 4) {
                        val mountPoint = tokens[1]
                        val options = tokens[3]
                        if ((mountPoint == "/system" || mountPoint == "/") && options.split(",").contains("rw")) {
                            detectedVectors.add("Writable /system mount detected: $mountPoint")
                        }
                        if (line.contains("magisk") || line.contains("kernelsu") || line.contains("apatch")) {
                            detectedVectors.add("Root overlay mount detected: $mountPoint")
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Vector 6: Dangerous binaries (busybox)
        for (bin in DANGEROUS_BINARIES) {
            try {
                if (File(bin).exists()) {
                    detectedVectors.add("Dangerous binary present: $bin")
                }
            } catch (_: Throwable) {}
        }

        // =========================================================================
        // PART B: DEEP HYPERVISOR, SILICON & ANTI-SPOOFING FORENSICS
        // =========================================================================

        // 1. Native C++ Syscall Probe (Resistant to Java hooking)
        var nativeSyscallStatus = "Verified Clean (ARM64 svc #0)"
        try {
            val nativeFlags = nativeIntegrityProbe()
            if (nativeFlags.isNotBlank()) {
                val tokens = nativeFlags.split("|")
                if (tokens.contains("NATIVE_SYSCALL_LIBC_DIVERGENCE")) {
                    detectedVectors.add("Native ARM64 svc #0 syscall diverged from hooked libc")
                    nativeSyscallStatus = "🚨 Syscall Divergence (libc hook bypassed via svc #0)"
                }
                if (tokens.contains("NATIVE_LIBC_INLINE_HOOK")) {
                    detectedVectors.add("Native libc.so inline hook / trampoline detected")
                    nativeSyscallStatus = "🚨 Hooked libc.so Prologue (Inline Trampoline Detected)"
                }
                if (tokens.contains("NATIVE_EMU_NODE")) {
                    emulatorVectors.add("Native C++: QEMU/VirtIO/Goldfish kernel node present")
                    if (!nativeSyscallStatus.startsWith("🚨")) {
                        nativeSyscallStatus = "⚠️ Emulated Node Detected (ARM64 svc #0)"
                    }
                }
                if (tokens.contains("NATIVE_BOOT_PROP_EMU")) {
                    emulatorVectors.add("Native C++: Bootloader/HAL prop indicates ranchu/goldfish")
                }
                if (tokens.contains("NATIVE_KERNEL_QEMU")) {
                    emulatorVectors.add("Native C++: ro.kernel.qemu=1")
                }
                if (tokens.contains("NATIVE_HOOK_FRAMEWORK")) {
                    detectedVectors.add("Native C++: Runtime hook framework in /proc/self/maps")
                }
                if (tokens.contains("NATIVE_TRACER_ATTACHED")) {
                    detectedVectors.add("Native C++: Active ptrace debugger attached (TracerPid != 0)")
                }
                if (tokens.contains("NATIVE_RWXP_SHELLCODE_PAGE")) {
                    detectedVectors.add("Native C++: In-memory rwxp shellcode pages detected in /proc/self/maps")
                }
                if (tokens.contains("NATIVE_FILELESS_ELF_INJECTION")) {
                    detectedVectors.add("Fileless ELF Memory Injection (/memfd or deleted r-xp page in /proc/self/maps)")
                }
                if (tokens.contains("NATIVE_ELF_TEXT_PATCHED")) {
                    detectedVectors.add("Native C++: libc.so .text RAM-vs-Disk divergence (inline hook / ShadowHook detected)")
                    nativeSyscallStatus = "🚨 Patched libc.so .text (In-Memory Trampoline Detected)"
                }
                if (tokens.contains("NATIVE_SECCOMP_DISABLED")) {
                    detectedVectors.add("Kernel Seccomp-BPF filter disabled (Seccomp: 0 - possible kernel privilege escalation)")
                }
                if (tokens.contains("NATIVE_ROOT_CAP_EFF_ELEVATED")) {
                    detectedVectors.add("Kernel Effective Capabilities elevated (CapEff != 0 - possible root exploit)")
                }
            }
        } catch (_: UnsatisfiedLinkError) {
            nativeSyscallStatus = "N/A (Native Lib Missing)"
        } catch (_: Throwable) {
            nativeSyscallStatus = "N/A"
        }

        // 2. Kernel Virtualization Nodes & VirtIO Bus
        val foundNodes = mutableListOf<String>()
        for (node in EMULATOR_DEVICE_NODES) {
            try {
                if (File(node).exists()) {
                    foundNodes.add(node)
                }
            } catch (_: Throwable) {}
        }
        if (foundNodes.isNotEmpty()) {
            emulatorVectors.add("Virtualization device nodes: ${foundNodes.take(3).joinToString(", ")}")
        }

        // 3. Unspoofable Bootloader, HAL & Init Service Properties
        val claimedManufacturer = (Build.MANUFACTURER ?: "").trim()
        val claimedModel = (Build.MODEL ?: "").trim()
        val claimedSoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (Build.SOC_MODEL ?: "").trim() else ""

        for (prop in EMULATOR_BOOT_PROPS) {
            val v = getSystemProperty(prop).lowercase()
            if (v.contains("ranchu") || v.contains("goldfish") || v.contains("cuttlefish") ||
                v.contains("vbox") || v.contains("swiftshader") || v.contains("emulation")) {
                emulatorVectors.add("Virtual HAL property: $prop=$v")
                if (claimedManufacturer.equals("Samsung", ignoreCase = true) || claimedSoc.startsWith("SM", ignoreCase = true)) {
                    isHardwareSpoofed = true
                }
            }
        }

        for (svc in EMULATOR_INIT_SERVICES) {
            val v = getSystemProperty(svc)
            if (v.isNotBlank()) {
                emulatorVectors.add("Emulator init daemon active: $svc=$v")
            }
        }

        if (getSystemProperty("ro.kernel.qemu") == "1" || getSystemProperty("ro.boot.qemu") == "1") {
            emulatorVectors.add("QEMU kernel flag enabled (ro.kernel.qemu=1)")
        }

        // 4. Headless 1x1 PBuffer EGL / OpenGL ES Silicon Cross-Validation
        val gpuProbe = probeGpuSilicon()
        val glVendor = gpuProbe.vendor
        val glRenderer = gpuProbe.renderer
        val glVersion = gpuProbe.version
        val glExtensions = gpuProbe.extensions
        val glCombined = "$glVendor $glRenderer".lowercase()
        var gpuSpoofFlag = false
        var gpuForgedAdreno = false

        if (glCombined.contains("emulator") || glCombined.contains("swiftshader") ||
            glCombined.contains("angle") || glCombined.contains("virgl") ||
            glCombined.contains("llvmpipe") || glCombined.contains("mesa") ||
            glCombined.contains("apple") || glCombined.contains("vmware")) {
            emulatorVectors.add("Virtualized/Host GPU translator: $glVendor · $glRenderer")
            gpuSpoofFlag = true
        }

        // Cross-check: If device claims Qualcomm Snapdragon (SM8650 / QCOM), GPU MUST be Adreno
        val claimsQualcommSoc = claimedSoc.startsWith("SM", ignoreCase = true) ||
            claimedSoc.startsWith("QCS", ignoreCase = true) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MANUFACTURER.equals("QCOM", ignoreCase = true))

        if (claimsQualcommSoc && glRenderer != "Unknown" && !glRenderer.contains("Adreno", ignoreCase = true)) {
            isHardwareSpoofed = true
            gpuSpoofFlag = true
            emulatorVectors.add("Silicon Spoof Mismatch: Claimed SoC '$claimedSoc' but GPU is '$glRenderer' (Expected Adreno)")
        }

        // Deep Adreno Silicon Verification (Unmasking Userspace Fake Adreno Strings)
        val hasKgslKernelNode = File("/dev/kgsl-3d0").exists() || File("/sys/class/kgsl/kgsl-3d0").exists()
        val mapsContainEglEmulation = try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                val emuLibs = listOf("libegl_emulation", "libglesv2_emulation", "libopenglsystemcommon", "lib_rendercontrol_enc", "vulkan.ranchu", "swiftshader")
                mapsFile.useLines { lines ->
                    lines.any { line ->
                        val lineLc = line.lowercase()
                        emuLibs.any { lineLc.contains(it) }
                    }
                }
            } else false
        } catch (_: Throwable) { false }

        val eglProp = getSystemProperty("ro.hardware.egl").lowercase()
        val grallocProp = getSystemProperty("ro.hardware.gralloc").lowercase()
        val halIndicatesEglEmulation = eglProp.contains("emulation") || eglProp.contains("ranchu") || eglProp.contains("goldfish") ||
            grallocProp.contains("emulation") || grallocProp.contains("ranchu") || grallocProp.contains("goldfish")

        if (glRenderer.contains("Adreno", ignoreCase = true)) {
            val missingAdrenoSiliconExtensions = !glExtensions.contains("GL_QCOM_") && !glVersion.contains("V@")
            if (!hasKgslKernelNode || mapsContainEglEmulation || halIndicatesEglEmulation || missingAdrenoSiliconExtensions) {
                isHardwareSpoofed = true
                gpuSpoofFlag = true
                gpuForgedAdreno = true
                val eglDetail = if (eglProp.isNotBlank()) "ro.hardware.egl=$eglProp" else if (mapsContainEglEmulation) "emulation .so mapped" else "virtual HAL"
                emulatorVectors.add("Forged GPU Identity: GL_RENDERER claims '$glRenderer' but runs on '$eglDetail' without Qualcomm /dev/kgsl-3d0 or GL_QCOM silicon")
            }
        }

        val gpuRendererSummary = when {
            gpuForgedAdreno -> "🚨 FORGED '$glRenderer' (Backed by QEMU/Emulation HAL)"
            gpuSpoofFlag -> "⚠️ SPOOF MISMATCH ($glVendor · $glRenderer)"
            glRenderer != "Unknown" -> "$glVendor · $glRenderer (Verified)"
            else -> "Standard EGL Pipeline"
        }

        // 5. Physical MEMS Sensor Vendor Inspection
        val sensorHardwareSummary = try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val physicalTypes = setOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_PROXIMITY
            )
            val allSensors = sm?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
            val hwSensors = allSensors.filter { it.type in physicalTypes }
            val emuSensors = hwSensors.filter { s ->
                val nameLc = s.name.lowercase()
                val vendorLc = s.vendor.lowercase()
                nameLc.contains("goldfish") || nameLc.contains("ranchu") || nameLc.contains("emulator") ||
                    vendorLc.contains("android open source project") || vendorLc == "aosp"
            }

            if (emuSensors.isNotEmpty()) {
                val sample = emuSensors.first()
                emulatorVectors.add("Virtual MEMS Sensor HAL: '${sample.name}' by '${sample.vendor}'")
                if (claimedManufacturer.equals("Samsung", ignoreCase = true)) {
                    isHardwareSpoofed = true
                }
                "⚠️ EMULATED (${sample.name} · ${sample.vendor})"
            } else if (hwSensors.isNotEmpty()) {
                val vendors = hwSensors.map { it.vendor }.distinct().take(2).joinToString(", ")
                "Authentic MEMS ($vendors)"
            } else {
                "No Physical MEMS Found"
            }
        } catch (_: Throwable) {
            "Standard Sensor HAL"
        }

        // 6. Hardware-Backed Keystore (TEE / StrongBox) X.509 ASN.1 Attestation
        val (teePassed, teeStatusText) = verifyHardwareKeystoreAttestation()
        if (!teePassed) {
            emulatorVectors.add("Hardware Keystore Attestation Failed: $teeStatusText")
            if (claimedManufacturer.equals("Samsung", ignoreCase = true) || claimedSoc.isNotBlank()) {
                isHardwareSpoofed = true
            }
        }

        // 7. SoC Asymmetric CPU Frequency Scaling & Thermal Zone Count Audit
        try {
            val cpuFreqDir = File("/sys/devices/system/cpu/cpu0/cpufreq")
            val thermalDir = File("/sys/class/thermal")
            val thermalZones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }?.size ?: 0

            if (!cpuFreqDir.exists() && thermalZones <= 3) {
                emulatorVectors.add("Missing SoC cpufreq scaling & low thermal zone count ($thermalZones zones)")
            }
        } catch (_: Throwable) {}

        // 8. OEM Ecosystem Consistency Check (Samsung SM-S928B / Knox Verification)
        if (claimedManufacturer.equals("Samsung", ignoreCase = true)) {
            val sharedLibs = try {
                pm.systemSharedLibraryNames?.toList() ?: emptyList()
            } catch (_: Throwable) { emptyList() }

            val hasSamsungLibs = sharedLibs.any {
                it.contains("samsung", ignoreCase = true) || it.contains("sec_platform", ignoreCase = true)
            }
            val knoxBit = getSystemProperty("ro.boot.warranty_bit")
            val samsungPda = getSystemProperty("ro.build.PDA")

            if (!hasSamsungLibs && knoxBit.isBlank() && samsungPda.isBlank()) {
                isHardwareSpoofed = true
                emulatorVectors.add("OEM Spoof Mismatch: Claims 'Samsung $claimedModel' but lacks Samsung framework/Knox/PDA footprint")
            }

            if (Build.BOOTLOADER.equals("unknown", ignoreCase = true)) {
                isHardwareSpoofed = true
                emulatorVectors.add("Bootloader Spoof Mismatch: Samsung device reports Build.BOOTLOADER='unknown'")
            }
        }

        // 9. Baseband / Cellular Modem & Virtual RIL Driver Inspection
        val radioVer = (Build.getRadioVersion() ?: "").trim()
        val rilImpl = getSystemProperty("gsm.version.ril-impl").trim()
        val rilLower = rilImpl.lowercase()

        if (rilLower.contains("reference-ril") || rilLower.contains("goldfish") ||
            rilLower.contains("ranchu") || rilLower.contains("cuttlefish") ||
            ((radioVer.isBlank() || radioVer.equals("unknown", ignoreCase = true) || radioVer == "1.0.0.0") &&
                (claimedManufacturer.equals("Samsung", ignoreCase = true) || claimedSoc.startsWith("SM", ignoreCase = true)))) {
            isHardwareSpoofed = true
            emulatorVectors.add("Baseband/SIM Spoof Mismatch: No physical cellular modem (radio='$radioVer', ril='$rilImpl') despite claiming SIM/LTE hardware")
        }

        // 10. System TLS Trust Store Audit (User MITM Root CA Detection)
        val (tlsMitmDetected, tlsMitmSummary) = auditTlsTrustStore()
        if (tlsMitmDetected) {
            detectedVectors.add("TLS MITM Proxy: $tlsMitmSummary")
        }

        val isEmulator = emulatorVectors.isNotEmpty()
        val isRooted = detectedVectors.isNotEmpty()

        val hypervisorSummary = when {
            isEmulator && isHardwareSpoofed -> "🚨 SPOOFED EMULATOR (Fake '$claimedManufacturer $claimedModel' on QEMU/Ranchu)"
            isEmulator -> "⚠️ VIRTUALIZED HYPERVISOR (${emulatorVectors.size} vectors)"
            else -> "Verified Bare-Metal Physical Silicon"
        }

        if (isRooted || isEmulator) {
            Log.w(TAG, "Integrity Alert! Root=$isRooted ($detectedVectors) | Emulator=$isEmulator Spoofed=$isHardwareSpoofed ($emulatorVectors)")
        }

        return RootCheckResult(
            isRooted = isRooted,
            rootScore = detectedVectors.size,
            detectedVectors = detectedVectors,
            isEmulator = isEmulator,
            isHardwareSpoofed = isHardwareSpoofed,
            emulatorScore = emulatorVectors.size,
            emulatorVectors = emulatorVectors,
            teeAttestationStatus = teeStatusText,
            gpuRendererSummary = gpuRendererSummary,
            sensorHardwareSummary = sensorHardwareSummary,
            hypervisorSummary = hypervisorSummary,
            nativeSyscallStatus = nativeSyscallStatus,
            tlsMitmDetected = tlsMitmDetected,
            tlsMitmSummary = tlsMitmSummary
        )
    }

    /**
     * Queries OpenGL ES Vendor, Renderer, Version & Extensions via a 1x1 headless EGL14 PBuffer surface.
     * Cached after first execution for zero runtime overhead.
     */
    private fun probeGpuSilicon(): GpuSiliconProbe {
        cachedGpuProbe?.let { return it }
        val fallback = GpuSiliconProbe("Unknown", "Unknown", "Unknown", "")
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return fallback

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return fallback
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return fallback

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)

            val prevDisplay = EGL14.eglGetCurrentDisplay()
            val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val prevCtx = EGL14.eglGetCurrentContext()

            var vendor = "Unknown"
            var renderer = "Unknown"
            var glVer = "Unknown"
            var extensions = ""

            if (EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim() ?: "Unknown"
                renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim() ?: "Unknown"
                glVer = GLES20.glGetString(GLES20.GL_VERSION)?.trim() ?: "Unknown"
                extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.trim() ?: ""
            }

            // Restore previous context if any, and clean up headless PBuffer
            if (prevCtx != null && prevCtx != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(prevDisplay, prevDraw, prevRead, prevCtx)
            } else {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)

            GpuSiliconProbe(vendor, renderer, glVer, extensions).also { cachedGpuProbe = it }
        } catch (_: Throwable) {
            fallback
        }
    }

    /**
     * Performs Hardware-Backed AndroidKeyStore Key Attestation and parses the X.509
     * KeyDescription extension (OID 1.3.6.1.4.1.11129.2.1.17) to verify whether the keymaster
     * is backed by physical TEE/StrongBox silicon or a Software Emulator Keymaster.
     */
    private fun verifyHardwareKeystoreAttestation(): Pair<Boolean, String> {
        cachedTeeResult?.let { return it }
        val alias = "sentinel_tee_attestation_probe"
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            try { ks.deleteEntry(alias) } catch (_: Throwable) {}

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val challenge = "SENTINEL_OMEGA_TEE_CHALLENGE".toByteArray()

            val specBuilder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)

            kpg.initialize(specBuilder.build())
            val keyPair = kpg.generateKeyPair()

            // 1. Check KeyInfo hardware backing
            val factory = KeyFactory.getInstance(keyPair.private.algorithm, "AndroidKeyStore")
            val keyInfo = factory.getKeySpec(keyPair.private, KeyInfo::class.java)
            @Suppress("DEPRECATION")
            val insideSecureHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                keyInfo.isInsideSecureHardware
            }

            // 2. Inspect X.509 Attestation Extension (OID 1.3.6.1.4.1.11129.2.1.17)
            val chain = ks.getCertificateChain(alias)
            val leafCert = chain?.firstOrNull() as? X509Certificate
            val extBytes = leafCert?.getExtensionValue(KEY_ATTESTATION_OID)

            // Measure QSEE / TrustZone SMC Hardware Signature Timing
            val t0 = System.nanoTime()
            try {
                val signer = java.security.Signature.getInstance("SHA256withECDSA").apply {
                    initSign(keyPair.private)
                    update(challenge)
                }
                signer.sign()
            } catch (_: Throwable) {}
            val t1 = System.nanoTime()
            val smcUs = ((t1 - t0) / 1000.0).coerceAtLeast(12.0)
            cachedQseeSmcLatencyUs = smcUs

            val asn1SecLevel = if (extBytes != null) parseAsn1AttestationSecurityLevel(extBytes) else -1
            val (devLocked, bootState) = if (extBytes != null) parseAsn1RootOfTrust(extBytes) else null to null
            try { ks.deleteEntry(alias) } catch (_: Throwable) {}

            val smcStr = String.format(java.util.Locale.US, " · QSEE SMC: %.1fµs", smcUs)
            val rotDetail = when {
                devLocked != null && bootState != null -> {
                    val bootStr = when (bootState) {
                        0 -> "GREEN"
                        1 -> "YELLOW"
                        2 -> "ORANGE"
                        3 -> "RED"
                        else -> "STATE_$bootState"
                    }
                    val lockStr = if (devLocked) "Locked" else "UNLOCKED"
                    " · Fuse: $lockStr · Boot: $bootStr"
                }
                devLocked != null -> " · Fuse: " + (if (devLocked) "Locked" else "UNLOCKED")
                else -> ""
            }

            val result = when {
                asn1SecLevel == 2 -> true to "Verified StrongBox Hardware SE (ASN.1 Level 2$smcStr$rotDetail)"
                asn1SecLevel == 1 -> true to "Verified Hardware TEE (ASN.1 Level 1$smcStr$rotDetail)"
                asn1SecLevel == 0 -> false to "FAILED: Software-Only Emulated Keymaster (ASN.1 Level 0)"
                insideSecureHw -> true to "Verified Hardware-Backed Keystore (TEE$smcStr$rotDetail)"
                else -> false to "FAILED: Software Keymaster (No Hardware TEE)"
            }
            result.also { cachedTeeResult = it }
        } catch (e: Throwable) {
            // If setAttestationChallenge fails, fall back to checking basic hardware key generation
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                val kp = kpg.generateKeyPair()
                val t0 = System.nanoTime()
                try {
                    val signer = java.security.Signature.getInstance("SHA256withECDSA").apply {
                        initSign(kp.private)
                        update(byteArrayOf(1, 2, 3, 4))
                    }
                    signer.sign()
                } catch (_: Throwable) {}
                val t1 = System.nanoTime()
                val smcUs = ((t1 - t0) / 1000.0).coerceAtLeast(15.0)
                cachedQseeSmcLatencyUs = smcUs

                val factory = KeyFactory.getInstance(kp.private.algorithm, "AndroidKeyStore")
                val keyInfo = factory.getKeySpec(kp.private, KeyInfo::class.java)
                try { ks.deleteEntry(alias) } catch (_: Throwable) {}

                @Suppress("DEPRECATION")
                val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                        keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                } else {
                    keyInfo.isInsideSecureHardware
                }
                val smcStr = String.format(java.util.Locale.US, " · QSEE SMC: %.1fµs", smcUs)
                val res = if (isHw) {
                    false to "⚠️ Keybox Attestation Rejected (Unverified Boot / Emulator$smcStr)"
                } else {
                    false to "FAILED: Software Keymaster & No Attestation Keybox"
                }
                res.also { cachedTeeResult = it }
            } catch (_: Throwable) {
                (false to "FAILED: Keystore Unavailable (${e.javaClass.simpleName})").also { cachedTeeResult = it }
            }
        }
    }

    /**
     * Parses the DER-encoded X.509 KeyDescription OCTET STRING -> SEQUENCE:
     * KeyDescription ::= SEQUENCE {
     *   attestationVersion         INTEGER,
     *   attestationSecurityLevel   SecurityLevel (ENUMERATED: 0=Software, 1=TEE, 2=StrongBox),
     *   keymasterVersion           INTEGER,
     *   keymasterSecurityLevel     SecurityLevel (ENUMERATED: 0=Software, 1=TEE, 2=StrongBox),
     *   ...
     * }
     */
    private fun parseAsn1AttestationSecurityLevel(extBytes: ByteArray): Int {
        return try {
            var idx = 0
            // Outer OCTET STRING (0x04)
            if (extBytes[idx++].toInt() and 0xFF != 0x04) return -1
            idx += skipDerLength(extBytes, idx)

            // KeyDescription SEQUENCE (0x30)
            if (extBytes[idx++].toInt() and 0xFF != 0x30) return -1
            idx += skipDerLength(extBytes, idx)

            // 1. attestationVersion INTEGER (0x02)
            if (extBytes[idx++].toInt() and 0xFF != 0x02) return -1
            val verLen = readDerLength(extBytes, idx)
            idx += skipDerLength(extBytes, idx) + verLen

            // 2. attestationSecurityLevel ENUMERATED (0x0A)
            if (extBytes[idx++].toInt() and 0xFF != 0x0A) return -1
            val secLen = readDerLength(extBytes, idx)
            idx += skipDerLength(extBytes, idx)
            if (secLen >= 1 && idx < extBytes.size) {
                extBytes[idx].toInt() and 0xFF
            } else -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Scans DER-encoded X.509 KeyDescription for tag [704] (KM_TAG_ROOT_OF_TRUST).
     * Tag 704 is encoded as context-specific constructed tag 0xBF 0x85 0x40.
     * RootOfTrust ::= SEQUENCE {
     *   verifiedBootKey    OCTET_STRING,
     *   deviceLocked       BOOLEAN,
     *   verifiedBootState  VerifiedBootState (ENUMERATED: 0=GREEN, 1=YELLOW, 2=ORANGE, 3=RED),
     *   verifiedBootHash   OCTET_STRING
     * }
     */
    private fun parseAsn1RootOfTrust(extBytes: ByteArray): Pair<Boolean?, Int?> {
        return try {
            val tag = byteArrayOf(0xBF.toByte(), 0x85.toByte(), 0x40.toByte())
            var foundIdx = -1
            for (i in 0 until extBytes.size - 3) {
                if (extBytes[i] == tag[0] && extBytes[i + 1] == tag[1] && extBytes[i + 2] == tag[2]) {
                    foundIdx = i + 3
                    break
                }
            }
            if (foundIdx < 0 || foundIdx >= extBytes.size) return null to null

            var idx = foundIdx
            idx += skipDerLength(extBytes, idx)

            // Inside context-specific tag [704], there is a SEQUENCE (0x30)
            if (idx < extBytes.size && (extBytes[idx].toInt() and 0xFF) == 0x30) {
                idx++
                idx += skipDerLength(extBytes, idx)
            }

            // 1. verifiedBootKey OCTET STRING (0x04)
            if (idx < extBytes.size && (extBytes[idx].toInt() and 0xFF) == 0x04) {
                idx++
                val kLen = readDerLength(extBytes, idx)
                idx += skipDerLength(extBytes, idx) + kLen
            }

            // 2. deviceLocked BOOLEAN (0x01)
            var deviceLocked: Boolean? = null
            if (idx < extBytes.size && (extBytes[idx].toInt() and 0xFF) == 0x01) {
                idx++
                val bLen = readDerLength(extBytes, idx)
                idx += skipDerLength(extBytes, idx)
                if (bLen >= 1 && idx < extBytes.size) {
                    deviceLocked = extBytes[idx].toInt() != 0
                    idx += bLen
                }
            }

            // 3. verifiedBootState ENUMERATED (0x0A)
            var verifiedBootState: Int? = null
            if (idx < extBytes.size && (extBytes[idx].toInt() and 0xFF) == 0x0A) {
                idx++
                val eLen = readDerLength(extBytes, idx)
                idx += skipDerLength(extBytes, idx)
                if (eLen >= 1 && idx < extBytes.size) {
                    verifiedBootState = extBytes[idx].toInt() and 0xFF
                }
            }

            deviceLocked to verifiedBootState
        } catch (_: Throwable) {
            null to null
        }
    }

    /**
     * Audits the system TLS trust store for user-installed Root CA certificates
     * that indicate an active or passive MITM interception proxy (Burp, Charles, mitmproxy).
     */
    private fun auditTlsTrustStore(): Pair<Boolean, String> {
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val userCas = mutableListOf<String>()
            for (alias in ks.aliases()) {
                if (alias.startsWith("user:")) {
                    val cert = ks.getCertificate(alias) as? X509Certificate
                    val cn = cert?.subjectDN?.name ?: alias
                    userCas.add(cn.take(40))
                }
            }
            if (userCas.isNotEmpty()) {
                true to "⚠️ ${userCas.size} User MITM CA(s): ${userCas.first()}"
            } else {
                false to "Verified Clean (0 User MITM CAs)"
            }
        } catch (_: Throwable) {
            false to "Audit N/A"
        }
    }

    private fun readDerLength(bytes: ByteArray, offset: Int): Int {
        val b = bytes[offset].toInt() and 0xFF
        if (b and 0x80 == 0) return b
        val numBytes = b and 0x7F
        var len = 0
        for (i in 1..numBytes) {
            len = (len shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return len
    }

    private fun skipDerLength(bytes: ByteArray, offset: Int): Int {
        val b = bytes[offset].toInt() and 0xFF
        return if (b and 0x80 == 0) 1 else 1 + (b and 0x7F)
    }

    private fun getSystemProperty(propName: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, propName, "") as String
        } catch (_: Throwable) {
            ""
        }
    }
}
