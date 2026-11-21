package com.kernelsu.intrudercam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream

class CaptureService : Service() {
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val outPath = intent?.getStringExtra("out_path") ?: return stopAndExit()
        bgThread = HandlerThread("IntruderCamBg").apply { start() }
        bgHandler = Handler(bgThread.looper)
        takeFrontPhoto(outPath)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val chId = "intruder_cam_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "System Helper", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, chId).setContentTitle("Security").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Security").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        }
        startForeground(101, notif)
    }

    private fun takeFrontPhoto(outPath: String) {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return stopAndExit().let {}

        reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                img.close()
                try {
                    File(outPath).parentFile?.mkdirs()
                    FileOutputStream(outPath).use { it.write(bytes) }
                } catch (_: Exception) {}
                stopAndExit()
            }, bgHandler)
        }

        try {
            mgr.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val surface = reader!!.surface
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            s.capture(req, null, bgHandler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { stopAndExit() }
                    }, bgHandler)
                }
                override fun onDisconnected(cam: CameraDevice) { stopAndExit() }
                override fun onError(cam: CameraDevice, error: Int) { stopAndExit() }
            }, bgHandler)
        } catch (_: SecurityException) {
            stopAndExit()
        }
    }

    private fun stopAndExit(): Int {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        if (::bgThread.isInitialized) bgThread.quitSafely()
        stopSelf()
        return START_NOT_STICKY
    }
}
