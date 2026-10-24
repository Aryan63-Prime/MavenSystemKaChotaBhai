package com.sentinel.host.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sentinel.host.domain.session.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class CameraCapturer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "Sentinel:Camera"
    }

    private val cameraMutex = Mutex()

    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(
        useFrontCamera: Boolean = false,
        streamId: java.util.UUID? = null
    ): Map<String, Any> = cameraMutex.withLock {
        if (!sessionManager.isEnrollmentApproved()) {
            Log.w(TAG, "Cannot capture photo: on-device enrollment approval not granted")
            return@withLock mapOf("success" to false, "error" to "On-device enrollment approval not granted")
        }
        val targetStreamId = if (streamId != null && streamId != com.sentinel.host.data.session.SessionManagerImpl.NIL_UUID) {
            streamId
        } else {
            java.util.UUID.randomUUID()
        }
        val permit = sessionManager.authorizeCapture(
            streamId = targetStreamId,
            operation = com.sentinel.host.domain.session.CaptureOperation.PHOTO_CAPTURE,
            durationSeconds = 30L
        )
        if (permit == null || !sessionManager.isCapturePermitted(targetStreamId, com.sentinel.host.domain.session.CaptureOperation.PHOTO_CAPTURE)) {
            Log.w(TAG, "Cannot capture photo: PHOTO_CAPTURE operation not permitted (streamId=$streamId)")
            return@withLock mapOf("success" to false, "error" to "Photo capture operation not permitted")
        }
        try {
            val result = withTimeoutOrNull(12000L) {
            suspendCancellableCoroutine<Map<String, Any>> { continuation ->
                try {
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val targetFacing = if (useFrontCamera) {
                        CameraCharacteristics.LENS_FACING_FRONT // 0 in Camera2 API
                    } else {
                        CameraCharacteristics.LENS_FACING_BACK // 1 in Camera2 API
                    }

                    Log.i(TAG, "Requested capture useFrontCamera=$useFrontCamera (targetFacing=$targetFacing)")
                    var selectedCameraId: String? = null

                    for (id in cameraManager.cameraIdList) {
                        val char = cameraManager.getCameraCharacteristics(id)
                        val facing = char.get(CameraCharacteristics.LENS_FACING)
                        val orient = char.get(CameraCharacteristics.SENSOR_ORIENTATION)
                        Log.i(TAG, "Available Camera2 ID '$id': facing=$facing (FRONT=0, BACK=1), orientation=$orient")

                        if (facing == targetFacing) {
                            selectedCameraId = id
                            break
                        }
                    }

                    if (selectedCameraId == null) {
                        selectedCameraId = if (useFrontCamera) {
                            cameraManager.cameraIdList.lastOrNull() ?: "0"
                        } else {
                            cameraManager.cameraIdList.firstOrNull() ?: "0"
                        }
                    }

                    val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    val actualFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: targetFacing
                    Log.i(TAG, "Final Selected Camera2 ID '$selectedCameraId' (facing=$actualFacing, sensorOrientation=$sensorOrientation)")

                    val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val jpegSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
                    val bestSize = jpegSizes
                        ?.filter { it.width <= 1920 && it.height <= 1080 }
                        ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: jpegSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                        ?: Size(1280, 720)
                    Log.i(TAG, "Selected Camera2 capture resolution: ${bestSize.width}x${bestSize.height}")

                    val cameraThread = HandlerThread("Sentinel-CameraThread").apply { start() }
                    val handler = Handler(cameraThread.looper)
                    var imageReader: ImageReader? = null
                    var cameraDevice: CameraDevice? = null
                    var previewSurface: Surface? = null
                    var dummyTexture: SurfaceTexture? = null

                    fun cleanup() {
                        try { imageReader?.close() } catch (_: Throwable) {}
                        try { previewSurface?.release() } catch (_: Throwable) {}
                        try { dummyTexture?.release() } catch (_: Throwable) {}
                        try { cameraDevice?.close() } catch (_: Throwable) {}
                        try { cameraThread.quitSafely() } catch (_: Throwable) {}
                    }

                    continuation.invokeOnCancellation {
                        cleanup()
                    }

                    val texture = SurfaceTexture(false).apply {
                        setDefaultBufferSize(640, 480)
                    }
                    dummyTexture = texture
                    val pSurface = Surface(texture)
                    previewSurface = pSurface

                    val reader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 2)
                    imageReader = reader

                    reader.setOnImageAvailableListener({ ir ->
                        Log.i(TAG, "Camera2 onImageAvailable triggered!")
                        val image = ir.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            var base64Image: String
                            try {
                                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                val matrix = Matrix()

                                val isFront = useFrontCamera || actualFacing == CameraCharacteristics.LENS_FACING_FRONT
                                val degrees = if (isFront) 270f else 90f
                                matrix.postRotate(degrees)

                                if (isFront) {
                                    matrix.postScale(-1f, 1f) // Mirror selfie picture
                                }

                                val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                                val baos = ByteArrayOutputStream()
                                rotated.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                                val finalBytes = baos.toByteArray()
                                base64Image = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                                Log.i(TAG, "Camera2 photo rotated upright ($degrees deg, isFront=$isFront)! bytes=${finalBytes.size}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to rotate Camera2 bitmap: ${e.message}", e)
                                base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            } finally {
                                cleanup()
                            }

                            if (continuation.isActive) {
                                continuation.resume(
                                    mapOf(
                                        "success" to true,
                                        "imageBase64" to base64Image,
                                        "cameraFacing" to if (useFrontCamera || actualFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "REAR"
                                    )
                                )
                            }
                        }
                    }, handler)

                    cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.i(TAG, "Camera2 device onOpened")
                            cameraDevice = camera
                            try {
                                val stillSurface = reader.surface
                                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(stillSurface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                }

                                camera.createCaptureSession(
                                    listOf(pSurface, stillSurface),
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {
                                            Log.i(TAG, "Camera2 session onConfigured, warming up 3A on preview surface")
                                            try {
                                                val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                    addTarget(pSurface)
                                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                }

                                                session.setRepeatingRequest(previewBuilder.build(), null, handler)

                                                // Allow 400ms for hardware AE/AF to converge on offscreen SurfaceTexture
                                                handler.postDelayed({
                                                    try {
                                                        session.stopRepeating()
                                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                                Log.e(TAG, "Camera2 capture failed: reason=${failure.reason}")
                                                                cleanup()
                                                                if (continuation.isActive) {
                                                                    continuation.resume(mapOf("success" to false, "error" to "Capture failed: ${failure.reason}"))
                                                                }
                                                            }
                                                        }, handler)
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Camera2 capture delayed exception: ${e.message}")
                                                        cleanup()
                                                        if (continuation.isActive) {
                                                            continuation.resume(mapOf("success" to false, "error" to (e.message ?: "Capture delayed error")))
                                                        }
                                                    }
                                                }, 400L)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Camera2 preview repeating exception: ${e.message}")
                                                cleanup()
                                                if (continuation.isActive) {
                                                    continuation.resume(mapOf("success" to false, "error" to "Preview failed: ${e.message}"))
                                                }
                                            }
                                        }

                                        override fun onConfigureFailed(session: CameraCaptureSession) {
                                            Log.e(TAG, "Camera2 session onConfigureFailed")
                                            cleanup()
                                            if (continuation.isActive) {
                                                continuation.resume(mapOf("success" to false, "error" to "Session config failed"))
                                            }
                                        }
                                    },
                                    handler
                                )
                            } catch (e: Exception) {
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resume(mapOf("success" to false, "error" to "Camera open exception: ${e.message}"))
                                }
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(mapOf("success" to false, "error" to "Camera disconnected"))
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Camera2 onError code: $error — will trigger Camera1 fallback")
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(mapOf("success" to false, "error" to "Camera2 error: $error"))
                            }
                        }
                    }, handler)

                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(mapOf("success" to false, "error" to "Camera exception: ${e.localizedMessage}"))
                    }
                }
            }
        }

            if (result != null && result["success"] == true) {
                return@withLock result
            }

            // Camera2 failed or timed out — fallback to Camera1 API
            Log.w(TAG, "Camera2 returned failure: ${result?.get("error")} — Executing Camera1 fallback (useFront=$useFrontCamera)")
            return@withLock capturePhotoCamera1(useFrontCamera)
        } finally {
            sessionManager.revokePermit(targetStreamId)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun capturePhotoCamera1(useFront: Boolean): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            var camera: Camera? = null
            var result = mapOf<String, Any>("success" to false, "error" to "Camera1 capture failed")
            val latch = CountDownLatch(1)

            try {
                val info = Camera.CameraInfo()
                val targetFacing = if (useFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
                var selectedId = -1
                val totalCameras = Camera.getNumberOfCameras()

                for (i in 0 until totalCameras) {
                    Camera.getCameraInfo(i, info)
                    Log.i(TAG, "Camera1 index $i: facing=${info.facing} (targetFacing=$targetFacing, 0=BACK, 1=FRONT), orientation=${info.orientation}")
                    if (info.facing == targetFacing) {
                        selectedId = i
                        break
                    }
                }
                if (selectedId == -1 && totalCameras > 0) {
                    selectedId = if (useFront) totalCameras - 1 else 0
                    Camera.getCameraInfo(selectedId, info)
                }
                if (selectedId == -1) {
                    return@withContext mapOf("success" to false, "error" to "No Camera1 available")
                }

                Log.i(TAG, "Opening Camera1 ID $selectedId for capture (facing=${info.facing})")
                camera = Camera.open(selectedId)
                val params = camera.parameters
                if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                } else if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                }
                if (params.supportedWhiteBalance?.contains(Camera.Parameters.WHITE_BALANCE_AUTO) == true) {
                    params.whiteBalance = Camera.Parameters.WHITE_BALANCE_AUTO
                }

                val bestSize = params.supportedPictureSizes
                    ?.filter { it.width <= 1920 && it.height <= 1080 }
                    ?.maxByOrNull { it.width * it.height }
                    ?: params.supportedPictureSizes?.maxByOrNull { it.width * it.height }

                if (bestSize != null) {
                    params.setPictureSize(bestSize.width, bestSize.height)
                }
                params.jpegQuality = 95
                camera.parameters = params

                val dummyTexture = SurfaceTexture(10)
                camera.setPreviewTexture(dummyTexture)
                camera.startPreview()

                // Allow 800ms for hardware auto-exposure (AE) and auto-focus (AF) to converge
                Thread.sleep(800)

                camera.takePicture(null, null) { data, _ ->
                    if (data != null && data.isNotEmpty()) {
                        try {
                            val original = BitmapFactory.decodeByteArray(data, 0, data.size)
                            val matrix = Matrix()

                            val isFrontCamera = useFront || info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                            val degrees = if (isFrontCamera) 270f else 90f
                            matrix.postRotate(degrees)

                            if (isFrontCamera) {
                                matrix.postScale(-1f, 1f)
                            }

                            val rotated = Bitmap.createBitmap(
                                original, 0, 0, original.width, original.height, matrix, true
                            )

                            val baos = ByteArrayOutputStream()
                            rotated.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                            val finalBytes = baos.toByteArray()
                            val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)

                            Log.i(TAG, "Camera1 capture succeeded & rotated upright ($degrees deg)! bytes=${finalBytes.size}")
                            result = mapOf(
                                "success" to true,
                                "imageBase64" to base64,
                                "cameraFacing" to if (isFrontCamera) "FRONT" else "REAR"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error rotating bitmap: ${e.message}", e)
                            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
                            result = mapOf(
                                "success" to true,
                                "imageBase64" to base64,
                                "cameraFacing" to if (useFront || info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) "FRONT" else "REAR"
                            )
                        }
                    } else {
                        result = mapOf("success" to false, "error" to "Camera1 empty picture data")
                    }
                    latch.countDown()
                }

                latch.await(4, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Camera1 exception: ${e.message}", e)
                result = mapOf("success" to false, "error" to "Camera1 exception: ${e.localizedMessage}")
            } finally {
                try {
                    camera?.stopPreview()
                    camera?.release()
                } catch (_: Exception) {}
            }

            result
        }
    }
}
