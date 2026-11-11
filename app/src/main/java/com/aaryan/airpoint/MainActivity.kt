package com.aaryan.airpoint

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private var betaMenuOpen = false
    private var titleTaps = 0
    private var lastTitleTap = 0L
    private val bg = 0xFF00070D.toInt(); private val cyan = 0xFF00E5FF.toInt(); private val fg = 0xFFE8F6FA.toInt(); private val dim = 0xFF7F9AA6.toInt()

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i) }
    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("start", false)) { // opened from the Quick Settings tile
            intent.removeExtra("start")
            if (camOn() && accOn() && !HandService.running) startForegroundService(Intent(this, HandService::class.java)) else if (!HandService.running) toast("Finish the setup first")
        }
        build()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun camOn() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun accOn() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true
    private fun label(t: String, sz: Float = 15f, c: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(3), 0, dp(3)) }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply { setColor(0xFF08161F.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF12384A.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) } }
    private fun btn(t: String, primary: Boolean = false, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(if (primary) 0xFF00212B.toInt() else fg)
        background = GradientDrawable().apply { setColor(if (primary) cyan else 0xFF11303D.toInt()); cornerRadius = dp(14).toFloat() }
        setOnClickListener { on() }; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) } }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(52), dp(18), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        val brand = label("AIRPOINT", 30f, cyan, true)
        brand.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTitleTap > 1800L) titleTaps = 0
            lastTitleTap = now
            titleTaps++
            if (titleTaps >= 7) {
                betaMenuOpen = !betaMenuOpen
                titleTaps = 0
                toast(if (betaMenuOpen) "Experimental features unlocked" else "Experimental menu hidden")
                build()
            }
        }
        root.addView(brand); root.addView(label("Control your phone with your hands or your face", 14f, dim))

        val on = HandService.running
        val c = card(); c.addView(label(if (on) (if (HandService.paused) "AIRPOINT IS PAUSED" else "AIRPOINT IS ON") else "AIRPOINT IS OFF", 20f, if (on && !HandService.paused) cyan else fg, true))
        c.addView(label("Hold your phone upright with the front camera facing you, about an arm's length away.", 13f, dim))
        c.addView(btn(if (on) "Stop hand control" else "Start hand control", !on) {
            if (on) stopService(Intent(this, HandService::class.java))
            else if (!camOn()) toast("Allow the camera first")
            else if (!accOn()) toast("Turn on Accessibility first")
            else startForegroundService(Intent(this, HandService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ build() }, 500) })
        root.addView(c)

        val ap0 = getSharedPreferences("air", 0); val curMode = ap0.getString("mode", "hand"); val eyes = curMode == "eyes"; val face = curMode == "face" || eyes
        val mc = card(); mc.addView(label("Control mode", 16f, fg, true))
        mc.addView(label(if (eyes) "Eyes (experimental): the cursor follows where you look. Needs a short calibration." else if (face) "Face: move your head to steer, open your mouth to click. Hold the phone about 40 cm from your face." else "Hand: hold the phone upright, about an arm's length away.", 13f, dim))
        val modes = LinearLayout(this)
        fun mode(t: String, key: String) { modes.addView(btn(t, key == curMode) {
            ap0.edit().putString("mode", key).apply()
            if (HandService.running) { stopService(Intent(this, HandService::class.java)); Handler(Looper.getMainLooper()).postDelayed({ startForegroundService(Intent(this, HandService::class.java)) }, 700) }
            Handler(Looper.getMainLooper()).postDelayed({ build() }, 900) }.also { }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { topMargin = dp(10); marginEnd = dp(6) } }) }
        mode("Hand", "hand"); mode("Face", "face")
        mc.addView(modes); root.addView(mc)
        if (betaMenuOpen) {
            val ec = card(); ec.addView(label("EXPERIMENTAL LAB", 17f, cyan, true))
            ec.addView(label("Beta features can be inaccurate and may behave unpredictably. Eye tracking uses the front camera and runs on-device.", 13f, dim))
            val eyeEnabled = ap0.getString("mode", "hand") == "eyes"
            ec.addView(Switch(this).apply {
                text = "Enable eye control (beta)"; textSize = 14f; setTextColor(fg)
                isChecked = eyeEnabled
                setOnCheckedChangeListener { _, enabled ->
                    ap0.edit().putString("mode", if (enabled) "eyes" else "hand").apply()
                    if (HandService.running) {
                        stopService(Intent(this@MainActivity, HandService::class.java))
                        Handler(Looper.getMainLooper()).postDelayed({ startForegroundService(Intent(this@MainActivity, HandService::class.java)); build() }, 700)
                    } else build()
                }
            })
            val calibrated = ap0.getString("gaze", null) != null
            ec.addView(label(if (calibrated) "Calibration saved. Recalibrate if tracking drifts or lighting/phone position changes." else "Not calibrated. Complete the nine-point calibration before using gaze to move the cursor.", 13f, dim))
            ec.addView(btn(if (calibrated) "Calibrate / recalibrate eyes" else "Start nine-point calibration", !calibrated) {
                if (!camOn()) toast("Allow the camera first")
                else if (!accOn()) toast("Turn on Accessibility first")
                else {
                    ap0.edit().putString("mode", "eyes").apply()
                    val wasRunning = HandService.running
                    if (wasRunning) stopService(Intent(this, HandService::class.java))
                    Handler(Looper.getMainLooper()).postDelayed({ startActivity(Intent(this, CalibrateActivity::class.java)); build() }, if (wasRunning) 700 else 0)
                }
            })
            ec.addView(label("How it works: MediaPipe Face Landmarker tracks iris and eye landmarks; nine calibration targets train a personal gaze-to-screen mapping. Look at a target, then dwell or use facial gestures to click and navigate. Accuracy depends on lighting, camera angle, glasses and head movement.", 12f, dim))
            root.addView(ec)
        }

        val s = card(); s.addView(label("Setup", 16f, fg, true))
        fun row(ok: Boolean, t: String, fix: () -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            r.addView(TextView(this).apply { text = (if (ok) "✓  " else "✗  ") + t; textSize = 14f; setTextColor(if (ok) 0xFF7DE08A.toInt() else 0xFFFF6B6B.toInt()); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            if (!ok) r.addView(Button(this).apply { text = "Fix"; isAllCaps = false; setOnClickListener { fix() } })
            s.addView(r) }
        row(camOn(), "Camera allowed") { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
        row(accOn(), "Accessibility on (lets Airpoint tap and scroll for you)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 33) row(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Notifications allowed (shows the Pause button)") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
        s.addView(label("On Android 13 and newer, a sideloaded app is blocked from Accessibility until you open Settings, Apps, Airpoint, the three dots, then Allow restricted settings.", 12f, dim))
        root.addView(s)

        val ap = getSharedPreferences("air", 0)
        val tune = card(); tune.addView(label("Settings", 16f, fg, true))
        tune.addView(Switch(this).apply { text = "Show live hand preview"; textSize = 14f; setTextColor(fg); setPadding(0, dp(8), 0, dp(8)); isChecked = ap.getBoolean("preview", true)
            setOnCheckedChangeListener { _, v -> ap.edit().putBoolean("preview", v).apply() } })
        fun slider(title: String, key: String, max: Int, def: Int, show: (Int) -> String) {
            val t = label(title + ": " + show(ap.getInt(key, def)), 14f, fg); tune.addView(t)
            tune.addView(SeekBar(this).apply { this.max = max; progress = ap.getInt(key, def)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { ap.edit().putInt(key, p).apply(); t.text = title + ": " + show(p) }
                    override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {} }) })
        }
        slider("Cursor speed", "speed", 4, 2) { listOf("Slowest", "Slow", "Normal", "Fast", "Fastest")[it] }
        slider("Face cursor speed", "fspeed", 4, 2) { listOf("Slowest", "Slow", "Normal", "Fast", "Fastest")[it] }
        slider("Scroll speed (open hand or eyebrows)", "sspeed", 4, 2) { listOf("Slowest", "Slow", "Normal", "Fast", "Fastest")[it] }
        slider("Cursor smoothing", "smooth", 4, 2) { listOf("Very quick", "Quick", "Balanced", "Smooth", "Very smooth")[it] }
        slider("Hold time for Back, Home and Recents", "hold", 2, 1) { listOf("Quick", "Normal", "Slow")[it] }
        slider("Dwell click (hold still to click)", "dwell", 4, 4) { listOf("Off", "1 second", "1.5 seconds", "2 seconds", "3 seconds")[it] }
        tune.addView(Switch(this).apply { text = "Scroll when the cursor is at the top or bottom"; textSize = 14f; setTextColor(fg); setPadding(0, dp(8), 0, dp(8)); isChecked = ap.getBoolean("edge", true)
            setOnCheckedChangeListener { _, v -> ap.edit().putBoolean("edge", v).apply() } })
        root.addView(tune)

        val g = card(); g.addView(label("Gestures", 16f, fg, true))
        (if (eyes) listOf("Look at the spot you want" to "moves the cursor (calibrate first)", "Open your mouth" to "click (hold = long press)", "Keep your eyes still on a spot" to "clicks (the ring fills first)",
            "Raise your eyebrows, then nod up or down" to "scrolls", "Smile, hold" to "Back", "Pucker your lips, hold" to "Home", "Stick out your tongue, hold" to "Recents")
        else if (face) listOf("Turn or nod your head a little" to "moves the cursor", "Open your mouth" to "click (hold = long press, move = drag)", "Raise your eyebrows, then nod up or down" to "scrolls",
            "Smile, hold" to "Back", "Pucker your lips, hold" to "Home", "Stick out your tongue, hold" to "Recents", "Keep the cursor still" to "clicks (the ring fills first)", "Cursor at the screen edge" to "scrolls")
        else listOf("Point with one finger, move your hand" to "moves the cursor like a mouse", "Make a fist right after pointing" to "click (hold = long press, move = drag)",
            "Open hand above or below where it opened" to "scrolls, farther = faster", "Peace sign, hold" to "Back", "Thumbs up, hold" to "Home", "Thumbs down, hold" to "Recents",
            "I-love-you sign (thumb, index, little finger), hold" to "play / pause", "Pinch, then turn your hand like a dial" to "volume", "Hold the cursor still" to "clicks (the ring fills first)",
            "Cursor at the top or bottom edge" to "scrolls")).forEach { (a, b) -> g.addView(label("$a  →  $b", 14f, fg)) }
        root.addView(g)
        root.addView(label("The camera is used only on your phone. Nothing is recorded or sent anywhere. It pauses when the screen is off, and the camera indicator shows whenever it runs.", 12f, dim).apply { setPadding(0, dp(18), 0, 0) })
    }
}
