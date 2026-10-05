package com.agentdeck.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Local-only face position and smile tracking. Frames are never stored or transmitted. */
class FaceTracker(private val ctx: Context) {
    data class Target(
        val x: Float,
        val y: Float,
        val size: Float,
        val smiling: Boolean,
        val gazeFeatures: FloatArray = floatArrayOf(),
        val lookingAtDevice: Boolean = false,
        val gazeConfidence: Float = 0f
    )
    data class Gesture(
        val handRaised: Boolean,
        val fingers: Int,
        val rawFingers: Int = fingers,
        val confidence: Float = 0f,
        val features: FloatArray = floatArrayOf()
    )

    private val thread = HandlerThread("manni-face-tracker").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(.14f)
            .enableTracking()
            .build()
    )
    private val gestureRecognizer = GestureRecognizer.createFromOptions(
        ctx,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .setMinHandDetectionConfidence(.5f)
            .setMinHandPresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f)
            .build()
    )
    private val faceLandmarker = FaceLandmarker.createFromOptions(
        ctx,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(.5f)
            .setMinFacePresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f)
            .build()
    )
    private var frameNumber = 0L
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private val calibration = HashMap<Int, FloatArray>()
    private val gazeCalibration = HashMap<String, FloatArray>()
    @Volatile private var lastGazeFeatures = FloatArray(0)
    @Volatile private var lastLookingAtDevice = false
    @Volatile private var lastGazeConfidence = 0f

    init {
        val prefs = ctx.getSharedPreferences("pet_gesture_calibration", Context.MODE_PRIVATE)
        for (label in 1..3) {
            val values = prefs.getString("mp_gesture_$label", null)
                ?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            if (values != null && values.size == 63) calibration[label] = values
        }
        for (kind in listOf("device", "laptop")) {
            val values = prefs.getString("gaze_$kind", null)
                ?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            if (values != null && values.size == 5) gazeCalibration[kind] = values
        }
    }

    fun saveCalibration(label: Int, features: FloatArray): Boolean {
        if (label !in 1..3 || features.size != 63) return false
        calibration[label] = features.copyOf()
        ctx.getSharedPreferences("pet_gesture_calibration", Context.MODE_PRIVATE).edit()
            .putString("mp_gesture_$label", features.joinToString(","))
            .apply()
        return true
    }

    fun isCalibrated(label: Int): Boolean = calibration.containsKey(label)

    fun saveGazeCalibration(kind: String, features: FloatArray): Boolean {
        if (kind !in listOf("device", "laptop") || features.size != 5) return false
        gazeCalibration[kind] = features.copyOf()
        ctx.getSharedPreferences("pet_gesture_calibration", Context.MODE_PRIVATE).edit()
            .putString("gaze_$kind", features.joinToString(","))
            .apply()
        classifyGaze(features)
        return true
    }

    fun isGazeCalibrated(kind: String): Boolean = gazeCalibration.containsKey(kind)

    private fun classifyGaze(features: FloatArray): Pair<Boolean, Float> {
        val device = gazeCalibration["device"]
        val laptop = gazeCalibration["laptop"]
        if (device == null || laptop == null || features.size != 5) return false to 0f
        // Project the live pose onto the exact direction separating the user's
        // two taught positions. Eye/blink motion perpendicular to that direction
        // is ignored, making this much steadier than nearest-neighbour distance.
        var numerator = 0f
        var denominator = 0f
        for (i in features.indices) {
            val axis = device[i] - laptop[i]
            numerator += (features[i] - laptop[i]) * axis
            denominator += axis * axis
        }
        if (denominator < .00001f) return false to 0f
        val position = numerator / denominator // 0 = laptop, 1 = Manzanilla
        val confidence = (kotlin.math.abs(position - .5f) * 2f).coerceIn(0f, 1f)
        // Deliberately biased toward OFF: uncertain/middle glances cannot arm commands.
        return (position >= .68f) to confidence
    }

    private fun projection(pointX: Float, pointY: Float, ax: Float, ay: Float,
                           bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return ((pointX - ax) * dx + (pointY - ay) * dy) /
            (dx * dx + dy * dy).coerceAtLeast(.000001f)
    }

    private fun readGaze(bitmap: Bitmap) {
        try {
            val image = BitmapImageBuilder(bitmap).build()
            val face = faceLandmarker.detect(image).faceLandmarks().firstOrNull()
            if (face == null || face.size < 478) {
                lastGazeFeatures = FloatArray(0)
                lastLookingAtDevice = false
                lastGazeConfidence = 0f
                image.close()
                return
            }
            fun average(from: Int, to: Int): Pair<Float, Float> {
                var x = 0f; var y = 0f
                for (i in from..to) { x += face[i].x(); y += face[i].y() }
                val n = (to - from + 1).toFloat()
                return x / n to y / n
            }
            val leftIris = average(468, 472)
            val rightIris = average(473, 477)
            val leftH = projection(leftIris.first, leftIris.second,
                face[33].x(), face[33].y(), face[133].x(), face[133].y())
            val leftV = projection(leftIris.first, leftIris.second,
                face[159].x(), face[159].y(), face[145].x(), face[145].y())
            val rightH = projection(rightIris.first, rightIris.second,
                face[362].x(), face[362].y(), face[263].x(), face[263].y())
            val rightV = projection(rightIris.first, rightIris.second,
                face[386].x(), face[386].y(), face[374].x(), face[374].y())
            val eyeCenterX = (leftIris.first + rightIris.first) * .5f
            val eyeDistance = kotlin.math.abs(rightIris.first - leftIris.first).coerceAtLeast(.02f)
            val yaw = (face[1].x() - eyeCenterX) / eyeDistance
            val features = floatArrayOf(leftH, leftV, rightH, rightV, yaw)
            val classified = classifyGaze(features)
            lastGazeFeatures = features
            lastLookingAtDevice = classified.first
            lastGazeConfidence = classified.second
            image.close()
        } catch (_: Exception) {
            lastGazeFeatures = FloatArray(0)
            lastLookingAtDevice = false
            lastGazeConfidence = 0f
        }
    }

    private fun classify(features: FloatArray, rawCount: Int): Pair<Int, Float> {
        if ((1..3).any { !calibration.containsKey(it) } || features.size != 63)
            return rawCount to .35f
        var bestLabel = rawCount.coerceIn(1, 3)
        var bestDistance = Float.MAX_VALUE
        for ((label, sample) in calibration) {
            var sum = 0f
            for (i in features.indices) {
                val d = features[i] - sample[i]
                sum += d * d
            }
            val distance = sqrt(sum)
            if (distance < bestDistance) {
                bestDistance = distance
                bestLabel = label
            }
        }
        return bestLabel to (1f / (1f + bestDistance * 5f)).coerceIn(0f, 1f)
    }

    @SuppressLint("MissingPermission")
    fun start(onTarget: (Target?) -> Unit, onGesture: (Gesture) -> Unit,
              onCapability: (Boolean) -> Unit,
              previewRequested: () -> Boolean = { false },
              onPreview: (Bitmap) -> Unit = {}) {
        if (running) return
        running = true
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                running = false; onCapability(false); return
            }
            val chars = manager.getCameraCharacteristics(id)
            val mirrorPreview = chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
            val size = sizes?.minByOrNull { abs(it.width * it.height - 480 * 360) }
                ?: run { running = false; onCapability(false); return }
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { r ->
                r.setOnImageAvailableListener({ source ->
                    val mediaImage = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (!running || !busy.compareAndSet(false, true)) { mediaImage.close(); return@setOnImageAvailableListener }
                    val input = InputImage.fromMediaImage(mediaImage, sensorOrientation)
                    val rotatedW = if (sensorOrientation % 180 == 0) mediaImage.width else mediaImage.height
                    val rotatedH = if (sensorOrientation % 180 == 0) mediaImage.height else mediaImage.width
                    frameNumber++
                    if (previewRequested() && frameNumber % 3L == 0L && frameNumber % 4L != 0L) {
                        makePreview(mediaImage, sensorOrientation, mirrorPreview)?.let(onPreview)
                    }
                    val analyzeHand = frameNumber % 4L == 0L
                    if (analyzeHand) {
                        try {
                            val bitmap = makePreview(mediaImage, sensorOrientation, mirrorPreview)
                            if (bitmap == null) {
                                onGesture(Gesture(false, 0))
                            } else {
                                if (previewRequested()) onPreview(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                                val mpImage = BitmapImageBuilder(bitmap).build()
                                val result = gestureRecognizer.recognize(mpImage)
                                val hand = result.landmarks().firstOrNull()
                                if (hand == null || hand.size < 21) {
                                    onGesture(Gesture(false, 0))
                                } else {
                                    val wrist = hand[0]
                                    val middleMcp = hand[9]
                                    val dx = middleMcp.x() - wrist.x()
                                    val dy = middleMcp.y() - wrist.y()
                                    val scale = sqrt(dx * dx + dy * dy).coerceAtLeast(.045f)
                                    val featureValues = FloatArray(63)
                                    hand.take(21).forEachIndexed { i, point ->
                                        featureValues[i * 3] = (point.x() - wrist.x()) / scale
                                        featureValues[i * 3 + 1] = (point.y() - wrist.y()) / scale
                                        featureValues[i * 3 + 2] = (point.z() - wrist.z()) / scale
                                    }
                                    var rawCount = 0
                                    if (hand[8].y() < hand[6].y() - .012f) rawCount++
                                    if (hand[12].y() < hand[10].y() - .012f) rawCount++
                                    if (hand[16].y() < hand[14].y() - .010f) rawCount++
                                    if (hand[20].y() < hand[18].y() - .008f) rawCount++
                                    // Number commands intentionally ignore the thumb. This makes
                                    // an open palm a distinct 4-finger WAVE instead of a false 3.
                                    val raw = rawCount.coerceIn(0, 4)
                                    val classified = if (raw in 1..3)
                                        classify(featureValues, raw) else raw to .85f
                                    onGesture(Gesture(true, classified.first, raw, classified.second, featureValues))
                                }
                                mpImage.close()
                                bitmap.recycle()
                            }
                        } catch (_: Exception) {
                            onGesture(Gesture(false, 0))
                        } finally {
                            mediaImage.close()
                            busy.set(false)
                        }
                    } else {
                        if (frameNumber % 8L == 2L) {
                            makePreview(mediaImage, sensorOrientation, mirrorPreview)?.let { bitmap ->
                                readGaze(bitmap)
                                bitmap.recycle()
                            }
                        }
                        detector.process(input)
                            .addOnSuccessListener { faces ->
                                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                                if (face == null) onTarget(null) else {
                                    val cx = face.boundingBox.exactCenterX() / rotatedW.toFloat()
                                    val cy = face.boundingBox.exactCenterY() / rotatedH.toFloat()
                                    val x = ((.5f - cx) * 2f).coerceIn(-1f, 1f)
                                    val y = ((cy - .5f) * 2f).coerceIn(-1f, 1f)
                                    val scale = (face.boundingBox.width().toFloat() / rotatedW.toFloat()).coerceIn(0f, 1f)
                                    onTarget(Target(x, y, scale, (face.smilingProbability ?: 0f) > .62f,
                                        lastGazeFeatures.copyOf(), lastLookingAtDevice, lastGazeConfidence))
                                }
                            }
                            .addOnFailureListener { onCapability(false) }
                            .addOnCompleteListener { mediaImage.close(); busy.set(false) }
                    }
                }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) { device.close(); return }
                    camera = device
                    device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (!running) { s.close(); return }
                            session = s
                            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            b.addTarget(reader!!.surface)
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            s.setRepeatingRequest(b.build(), null, handler)
                            onCapability(true)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { onCapability(false); stop() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); stop() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); onCapability(false); stop() }
            }, handler)
        } catch (_: Exception) { running = false; onCapability(false) }
    }

    private fun makePreview(image: Image, rotation: Int, mirror: Boolean): Bitmap? {
        return try {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        var out = 0
        for (row in 0 until height) {
            val rowBase = row * yPlane.rowStride
            for (col in 0 until width) nv21[out++] = yBuffer.get(rowBase + col * yPlane.pixelStride)
        }
        for (row in 0 until height / 2) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                nv21[out++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                nv21[out++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }
        val bytes = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 72, bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size()) ?: return null
        if (rotation == 0 && !mirror) decoded else {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        } catch (_: Exception) { null }
    }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
    }

    fun release() { stop(); detector.close(); gestureRecognizer.close(); faceLandmarker.close(); thread.quitSafely() }
}
