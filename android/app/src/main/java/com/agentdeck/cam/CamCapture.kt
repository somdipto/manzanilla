package com.agentdeck.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * One-shot JPEG capture from the front camera (the only camera on this desk
 * phone) using Camera2, no preview surface needed. Result is handed to the
 * callback and the camera fully released.
 */
class CamCapture(private val ctx: Context) {

    companion object { const val TAG_JPEG: Byte = 0x02 }

    private val bg = HandlerThread("cam").apply { start() }
    private val handler = Handler(bg.looper)

    @SuppressLint("MissingPermission")   // CAMERA checked by MainActivity
    fun snap(onJpeg: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = try {
            mgr.cameraIdList.firstOrNull() ?: run { onError("no camera"); return }
        } catch (e: Exception) { onError("cam list: ${e.message}"); return }

        val chars = mgr.getCameraCharacteristics(camId)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
        val size = sizes?.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) }
            ?: run { onError("no jpeg size"); return }

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        img.close()
                        cam.close(); reader.close()
                        onJpeg(bytes)
                    }, handler)

                    cam.createCaptureSession(listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                req.addTarget(reader.surface)
                                req.set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                sess.capture(req.build(), null, handler)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                cam.close(); reader.close(); onError("session failed")
                            }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); reader.close() }
                override fun onError(cam: CameraDevice, e: Int) {
                    cam.close(); reader.close(); onError("cam error $e")
                }
            }, handler)
        } catch (e: Exception) { onError("open: ${e.message}") }
    }
}
