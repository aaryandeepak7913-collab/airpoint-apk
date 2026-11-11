package com.aaryan.airpoint

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.sin

/** Nine dots: look at each one while the camera records your eyes. Then the app learns your eyes-to-screen mapping. */
@Suppress("DEPRECATION")
class CalibrateActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var view: Dots
    private val pts = arrayOf(0.1f to 0.12f, 0.5f to 0.12f, 0.9f to 0.12f, 0.1f to 0.5f, 0.5f to 0.5f, 0.9f to 0.5f, 0.1f to 0.88f, 0.5f to 0.88f, 0.9f to 0.88f)
    private var i = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        view = Dots(this); setContentView(view)
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || !acc) { Toast.makeText(this, "Allow the camera and turn on Accessibility first", Toast.LENGTH_LONG).show(); finish(); return }
        getSharedPreferences("air", 0).edit().putString("mode", "eyes").apply()
        synchronized(ControlService.samples) { ControlService.samples.clear() }
        // restart the camera so it is running the face model
        val wasRunning = HandService.running
        if (wasRunning) stopService(Intent(this, HandService::class.java))
        h.postDelayed({ startForegroundService(Intent(this, HandService::class.java)) }, if (wasRunning) 700 else 250)
        view.msg = "Sit still, hold the phone steady, and follow each dot with your eyes only. Keep your head still."
        h.postDelayed({ next() }, 4500)
    }

    private fun next() {
        if (i >= pts.size) { finishUp(); return }
        val x = pts[i].first * view.width; val y = pts[i].second * view.height
        view.set(x, y, "${i + 1} of ${pts.size}")
        ControlService.calTx = -1f
        h.postDelayed({ ControlService.calTx = x; ControlService.calTy = y }, 800)   // give your eyes time to arrive, then record
        h.postDelayed({ ControlService.calTx = -1f; i++; next() }, 1900)
    }

    private fun finishUp() {
        view.set(-1f, -1f, "Working it out...")
        val res = synchronized(ControlService.samples) { Gaze.fit(ArrayList(ControlService.samples)) }
        if (res == null) view.msg = "Not enough data. Is your face visible to the front camera? Try again."
        else {
            Gaze.save(getSharedPreferences("air", 0), res.first); ControlService.instance?.reloadGaze()
            val cm = res.second / (resources.displayMetrics.xdpi / 2.54f)
            view.msg = "Done. Typical error is about " + String.format("%.1f", cm) + " cm on your own data."
        }
        h.postDelayed({ finish() }, 3000)
    }

    @Deprecated("Back cancels the calibration")
    override fun onBackPressed() { ControlService.calTx = -1f; h.removeCallbacksAndMessages(null); finish() }
    override fun onDestroy() { ControlService.calTx = -1f; h.removeCallbacksAndMessages(null); super.onDestroy() }
}

private class Dots(c: Context) : View(c) {
    private var dotX = -1f; private var dotY = -1f; var msg = ""
    private val t0 = SystemClock.uptimeMillis()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF00E5FF.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8F4FF.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER }
    fun set(nx: Float, ny: Float, m: String) { dotX = nx; dotY = ny; msg = m; invalidate() }
    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF00070D.toInt())
        if (dotX >= 0f) { val p = 0.5f + 0.5f * sin((SystemClock.uptimeMillis() - t0) / 150f); c.drawCircle(dotX, dotY, 26f + 10f * p, ring); c.drawCircle(dotX, dotY, 11f, dot) }
        val words = msg.split(" "); var line = ""; var yy = height * 0.45f
        for (w in words) { if (txt.measureText("$line $w") > width * 0.85f) { c.drawText(line, width / 2f, yy, txt); yy += 54f; line = w } else line = if (line.isEmpty()) w else "$line $w" }
        c.drawText(line, width / 2f, yy, txt)
        postInvalidateOnAnimation()
    }
}
