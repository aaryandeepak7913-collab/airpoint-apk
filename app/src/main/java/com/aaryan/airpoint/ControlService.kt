package com.aaryan.airpoint

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

/** Smooths the cursor: steady when you hold still, quick when you move. */
class OneEuro(private val minCutoff: Float, private val beta: Float = 0.01f) {
    private var x = 0f; private var dx = 0f; private var t = 0L; private var ready = false
    private fun a(cut: Float, dt: Float) = 1f / (1f + (1f / (2f * Math.PI.toFloat() * cut)) / dt)
    fun reset() { ready = false }
    fun f(v: Float, now: Long): Float {
        if (!ready) { x = v; t = now; ready = true; dx = 0f; return v }
        val dt = ((now - t) / 1000f).coerceAtLeast(0.001f); t = now
        dx += a(1f, dt) * ((v - x) / dt - dx)
        x += a(minCutoff + beta * abs(dx), dt) * (v - x)
        return x
    }
}

/**
 * How it works now:
 *  - Point (index finger up): move your hand like a mouse. The cursor is relative, so you can lift off and re-aim.
 *  - Make a fist right after pointing: that's the mouse button. Quick = click, hold = long press, move while holding = drag.
 *  - Open hand: scroll like a joystick. Hand above where it opened scrolls down, below scrolls up, farther = faster.
 *  - Peace sign hold = Back, thumbs up hold = Home, thumbs down hold = Recents, rock sign hold = play / pause.
 *  - Pinch and turn your hand = volume.
 * The pose names come from MediaPipe's trained gesture model, which is far steadier than hand-written finger maths.
 */
class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
        @Volatile var calTx = -1f; @Volatile var calTy = -1f          // while calibrating eye tracking: the dot you are looking at
        val samples = ArrayList<Pair<FloatArray, FloatArray>>()
    }
    private var gazeModel: Gaze.Model? = null
    private var gfx = OneEuro(0.8f, 0.005f); private var gfy = OneEuro(0.8f, 0.005f); private var lastGS = -1
    fun reloadGaze() { gazeModel = Gaze.load(prefs) }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: Overlay? = null
    private val prefs by lazy { getSharedPreferences("air", 0) }
    private var fx = OneEuro(1.5f, 0.02f); private var fy = OneEuro(1.5f, 0.02f); private var lastSmooth = -1

    private var cx = 0f; private var cy = 0f; private var haveCursor = false
    private var tracking = false; private var lastPx = 0f; private var lastPy = 0f; private var lastT = 0L
    private var candG = ""; private var candN = 0; private var stableG = ""; private var since = 0L; private var fired = false; private var cool = 0L
    private var lastPointAt = 0L; private var gripping = false; private var gStart = 0L; private var gX = 0f; private var gY = 0f
    private var palmRef = -1f; private var palmSince = 0L; private var scrollAcc = 0f; private var scrollT = 0L
    private var dX = 0f; private var dY = 0f; private var dStart = 0L; private var dLock = false; private var edgeSince = 0L; private var edgeCool = 0L
    private var pinchS = 1f; private var pinching = false; private var dialing = false; private var pAng = 0f; private var pPx = 0f; private var pPy = 0f; private var dialRef = 0f
    private var label = ""; private var labelUntil = 0L
    private var mOpen = false; private var smiling = false; private var brows = false
    private var holdName = ""; private var holdSince = 0L; private var holdFired = false; private var browRef = -1f; private var browSince = 0L

    override fun onServiceConnected() {
        instance = this
        reloadGaze()
        wm = getSystemService(WindowManager::class.java)
        overlay = Overlay(this).also {
            wm?.addView(it, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)) }
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        try { overlay?.let { wm?.removeView(it) } } catch (e: Exception) {}
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun hide() { ui.post { overlay?.let { it.show = false; it.pts = null; it.invalidate() } } }

    private fun reset() {
        candG = ""; candN = 0; stableG = ""; tracking = false; gripping = false; pinching = false; dialing = false
        palmRef = -1f; dStart = 0L; edgeSince = 0L; fx.reset(); fy.reset()
    }

    /** Called every camera frame with the 21 hand landmarks and the gesture model's pose name ("" when unsure). */
    fun onHand(lm: List<NormalizedLandmark>?, name: String) {
        val ov = overlay ?: return
        val now = SystemClock.uptimeMillis()
        if (lm == null || lm.size < 21) { reset(); hide(); return }
        val ar = 0.75f
        fun d(a: Int, b: Int) = hypot((lm[a].x() - lm[b].x()) * ar, lm[a].y() - lm[b].y())
        val size = maxOf(d(0, 9), 0.01f)
        pinchS = pinchS * 0.5f + (d(4, 8) / size) * 0.5f
        val ang = Math.toDegrees(Math.atan2((lm[9].y() - lm[0].y()).toDouble(), ((lm[9].x() - lm[0].x()) * ar).toDouble())).toFloat()
        val dt = ((now - lastT) / 1000f).coerceIn(0.001f, 0.2f); lastT = now
        val dm = resources.displayMetrics; val sw = dm.widthPixels.toFloat(); val sh = dm.heightPixels.toFloat()
        val smooth = prefs.getInt("smooth", 2).coerceIn(0, 4)
        if (smooth != lastSmooth) { lastSmooth = smooth; val c = floatArrayOf(4f, 2.5f, 1.5f, 1f, 0.6f)[smooth]; fx = OneEuro(c, 0.02f); fy = OneEuro(c, 0.02f) }
        val hs = floatArrayOf(0.7f, 1f, 1.5f)[prefs.getInt("hold", 1).coerceIn(0, 2)]

        // the pose has to be the same for 2 frames in a row to count
        val raw = if (name == "None") "" else name
        if (raw == candG) candN++ else { candG = raw; candN = 1 }
        if (candN >= 2 && stableG != candG) { stableG = candG; since = now; fired = false }
        val g = stableG
        val pointing = g == "Pointing_Up"
        if (!haveCursorInit) { cx = sw / 2; cy = sh / 2; haveCursorInit = true }

        // fist right after pointing = mouse button down
        if (g == "Closed_Fist" && !gripping && now - lastPointAt < 900 && now > cool) { gripping = true; gStart = now; gX = cx; gY = cy; say("GRAB") }
        if (pointing) lastPointAt = now

        // volume: pinch, then turn the hand like a dial (clockwise is louder)
        val pinchThr = when (g) { "Pointing_Up", "" -> 0.32f; "Closed_Fist" -> 0.2f; else -> 0f }
        if (pinchThr > 0f && pinchS < pinchThr && !pinching && !gripping && now > cool) { pinching = true; pAng = ang; pPx = lm[9].x(); pPy = lm[9].y(); dialing = false; dialRef = ang }
        if (pinching && !dialing) {
            val moved = hypot((lm[9].x() - pPx) * ar, lm[9].y() - pPy)
            if (abs(wrap(ang - pAng)) > 16f && moved < 0.06f) { dialing = true; dialRef = ang }
        }
        if (pinching && dialing) {
            val steps = (wrap(ang - dialRef) / 14f).toInt()
            if (steps != 0) {
                dialRef = wrap(dialRef + steps * 14f); say("VOLUME")
                val au = getSystemService(AudioManager::class.java)
                repeat(abs(steps)) { au.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
        }
        if (pinching && pinchS > 0.6f) { pinching = false; dialing = false; cool = now + 300 }

        // cursor: relative, from the knuckles (they hardly move when you curl your fingers). Lift off and re-aim whenever you like.
        val moving = (pointing || gripping) && !dialing
        if (moving) {
            val sxp = fx.f((lm[5].x() + lm[9].x()) / 2f * sw, now); val syp = fy.f((lm[5].y() + lm[9].y()) / 2f * sh, now)
            if (!tracking) tracking = true else {
                val dx = sxp - lastPx; val dy = syp - lastPy
                val v = hypot(dx / sw, dy / sh) / dt
                val gain = floatArrayOf(1.3f, 1.9f, 2.6f, 3.6f, 5f)[prefs.getInt("speed", 2).coerceIn(0, 4)] * (1f + 0.9f * minOf(v / 0.9f, 1f))
                cx = (cx + dx * gain).coerceIn(0f, sw); cy = (cy + dy * gain).coerceIn(0f, sh)
            }
            lastPx = sxp; lastPy = syp
        } else { tracking = false; fx.reset(); fy.reset() }

        // let go of the fist: click, long press or drag
        if (gripping && g != "Closed_Fist") {
            gripping = false; cool = now + 250
            val dist = hypot(cx - gX, cy - gY); val held = now - gStart
            when {
                dist > 80f * dm.density -> { say("DRAG"); stroke(gX, gY, cx, cy, 280) }
                held >= 650 -> { say("HOLD"); stroke(gX, gY, gX, gY, 700) }
                else -> { say("CLICK"); stroke(gX, gY, gX, gY, 60) }
            }
        }

        // open hand = joystick scroll
        if (g == "Open_Palm") {
            val y = lm[9].y()
            if (palmRef < 0f) { palmRef = y; palmSince = now }
            if (now - palmSince <= 250) palmRef = y          // settle: wherever your hand opens becomes the neutral spot
            else {
                val off = y - palmRef; val dead = 0.04f
                if (abs(off) > dead) {
                    val sp = floatArrayOf(0.5f, 0.75f, 1f, 1.4f, 2f)[prefs.getInt("sspeed", 2).coerceIn(0, 4)]
                    scrollAcc += minOf((abs(off) - dead) * 28f * sp, 6f) * dt
                    if (scrollAcc >= 1f && now - scrollT > 230) {
                        scrollAcc = 0f; scrollT = now; say(if (off < 0) "SCROLL DOWN" else "SCROLL UP")
                        if (off < 0) stroke(sw / 2, sh * 0.72f, sw / 2, sh * 0.42f, 200) else stroke(sw / 2, sh * 0.42f, sw / 2, sh * 0.72f, 200)
                    }
                } else scrollAcc = 0f
            }
        } else palmRef = -1f

        // easy scroll: park the cursor in the top or bottom band
        val edgeOn = prefs.getBoolean("edge", true)
        val zone = if (!edgeOn || !pointing) 0 else if (cy < sh * 0.08f) 1 else if (cy > sh * 0.92f) -1 else 0
        if (zone == 0) edgeSince = 0L else {
            if (edgeSince == 0L) edgeSince = now
            if (now - edgeSince > 350 && now > edgeCool) {
                edgeCool = now + 650; say(if (zone > 0) "SCROLL UP" else "SCROLL DOWN")
                if (zone > 0) stroke(sw / 2, sh * 0.35f, sw / 2, sh * 0.70f, 220) else stroke(sw / 2, sh * 0.70f, sw / 2, sh * 0.35f, 220)
            }
        }

        // dwell click: hold the cursor still and a ring fills
        val dwellMs = longArrayOf(0L, 1000L, 1500L, 2000L, 3000L)[prefs.getInt("dwell", 4).coerceIn(0, 4)]
        var ring = 0f
        if (dwellMs > 0 && pointing && !gripping && zone == 0) {
            if (hypot(cx - dX, cy - dY) > 26f * dm.density) { dX = cx; dY = cy; dStart = now; dLock = false }
            else if (!dLock) { ring = ((now - dStart) / dwellMs.toFloat()).coerceIn(0f, 1f); if (ring >= 1f) { dLock = true; ring = 0f; say("CLICK"); stroke(cx, cy, cx, cy, 60) } }
        } else { dX = cx; dY = cy; dStart = now; dLock = false }
        if (dialing) { val au = getSystemService(AudioManager::class.java); ring = au.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / au.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

        // hold a pose: peace = Back, thumbs up = Home, thumbs down = Recents, rock sign = play / pause
        val need = (when (g) { "Victory", "Thumb_Up", "Thumb_Down", "ILoveYou" -> 700L; else -> 1_000_000L } * hs).toLong()
        if (!fired && now - since > need && now > cool) {
            fired = true; cool = now + 1200
            when (g) {
                "Victory" -> act(GLOBAL_ACTION_BACK, "BACK"); "Thumb_Up" -> act(GLOBAL_ACTION_HOME, "HOME"); "Thumb_Down" -> act(GLOBAL_ACTION_RECENTS, "RECENTS")
                "ILoveYou" -> { say("PLAY / PAUSE"); media() }
            }
        }

        val text = if (now < labelUntil) label else g.replace("_", " ").uppercase()
        val status = (if (g == "") "..." else g) + "   pinch " + String.format("%.2f", pinchS)
        val pts = if (prefs.getBoolean("preview", true)) FloatArray(42) { if (it % 2 == 0) lm[it / 2].x() else lm[it / 2].y() } else null
        val show = pointing || gripping || g == "Open_Palm" || now < labelUntil
        val gx = gX; val gy = gY; val grip = gripping
        ui.post { ov.show = show; ov.px = cx; ov.py = cy; ov.down = grip; ov.label = text; ov.pts = pts; ov.ax = gx; ov.ay = gy; ov.drag = grip; ov.dwell = ring; ov.edge = edgeOn; ov.status = if (pts != null) status else ""; ov.invalidate() }
    }

    /**
     * Face mode (head tracking + expressions), from MediaPipe's face model:
     *  - Turn or nod your head slightly: the cursor moves, relative like a mouse.
     *  - Open your mouth: the button. Quick = click, hold = long press, move your head while open = drag.
     *  - Raise your eyebrows and nod up or down: joystick scroll.
     *  - Smile (hold) = Back, pucker (hold) = Home, tongue out (hold) = Recents.
     */
    fun onFace(lm: List<NormalizedLandmark>?, bs: Map<String, Float>) {
        val ov = overlay ?: return
        val now = SystemClock.uptimeMillis()
        if (lm == null || lm.size < 10) { tracking = false; gripping = false; mOpen = false; browRef = -1f; dStart = 0L; edgeSince = 0L; fx.reset(); fy.reset(); hide(); return }
        val dm = resources.displayMetrics; val sw = dm.widthPixels.toFloat(); val sh = dm.heightPixels.toFloat()
        val dt = ((now - lastT) / 1000f).coerceIn(0.001f, 0.2f); lastT = now
        val smooth = prefs.getInt("smooth", 2).coerceIn(0, 4)
        if (smooth != lastSmooth) { lastSmooth = smooth; val c = floatArrayOf(4f, 2.5f, 1.5f, 1f, 0.6f)[smooth]; fx = OneEuro(c, 0.02f); fy = OneEuro(c, 0.02f) }
        val hs = floatArrayOf(0.7f, 1f, 1.5f)[prefs.getInt("hold", 1).coerceIn(0, 2)]
        if (!haveCursorInit) { cx = sw / 2; cy = sh / 2; haveCursorInit = true }
        fun b(k: String) = bs[k] ?: 0f
        val eyes = prefs.getString("mode", "hand") == "eyes"
        val feat = if (eyes) Gaze.raw(lm) else null
        if (eyes && calTx >= 0f) {                          // calibrating: just record where the eyes are
            if (feat != null) synchronized(samples) { samples.add(Gaze.expand(feat) to floatArrayOf(calTx, calTy)) }
            hide(); return
        }

        // expressions, with a wide gap between "on" and "off" so they don't flicker
        val jaw = b("jawOpen"); mOpen = if (mOpen) jaw > 0.25f else jaw > 0.45f
        val sm = (b("mouthSmileLeft") + b("mouthSmileRight")) / 2f; smiling = if (smiling) sm > 0.35f else sm > 0.55f
        val br = b("browInnerUp"); brows = if (brows) br > 0.35f else br > 0.6f
        val pucker = b("mouthPucker") > 0.6f; val tongue = b("tongueOut") > 0.5f
        val scrolling = brows && !mOpen

        // mouth open = button down
        if (mOpen && !gripping && now > cool) { gripping = true; gStart = now; gX = cx; gY = cy; say("GRAB") }

        // cursor: relative, from the nose tip
        if (eyes) {
            // eye tracking: the cursor goes where your eyes are looking. It holds still while you scroll or click, so those don't disturb it.
            val m = gazeModel
            if (m != null && feat != null && !scrolling && !gripping) {
                val gs = prefs.getInt("smooth", 2).coerceIn(0, 4)
                if (gs != lastGS) { lastGS = gs; val c = floatArrayOf(2f, 1.2f, 0.8f, 0.5f, 0.3f)[gs]; gfx = OneEuro(c, 0.005f); gfy = OneEuro(c, 0.005f) }
                val t = Gaze.predict(m, Gaze.expand(feat))
                cx = gfx.f(t[0].coerceIn(0f, sw), now).coerceIn(0f, sw); cy = gfy.f(t[1].coerceIn(0f, sh), now).coerceIn(0f, sh)
            }
        } else if (!scrolling) {
            val sxp = fx.f(lm[1].x() * sw, now); val syp = fy.f(lm[1].y() * sh, now)
            if (!tracking) tracking = true else {
                val dx = sxp - lastPx; val dy = syp - lastPy
                val v = hypot(dx / sw, dy / sh) / dt
                val gain = floatArrayOf(3f, 4.5f, 6.5f, 9f, 13f)[prefs.getInt("fspeed", 2).coerceIn(0, 4)] * (1f + 0.9f * minOf(v / 0.4f, 1f))
                cx = (cx + dx * gain).coerceIn(0f, sw); cy = (cy + dy * gain).coerceIn(0f, sh)
            }
            lastPx = sxp; lastPy = syp
        } else { tracking = false; fx.reset(); fy.reset() }

        // mouth closes: click, long press or drag
        if (gripping && !mOpen) {
            gripping = false; cool = now + 250
            val dist = hypot(cx - gX, cy - gY); val held = now - gStart
            when {
                dist > 80f * dm.density -> { say("DRAG"); stroke(gX, gY, cx, cy, 280) }
                held >= 650 -> { say("HOLD"); stroke(gX, gY, gX, gY, 700) }
                else -> { say("CLICK"); stroke(gX, gY, gX, gY, 60) }
            }
        }

        // eyebrows up + nod = joystick scroll
        if (scrolling) {
            val y = lm[1].y()
            if (browRef < 0f) { browRef = y; browSince = now }
            if (now - browSince <= 300) browRef = y
            else {
                val off = y - browRef; val dead = 0.02f
                if (abs(off) > dead) {
                    val sp = floatArrayOf(0.5f, 0.75f, 1f, 1.4f, 2f)[prefs.getInt("sspeed", 2).coerceIn(0, 4)]
                    scrollAcc += minOf((abs(off) - dead) * 90f * sp, 6f) * dt
                    if (scrollAcc >= 1f && now - scrollT > 230) {
                        scrollAcc = 0f; scrollT = now; say(if (off < 0) "SCROLL DOWN" else "SCROLL UP")
                        if (off < 0) stroke(sw / 2, sh * 0.72f, sw / 2, sh * 0.42f, 200) else stroke(sw / 2, sh * 0.42f, sw / 2, sh * 0.72f, 200)
                    }
                } else scrollAcc = 0f
            }
        } else browRef = -1f

        // easy scroll at the top and bottom edges, and dwell click, same as hand mode
        val edgeOn = prefs.getBoolean("edge", true)
        val zone = if (!edgeOn || scrolling || gripping) 0 else if (cy < sh * 0.08f) 1 else if (cy > sh * 0.92f) -1 else 0
        if (zone == 0) edgeSince = 0L else {
            if (edgeSince == 0L) edgeSince = now
            if (now - edgeSince > 350 && now > edgeCool) {
                edgeCool = now + 650; say(if (zone > 0) "SCROLL UP" else "SCROLL DOWN")
                if (zone > 0) stroke(sw / 2, sh * 0.35f, sw / 2, sh * 0.70f, 220) else stroke(sw / 2, sh * 0.70f, sw / 2, sh * 0.35f, 220)
            }
        }
        val dwellMs = longArrayOf(0L, 1000L, 1500L, 2000L, 3000L)[prefs.getInt("dwell", 4).coerceIn(0, 4)]
        var ring = 0f
        if (dwellMs > 0 && !scrolling && !gripping && zone == 0) {
            if (hypot(cx - dX, cy - dY) > 26f * dm.density) { dX = cx; dY = cy; dStart = now; dLock = false }
            else if (!dLock) { ring = ((now - dStart) / dwellMs.toFloat()).coerceIn(0f, 1f); if (ring >= 1f) { dLock = true; ring = 0f; say("CLICK"); stroke(cx, cy, cx, cy, 60) } }
        } else { dX = cx; dY = cy; dStart = now; dLock = false }

        // hold an expression: smile = Back, pucker = Home, tongue out = Recents
        val hn = if (mOpen || scrolling) "" else if (smiling) "SMILE" else if (pucker) "PUCKER" else if (tongue) "TONGUE" else ""
        if (hn != holdName) { holdName = hn; holdSince = now; holdFired = false }
        if (hn != "" && !holdFired && now - holdSince > (800 * hs).toLong() && now > cool) {
            holdFired = true; cool = now + 1200
            when (hn) { "SMILE" -> act(GLOBAL_ACTION_BACK, "BACK"); "PUCKER" -> act(GLOBAL_ACTION_HOME, "HOME"); "TONGUE" -> act(GLOBAL_ACTION_RECENTS, "RECENTS") }
        }

        val text = if (now < labelUntil) label else if (scrolling) "SCROLL" else if (eyes && gazeModel == null) "CALIBRATE FIRST" else ""
        val status = if (prefs.getBoolean("preview", true)) "jaw " + String.format("%.2f", jaw) + "   smile " + String.format("%.2f", sm) + "   brow " + String.format("%.2f", br) else ""
        val gx = gX; val gy = gY; val grip = gripping
        ui.post { ov.show = true; ov.px = cx; ov.py = cy; ov.down = grip; ov.label = text; ov.pts = null; ov.ax = gx; ov.ay = gy; ov.drag = grip; ov.dwell = ring; ov.edge = edgeOn; ov.status = status; ov.invalidate() }
    }

    private var haveCursorInit = false
    private fun wrap(a: Float): Float { var d = a; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d }
    private fun say(t: String) { label = t; labelUntil = SystemClock.uptimeMillis() + 800 }
    private fun act(a: Int, name: String) { say(name); ui.post { performGlobalAction(a) } }
    private fun media() {
        val a = getSystemService(AudioManager::class.java)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }
    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        ui.post { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); if (x2 != x1 || y2 != y1) lineTo(x2, y2) }, 0, ms)).build(), null, null) }
    }
}

/** The glowing ring that follows your fingertip, plus a small live hand skeleton in the corner. Never touchable. */
class Overlay(c: Context) : View(c) {
    var px = 0f; var py = 0f; var show = false; var down = false; var label = ""
    var pts: FloatArray? = null; var ax = 0f; var ay = 0f; var drag = false; var dwell = 0f; var edge = true; var status = ""
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8F4FF.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt()) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = 0x4400E5FF }
    private val cyan = 0xFF00E5FF.toInt()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = cyan; setShadowLayer(20f, 0f, 0f, cyan) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cyan }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0x9900E5FF.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 34f; textAlign = Paint.Align.CENTER; setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt()) }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000A12.toInt() }
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = cyan }
    private val bones = intArrayOf(0,1,1,2,2,3,3,4,0,5,5,6,6,7,7,8,5,9,9,10,10,11,11,12,9,13,13,14,14,15,15,16,13,17,17,18,18,19,19,20,0,17)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        if (pts == null && status.isNotEmpty()) c.drawText(status, width / 2f, 130f * d, small)
        pts?.let { p ->
            val w = 110 * d; val h = 150 * d; val l = width - w - 14 * d; val t = 110 * d
            c.drawRoundRect(RectF(l, t, l + w, t + h), 14 * d, 14 * d, panel)
            var i = 0; while (i < bones.size) { c.drawLine(l + p[bones[i] * 2] * w, t + p[bones[i] * 2 + 1] * h, l + p[bones[i + 1] * 2] * w, t + p[bones[i + 1] * 2 + 1] * h, bone); i += 2 }
            for (k in 0 until 21) c.drawCircle(l + p[k * 2] * w, t + p[k * 2 + 1] * h, 3.5f * d, dot)
            c.drawText(status, l + w / 2, t + h + 28f, small)
        }
        if (!show) return
        if (edge) { c.drawLine(0f, height * 0.10f, width.toFloat(), height * 0.10f, band); c.drawLine(0f, height * 0.90f, width.toFloat(), height * 0.90f, band) }
        if (drag) c.drawLine(ax, ay, px, py, line)
        c.drawCircle(px, py, if (down) 22f else 40f, ring)
        c.drawCircle(px, py, if (down) 14f else 7f, dot)
        if (dwell > 0f) c.drawArc(RectF(px - 58f, py - 58f, px + 58f, py + 58f), -90f, 360f * dwell, false, arc)
        if (label.isNotEmpty()) c.drawText(label, px, py - 62f, txt)
    }
}
