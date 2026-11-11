package com.aaryan.airpoint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors

/** Watches the front camera and feeds hand landmarks to ControlService. Runs as a visible foreground service. */
class HandService : LifecycleService() {
    companion object { @Volatile var running = false; @Volatile var paused = false }

    private var landmarker: GestureRecognizer? = null
    private var faceLm: FaceLandmarker? = null; private var faceMode = false
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var screenOn = true
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn = i.action == Intent.ACTION_SCREEN_ON; if (!screenOn) ControlService.instance?.hide() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        super.onStartCommand(intent, flags, id)
        when (intent?.action) {
            "stop" -> { stopSelf(); return START_NOT_STICKY }
            "pause" -> { paused = !paused; if (paused) ControlService.instance?.hide() }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("air", "Hand control", NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, t: String) = Notification.Action.Builder(null, t,
            PendingIntent.getService(this, a.hashCode(), Intent(this, HandService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val n = Notification.Builder(this, "air").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(if (paused) "Hand control is paused" else "Hand control is on")
            .setContentText("The camera is watching for your hand. Nothing is recorded.").setOngoing(true)
            .addAction(action("pause", if (paused) "Resume" else "Pause")).addAction(action("stop", "Stop")).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(2, n)
        if (!running) { running = true; registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }); startCamera() }
        return START_NOT_STICKY
    }

    private fun buildRecognizer(delegate: Delegate): GestureRecognizer = GestureRecognizer.createFromOptions(this, GestureRecognizer.GestureRecognizerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").setDelegate(delegate).build())
        .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
        .setMinHandDetectionConfidence(0.4f).setMinHandPresenceConfidence(0.4f).setMinTrackingConfidence(0.4f)
        .setResultListener { r, _ ->
            val pose = r.gestures().firstOrNull()?.firstOrNull()
            ControlService.instance?.onHand(r.landmarks().firstOrNull(), if (pose != null && pose.score() >= 0.45f) pose.categoryName() else "")
        }.setErrorListener { }.build())

    private fun buildFace(delegate: Delegate): FaceLandmarker = FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(delegate).build())
        .setRunningMode(RunningMode.LIVE_STREAM).setNumFaces(1).setOutputFaceBlendshapes(true)
        .setResultListener { r, _ ->
            val bs = HashMap<String, Float>()
            r.faceBlendshapes().orElse(null)?.firstOrNull()?.forEach { bs[it.categoryName()] = it.score() }
            ControlService.instance?.onFace(r.faceLandmarks().firstOrNull(), bs)
        }.setErrorListener { }.build())

    private fun startCamera() {
        // GPU is faster, so try it first and fall back to the CPU if the phone refuses
        val mode = getSharedPreferences("air", 0).getString("mode", "hand")
        faceMode = mode == "face" || mode == "eyes"
        if (faceMode) faceLm = try { buildFace(Delegate.GPU) } catch (e: Exception) { buildFace(Delegate.CPU) }
        else landmarker = try { buildRecognizer(Delegate.GPU) } catch (e: Exception) { buildRecognizer(Delegate.CPU) }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val an = ImageAnalysis.Builder().setTargetResolution(android.util.Size(480, 640)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() // bigger picture: a small hand at arm's length is easier to find
            an.setAnalyzer(exec) { px ->
                try {
                    if (!paused && screenOn) {
                        // rotate upright and mirror, so moving your hand right moves the cursor right
                        val m = Matrix().apply { postRotate(px.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
                        val src = px.toBitmap()
                        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                        val img = BitmapImageBuilder(bmp).build(); val ts = SystemClock.uptimeMillis()
                        if (faceMode) faceLm?.detectAsync(img, ts) else landmarker?.recognizeAsync(img, ts)
                    }
                } catch (e: Exception) {} finally { px.close() }
            }
            f.get().unbindAll(); f.get().bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, an)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        running = false; paused = false
        try { unregisterReceiver(screen) } catch (e: Exception) {}
        try { landmarker?.close() } catch (e: Exception) {}
        try { faceLm?.close() } catch (e: Exception) {}
        exec.shutdown(); ControlService.instance?.hide()
        super.onDestroy()
    }
}
