package com.rollage.game

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlin.math.*

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class Mode { MENU, PLAYING, PAUSED, GAMEOVER }

    private val prefs = context.getSharedPreferences("rollage", Context.MODE_PRIVATE)
    private val sounds = SoundBank(context)
    private val w = GameWorld(sounds).apply {
        events = object : Events {
            override fun onTier(text: String) { buzz(35) }
            override fun onSlam() { buzz(18) }
            override fun onBoom() { buzz(65) }
            override fun onHurt() { buzz(60) }
        }
    }

    @Volatile private var mode = Mode.MENU
    private val ads = (context.applicationContext as RollageApp).ads
    private var revivedThisRun = false

    @Volatile private var running = false
    private var thread: Thread? = null
    private var scaleF = 0f
    private var viewW = 0f
    private var lastT = 0L
    @Volatile private var powerSave = false

    @Volatile private var best = prefs.getInt("best", 0)
    private var runStartBest = best
    private var lastRunNewBest = false
    private var bestDirty = false; private var bestSaveT = 0f
    @Volatile private var muted = prefs.getBoolean("mute", false)
    @Volatile private var hapticsOn = prefs.getBoolean("haptics", true)
    @Volatile private var shakeOn = prefs.getBoolean("shake", true)
    private var runsN = prefs.getInt("runs", 0)
    private var totalBlocks = prefs.getInt("tb", 0)
    private var bestCombo = prefs.getInt("bc", 0)

    private var activePid = -1
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var downT = 0L; private var downOnUi = false

    private val btnPause = RectF(); private val btnRestart = RectF(); private val btnSound = RectF()
    private val pResume = RectF(); private val pRestart = RectF()
    private val pSound = RectF();  private val pHaptics = RectF(); private val pShake = RectF()
    private val pPrivacy = RectF()
    private val goRetry = RectF(); private val goMenu = RectF()
    private val goRevive = RectF(); private val goDouble = RectF()
    private val mnBoost = RectF()

    private val clouds = List(7) { Cloud(rand(0f, 3600f), rand(50f, 250f), rand(0.7f, 1.4f), rand(4f, 10f)) }
    private class Cloud(val x: Float, val y: Float, val s: Float, val v: Float)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sPath = Path()
    private val ballClip = Path()
    private val sRect = RectF()
    private val grainPaint = Paint()
    private val heavy: Typeface = try {
        Typeface.createFromAsset(context.assets, "Bungee.ttf")
    } catch (e: Exception) { Typeface.create("sans-serif-black", Typeface.NORMAL) }
    private val PIf = Math.PI.toFloat()

    private val vibrator: Vibrator? = obtainVibrator(context)

    init {
        holder.addCallback(this)
        isFocusable = true; isFocusableInTouchMode = true
        sounds.muted = muted
        makeGrain()
        updateBanner()
    }

    // ---------- paper grain ----------
    private fun makeGrain() {
        val gsz = 144
        val bmp = Bitmap.createBitmap(gsz, gsz, Bitmap.Config.ARGB_8888)
        val px = IntArray(gsz * gsz)
        for (i in px.indices) {
            if (Math.random() < 0.10f)
                px[i] = (16 + (Math.random() * 36)).toInt() shl 24   // translucent ink speck
        }
        bmp.setPixels(px, 0, gsz, 0, 0, gsz, gsz)
        grainPaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainPaint.alpha = 60
    }

    // ---------- haptics ----------
    @RequiresApi(31)
    private fun vibrator31(c: Context): Vibrator? =
        (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    private fun obtainVibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) vibrator31(c)
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun buzz(ms: Long) {
        if (!hapticsOn || mode != Mode.PLAYING) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    // ---------- mode ----------
    private fun setMode(m: Mode) {
        mode = m
        w.playing = m == Mode.PLAYING
        if (m != Mode.PLAYING) { w.clearInput(); sounds.setRoll(0f) }
        updateBanner()
    }

    private fun updateBanner() {
        ads.setBannerVisible(mode == Mode.MENU || mode == Mode.GAMEOVER)
    }

    private fun startRun() {
        runStartBest = best
        lastRunNewBest = false
        revivedThisRun = false
        setMode(Mode.PLAYING)
        w.tutDone = prefs.getBoolean("tut", false)
        w.offerCmd('R')                      // fresh world on the game thread
        prefs.edit().putInt("runs", ++runsN).apply()
    }

    private fun enterGameOver() {
        mode = Mode.GAMEOVER
        lastRunNewBest = w.score > runStartBest && w.score > 0
        totalBlocks += w.blocksSmashed
        if (w.maxCombo > bestCombo) bestCombo = w.maxCombo
        prefs.edit()
            .putInt("tb", totalBlocks).putInt("bc", bestCombo)
            .putBoolean("tut", w.tutDone).apply()
        saveBest()
        sounds.setRoll(0f)
        updateBanner()
        post {                               // main thread: count this game-over for ad pacing
            ads.reportGameOver()
            updateBanner()
        }
    }

    fun handleBack(): Boolean = when (mode) {
        Mode.PLAYING -> { setMode(Mode.PAUSED); true }
        Mode.PAUSED  -> { setMode(Mode.PLAYING); true }
        Mode.GAMEOVER -> {
            val act = context as? Activity
            if (act != null) ads.maybeShowInterstitial(act) { setMode(Mode.MENU); w.offerCmd('M') }
            else { setMode(Mode.MENU); w.offerCmd('M') }
            true
        }
        Mode.MENU    -> false
    }

    private fun toggleSound() {
        muted = !muted
        sounds.muted = muted
        prefs.edit().putBoolean("mute", muted).apply()
        if (!muted) sounds.ensureStarted()
        sounds.play("ding0", 0.5f, 1.2f)
    }
    private fun toggleHaptics() {
        hapticsOn = !hapticsOn
        prefs.edit().putBoolean("haptics", hapticsOn).apply()
        if (hapticsOn) buzz(25)
    }
    private fun toggleShake() {
        shakeOn = !shakeOn
        prefs.edit().putBoolean("shake", shakeOn).apply()
    }

    // ---------- ads helpers ----------
    private fun thenStartRun() {
        val act = context as? Activity
        if (act != null && mode == Mode.GAMEOVER) ads.maybeShowInterstitial(act) { startRun() }
        else startRun()
    }
    private fun watchAd(grant: () -> Unit) {
        val act = context as? Activity ?: return
        ads.showRewarded(act, onGranted = grant, onUnavailable = {
            Toast.makeText(context, "AD NOT READY - TRY AGAIN IN A MOMENT",
                Toast.LENGTH_SHORT).show()
        })
    }
    private fun reviveOffered() = !revivedThisRun && w.score >= 300
    private fun doubleOffered() = !w.scoreDoubled && w.score >= 200

    // ---------- lifecycle ----------
    private fun refreshPowerSave() {
        powerSave = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        } catch (_: Exception) { false }
    }

    fun resume() {
        if (running) return
        refreshPowerSave()
        sounds.resumeAll()
        running = true; lastT = System.nanoTime()
        thread = Thread(this, "rollage-loop").also { it.start() }
    }

    fun pause() {
        if (!running && thread == null) return
        running = false
        if (mode == Mode.PLAYING) setMode(Mode.PAUSED)
        sounds.pauseAll()
        thread?.let { t -> t.interrupt(); try { t.join(1500) } catch (_: InterruptedException) {} }
        thread = null
        prefs.edit().putBoolean("tut", w.tutDone).apply()
        saveBest()
    }
    private fun saveBest() = prefs.edit().putInt("best", best).apply()

    override fun surfaceCreated(h: SurfaceHolder) { resume() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, wd: Int, ht: Int) {
        scaleF = ht / 740f; viewW = wd / scaleF
    }
    override fun surfaceDestroyed(h: SurfaceHolder) { pause() }

    // ---------- game loop ----------
    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            if (viewW <= 0f || scaleF <= 0f) { SystemClock.sleep(8); lastT = System.nanoTime(); continue }
            val t0 = System.nanoTime()
            var dt = (t0 - lastT) / 1e9f; lastT = t0
            if (dt > 0.1f) dt = 0.1f
            if (dt <= 0f) dt = 0.0001f

            try {
                w.viewWM = viewW / PX
                if (mode != Mode.PAUSED) w.update(dt)
                if (mode == Mode.PLAYING && w.over) enterGameOver()
                sounds.setBiome(w.biomeIdx)
                if (w.score > best) { best = w.score; bestDirty = true }
                if (bestDirty) { bestSaveT -= dt
                    if (bestSaveT <= 0f) { bestSaveT = 1f; bestDirty = false; saveBest() } }

                if (holder.surface.isValid) {
                    var c: Canvas? = null
                    try { c = holder.lockCanvas(); if (c != null) render(c) }
                    finally { if (c != null) try { holder.unlockCanvasAndPost(c) } catch (_: Exception) {} }
                }
            } catch (t: Throwable) {
                L.e("game loop crashed", t)
                saveBest()
                running = false
                if (BuildConfig.DEBUG) throw t
            }

            val target = if (powerSave) 33f else 16f
            val spentMs = (System.nanoTime() - t0) / 1e6f
            if (spentMs < target) SystemClock.sleep((target - spentMs).toLong())
        }
    }

    // ---------- input ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePid != -1) return true
                activePid = e.getPointerId(e.actionIndex)
                val i = e.findPointerIndex(activePid)
                val x = e.getX(i) / scaleF; val y = e.getY(i) / scaleF
                downX = x; downY = y; lastX = x; downT = System.currentTimeMillis()
                downOnUi = mode == Mode.PLAYING &&
                    (btnPause.contains(x, y) || btnRestart.contains(x, y) || btnSound.contains(x, y))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePid)
                if (i >= 0 && !downOnUi && mode == Mode.PLAYING) {
                    val x = e.getX(i) / scaleF
                    val dx = x - lastX; lastX = x
                    if (dx != 0f) w.offerDrag(dx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) != activePid) return true
                activePid = -1
                val x = e.getX(e.actionIndex) / scaleF
                val y = e.getY(e.actionIndex) / scaleF
                val dur = System.currentTimeMillis() - downT
                val dx = x - downX; val dy = y - downY
                when (mode) {
                    Mode.MENU -> if (dur < 800L) when {
                        mnBoost.contains(x, y) -> watchAd {
                            w.boostNext = true
                            Toast.makeText(context, "SHIELD START ARMED",
                                Toast.LENGTH_SHORT).show()
                        }
                        else -> startRun()
                    }
                    Mode.PAUSED -> if (dur < 600L) when {
                        pResume.contains(x, y)  -> setMode(Mode.PLAYING)
                        pRestart.contains(x, y) -> startRun()
                        pSound.contains(x, y)   -> toggleSound()
                        pHaptics.contains(x, y) -> toggleHaptics()
                        pShake.contains(x, y)   -> toggleShake()
                        pPrivacy.contains(x, y) ->
                            (context as? Activity)?.let { ads.showPrivacyOptions(it) }
                    }
                    Mode.GAMEOVER -> if (dur < 600L) when {
                        goRevive.contains(x, y) && reviveOffered() -> watchAd {
                            revivedThisRun = true
                            setMode(Mode.PLAYING)
                            w.revive()
                        }
                        goDouble.contains(x, y) && doubleOffered() -> watchAd {
                            if (w.doubleScore()) {
                                if (w.score > best) { best = w.score; saveBest() }
                                lastRunNewBest = w.score > runStartBest
                            }
                        }
                        goRetry.contains(x, y) -> thenStartRun()
                        goMenu.contains(x, y) -> {
                            val act = context as? Activity
                            if (act != null)
                                ads.maybeShowInterstitial(act) { setMode(Mode.MENU); w.offerCmd('M') }
                            else { setMode(Mode.MENU); w.offerCmd('M') }
                        }
                    }
                    Mode.PLAYING -> {
                        if (downOnUi && dur < 600L) {
                            when {
                                btnPause.contains(x, y)   -> setMode(Mode.PAUSED)
                                btnRestart.contains(x, y) -> startRun()
                                btnSound.contains(x, y)   -> toggleSound()
                            }
                        } else if (dur < 240L && hypot(dx, dy) < 16f) w.offerCmd('T')
                        else if (dy < -46f && dur < 320L && abs(dx) < 80f) w.offerCmd('J')
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> activePid = -1
        }
        return true
    }
    override fun performClick(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mode == Mode.MENU && (keyCode == KeyEvent.KEYCODE_SPACE ||
                                  keyCode == KeyEvent.KEYCODE_ENTER)) { startRun(); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> w.keyDir = -1f
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> w.keyDir = 1f
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> w.offerCmd('J')
            KeyEvent.KEYCODE_SPACE -> w.offerCmd('S')
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE -> handleBack()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> w.keyDir = 0f
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    // ---------- rendering ----------
    private fun render(c: Canvas) {
        c.drawColor(C_PAPER)
        c.save(); c.scale(scaleF, scaleF)
        val shMul = if (shakeOn) 1f else 0f
        val shx = (Math.random().toFloat() * 2f - 1f) * w.shake * shMul
        val shy = (Math.random().toFloat() * 2f - 1f) * w.shake * 0.7f * shMul
        c.save()
        c.translate(-w.camX * PX + shx, -w.camY * PX + shy)
        drawSky(c); drawGround(c); drawProps(c); drawRamps(c); drawRopes(c)
        drawPickups(c)
        drawBlocks(c); drawSpinners(c); drawWaves(c); drawFrags(c); drawBall(c)
        drawParts(c); drawPops(c)
        c.restore()
        when (mode) {
            Mode.PLAYING  -> { drawHud(c); drawAnnounce(c); drawHint(c) }
            Mode.PAUSED   -> { drawHud(c); drawAnnounce(c); drawPause(c) }
            Mode.MENU     -> { drawAnnounce(c); drawTitle(c) }
            Mode.GAMEOVER -> drawResults(c)
        }
        c.restore()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grainPaint)
    }

    private fun mod(a: Float, b: Float) = ((a % b) + b) % b

    private fun drawSky(c: Canvas) {
        val sunX = viewW * 0.8f; val sunY = 126f - w.camY * PX * 0.25f
        val mid = w.biomeAtX(w.camX + w.viewWM * 0.5f).decor == 4
        if (mid) {
            p.style = Paint.Style.FILL; p.color = C_PAPER
            val off3 = w.camX * PX * 0.06f
            var si = floor((off3 - 60f) / 80f)
            while (si * 80f - off3 < viewW + 60f) {
                val x = si * 80f - off3
                val y = 20f + hash(si * 3.1f) * 230f
                p.alpha = (110 + hash(si * 7.7f) * 130).toInt()
                c.drawCircle(x, y, 1.3f + hash(si * 5.5f) * 1.3f, p)
                si++
            }
            p.alpha = 255
            c.drawCircle(sunX, sunY, 46f, p)
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
            c.drawCircle(sunX, sunY, 46f, p)
            p.style = Paint.Style.FILL; p.color = 0xFFAFA6C4.toInt()
            c.drawCircle(sunX - 14f, sunY - 8f, 7f, p)
            c.drawCircle(sunX + 10f, sunY + 14f, 5f, p)
        } else {
            p.style = Paint.Style.FILL; p.color = C_VERM
            c.drawCircle(sunX, sunY, 54f, p)
            p.style = Paint.Style.STROKE; p.color = C_TEAL; p.strokeWidth = 2.5f; p.alpha = 128
            c.drawCircle(sunX + 5f, sunY + 4f, 60f, p); p.alpha = 255
        }

        val t = System.nanoTime() / 1e9f
        if (!mid) for (cl in clouds) {
            val x = mod(cl.x - w.camX * PX * 0.1f + t * cl.v, 3600f) - 300f
            val y = cl.y - w.camY * PX * 0.12f
            if (x < -260f || x > viewW + 260f) continue
            drawCloud(c, x, y, cl.s)
        }

        val seg = 340f; val off = w.camX * PX * 0.16f
        var i = floor((off - 120f) / seg).toInt()
        while (i * seg - off < viewW + 140f) {
            val x = i * seg - off
            val b = w.biomeAtX(i * seg / (0.16f * PX))
            if (b.decor == 3) {
                val cx = x + seg / 2
                p.style = Paint.Style.FILL; p.color = b.mesa2
                sPath.reset()
                sPath.moveTo(cx - 150f, GROUND_Y + 60f); sPath.lineTo(cx, GROUND_Y - 250f)
                sPath.lineTo(cx + 150f, GROUND_Y + 60f); sPath.close()
                c.drawPath(sPath, p)
                p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                c.drawPath(sPath, p)
                p.style = Paint.Style.STROKE; p.color = C_VERM; p.strokeWidth = 4f
                c.drawLine(cx - 34f, GROUND_Y - 176f, cx + 30f, GROUND_Y - 196f, p)
            } else {
                val h1 = hash(i * 3.7f)
                if (h1 >= 0.25f) {
                    val mh = 70f + h1 * 130f; val mw = seg * 0.62f
                    p.style = Paint.Style.FILL; p.color = b.mesa1
                    sPath.reset()
                    sPath.moveTo(x + 16f, GROUND_Y + 60f); sPath.lineTo(x + 58f, GROUND_Y - mh)
                    sPath.lineTo(x + 58f + mw, GROUND_Y - mh); sPath.lineTo(x + seg - 16f, GROUND_Y + 60f)
                    sPath.close(); c.drawPath(sPath, p)
                }
            }
            i++
        }

        val seg2 = 300f; val off2 = w.camX * PX * 0.38f
        i = floor((off2 - 160f) / seg2).toInt()
        while (i * seg2 - off2 < viewW + 160f) {
            val x = i * seg2 - off2
            val b = w.biomeAtX(i * seg2 / (0.38f * PX))
            val r = 90f + hash(i * 5.3f) * 130f
            p.style = Paint.Style.FILL; p.color = b.mesa2
            sRect.set(x, GROUND_Y + 26f - r, x + seg2, GROUND_Y + 26f + r)
            sPath.reset(); sPath.addArc(sRect, 180f, 180f); sPath.close(); c.drawPath(sPath, p)
            when (b.decor) {
                0 -> if (hash(i * 8.8f) > 0.7f) drawCactus(c, x, r)
                1 -> if (hash(i * 8.8f) > 0.6f) {
                    val cx = x + seg2 / 2 + (hash(i * 2.2f) - 0.5f) * 120f
                    val cy = GROUND_Y + 26f - r
                    p.style = Paint.Style.FILL; p.color = b.mesa1
                    sPath.reset()
                    sPath.moveTo(cx - 14f, cy); sPath.lineTo(cx, cy - 90f - hash(i) * 60f)
                    sPath.lineTo(cx + 14f, cy); sPath.close(); c.drawPath(sPath, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2.5f
                    c.drawPath(sPath, p)
                }
                2 -> {
                    if (hash(i * 8.8f) > 0.55f) drawPine(c, x + seg2 / 2, GROUND_Y + 26f - r, hash(i))
                    p.style = Paint.Style.FILL; p.color = C_SNOW
                    sRect.set(x + 30f, GROUND_Y + 18f, x + seg2 - 30f, GROUND_Y + 34f)
                    c.drawOval(sRect, p)
                }
                3 -> if (hash(i * 8.8f) > 0.6f) {
                    val cx = x + seg2 / 2
                    p.style = Paint.Style.FILL; p.color = 0xFF8A5A44.toInt()
                    c.drawRoundRect(cx - 16f, GROUND_Y - 30f - hash(i) * 20f, cx + 16f, GROUND_Y, 6f, 6f, p)
                    p.style = Paint.Style.STROKE; p.color = C_VERM; p.strokeWidth = 2.5f
                    c.drawLine(cx - 8f, GROUND_Y - 14f, cx + 6f, GROUND_Y - 30f, p)
                }
                4 -> if (hash(i * 8.8f) > 0.5f) {
                    val cx = x + seg2 / 2
                    p.style = Paint.Style.FILL; p.color = 0xFF8D84A8.toInt()
                    val hh = 40f + hash(i * 3.3f) * 60f
                    c.drawRect(cx - 12f, GROUND_Y - hh, cx + 12f, GROUND_Y, p)
                    c.drawRect(cx - 20f, GROUND_Y - hh * 0.5f, cx - 12f, GROUND_Y, p)
                    c.drawRect(cx + 12f, GROUND_Y - hh * 0.7f, cx + 20f, GROUND_Y, p)
                }
            }
            i++
        }
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float, s: Float) {
        c.save(); c.translate(x, y); c.scale(s, s)
        p.style = Paint.Style.FILL; p.color = C_CLOUD
        c.drawRoundRect(-48f, -10f, 48f, 14f, 12f, 12f, p)
        c.drawCircle(-8f, -16f, 14f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f; p.color = C_INK
        c.drawRoundRect(-48f, -10f, 48f, 14f, 12f, 12f, p)
        c.drawCircle(-8f, -16f, 14f, p)
        c.restore()
    }

    private fun drawCactus(c: Canvas, x: Float, r: Float) {
        val cx = x + 150f + (hash(x) - 0.5f) * 120f
        val cy = GROUND_Y + 26f - r
        p.style = Paint.Style.FILL; p.color = C_TEAL
        c.drawRoundRect(cx - 5f, cy - 46f, cx + 5f, cy + 2f, 5f, 5f, p)
        c.drawRoundRect(cx - 17f, cy - 30f, cx - 8f, cy - 14f, 4.5f, 4.5f, p)
        c.drawRoundRect(cx - 14f, cy - 32f, cx - 4f, cy - 24f, 4f, 4f, p)
        c.drawRoundRect(cx + 8f, cy - 38f, cx + 17f, cy - 24f, 4.5f, 4.5f, p)
        c.drawRoundRect(cx + 4f, cy - 40f, cx + 14f, cy - 32f, 4f, 4f, p)
    }

    private fun drawPine(c: Canvas, cx: Float, cy: Float, h: Float) {
        var w2 = 44f
        for (k in 0..2) {
            val ty = cy - 26f - k * 24f
            sPath.reset()
            sPath.moveTo(cx, ty - 30f); sPath.lineTo(cx - w2 / 2, ty); sPath.lineTo(cx + w2 / 2, ty)
            sPath.close()
            p.style = Paint.Style.FILL; p.color = C_TEAL; c.drawPath(sPath, p)
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2.5f; c.drawPath(sPath, p)
            w2 *= 0.78f
        }
        if (h > 0.5f) {
            p.style = Paint.Style.FILL; p.color = C_SNOW
            c.drawCircle(cx, cy - 78f, 5f, p)
        }
    }

    private fun drawGround(c: Canvas) {
        val l = w.camX * PX - 60f; val r = l + viewW + 120f
        p.style = Paint.Style.FILL; p.color = C_PAPER2
        c.drawRect(l, GROUND_Y, r, GROUND_Y + 375f, p)
        p.style = Paint.Style.STROKE; p.color = C_VERM; p.strokeWidth = 2f; p.alpha = 230
        c.drawLine(l, GROUND_Y + 3.5f, r, GROUND_Y + 3.5f, p); p.alpha = 255
        p.color = C_INK; p.strokeWidth = 3.5f
        c.drawLine(l, GROUND_Y, r, GROUND_Y, p)
        val cell = 90f
        var i = floor(l / cell).toInt()
        while ((i + 1) * cell < r) {
            val h1 = hash(i * 1.93f); val x = i * cell + 20f + hash(i * 7.7f) * 50f
            val b = w.biomeAtX(x / PX)
            when {
                b.decor == 2 -> {
                    if (h1 < 0.35f) {
                        p.style = Paint.Style.FILL; p.color = C_SNOW
                        sRect.set(x - 8f, GROUND_Y - 7f, x + 34f, GROUND_Y + 5f)
                        c.drawOval(sRect, p)
                        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
                        c.drawLine(x - 8f, GROUND_Y - 1f, x + 34f, GROUND_Y - 1f, p)
                    } else if (h1 < 0.5f) drawPine(c, x + 8f, GROUND_Y, hash(i))
                    p.style = Paint.Style.FILL; p.color = C_INK; p.alpha = 130
                    c.drawCircle(x + 50f, GROUND_Y + 10f + hash(i * 3.1f) * 12f, 1.8f, p)
                    p.alpha = 255
                }
                b.decor == 3 -> {
                    if (h1 < 0.4f) {
                        p.style = Paint.Style.FILL; p.color = 0xFF8A5A44.toInt()
                        c.drawCircle(x, GROUND_Y + 8f + hash(i * 3.1f) * 12f, 2.4f, p)
                    } else if (h1 < 0.55f) {
                        p.style = Paint.Style.STROKE; p.color = C_VERM; p.strokeWidth = 2.5f; p.alpha = 190
                        val y = GROUND_Y + 14f + hash(i * 9.9f) * 20f
                        c.drawLine(x, y, x + 12f, y - 4f, p)
                        c.drawLine(x + 12f, y - 4f, x + 20f, y + 3f, p)
                        p.alpha = 255
                    }
                }
                else -> {
                    if (h1 < 0.3f) {
                        p.style = Paint.Style.STROKE; p.color = b.accent
                        p.strokeWidth = 2.5f; p.strokeCap = Paint.Cap.ROUND
                        if (b.decor == 4) p.alpha = 120
                        c.drawLine(x - 4f, GROUND_Y + 1f, x - 7f, GROUND_Y - 9f, p)
                        c.drawLine(x, GROUND_Y + 1f, x, GROUND_Y - 13f, p)
                        c.drawLine(x + 4f, GROUND_Y + 1f, x + 7f, GROUND_Y - 9f, p)
                        p.alpha = 255
                    } else if (h1 < 0.5f) {
                        p.style = Paint.Style.FILL; p.color = C_INK
                        p.alpha = if (b.decor == 4) 110 else 190
                        c.drawCircle(x, GROUND_Y + 7f + hash(i * 3.1f) * 14f, 2f, p)
                        c.drawCircle(x + 14f, GROUND_Y + 13f + hash(i * 4.4f) * 10f, 1.6f, p)
                        p.alpha = 255
                    } else if (h1 < 0.6f && b.decor != 4) {
                        val rw = 18f + hash(i * 6.6f) * 14f
                        p.style = Paint.Style.FILL; p.color = C_STONE
                        c.drawRoundRect(x, GROUND_Y - rw * 0.5f, x + rw, GROUND_Y, 5f, 5f, p)
                        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2.5f
                        c.drawRoundRect(x, GROUND_Y - rw * 0.5f, x + rw, GROUND_Y, 5f, 5f, p)
                    } else if (h1 < 0.75f) {
                        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f; p.alpha = 77
                        val y = GROUND_Y + 16f + hash(i * 9.9f) * 26f
                        c.drawLine(x, y, x + 9f, y, p); p.alpha = 255
                    }
                }
            }
            i++
        }
    }

    private fun drawRamps(c: Canvas) {
        for (r in w.ramps) {
            val b = r.body
            c.save()
            c.translate(b.position.x * PX, b.position.y * PX)
            c.rotate(b.angle * 180f / PIf)
            sPath.reset()
            sPath.moveTo(-r.w / 2, 13f); sPath.lineTo(r.w / 2, -13f); sPath.lineTo(r.w / 2, 13f)
            sPath.close()
            p.style = Paint.Style.FILL; p.color = C_STONE; c.drawPath(sPath, p)
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
            p.strokeJoin = Paint.Join.ROUND; c.drawPath(sPath, p)
            p.strokeWidth = 2f; p.alpha = 115
            for (k in 0..2) {
                val xx = -r.w / 2 + 50f + k * 75f
                val yOn = 13f - (xx + r.w / 2) * 26f / r.w
                c.drawLine(xx, yOn + 3f, xx + 10f, yOn + 16f, p)
            }
            p.alpha = 255
            c.restore()
        }
    }

    private fun drawRopes(c: Canvas) {
        for (r in w.ropes) {
            val a = r.j.anchorA; val bp = r.b.position
            p.style = Paint.Style.STROKE; p.color = C_INK
            p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(a.x * PX, a.y * PX, bp.x * PX, bp.y * PX, p)
            p.style = Paint.Style.FILL; p.color = C_VERM
            c.drawCircle(a.x * PX, a.y * PX, 4f, p)
        }
    }

    private fun drawPickups(c: Canvas) {
        for (pk in w.pickups) {
            val bob = sin(w.simTime * 2.2f + pk.x) * 6f
            val x = pk.x * PX; val y = pk.y * PX + bob
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f)
            c.drawCircle(x, y, 22f, p)
            p.pathEffect = null
            when (pk.kind) {
                'h' -> {
                    p.style = Paint.Style.FILL; p.color = C_VERM
                    sPath.reset()
                    sPath.addCircle(x - 7f, y - 3f, 8f, Path.Direction.CW)
                    sPath.addCircle(x + 7f, y - 3f, 8f, Path.Direction.CW)
                    sPath.moveTo(x - 14f, y); sPath.lineTo(x, y + 16f); sPath.lineTo(x + 14f, y)
                    sPath.close()
                    c.drawPath(sPath, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = C_INK
                    c.drawPath(sPath, p)
                }
                's' -> {
                    p.style = Paint.Style.FILL; p.color = C_TEAL
                    sPath.reset()
                    sPath.moveTo(x, y - 14f); sPath.lineTo(x + 11f, y - 8f); sPath.lineTo(x + 11f, y + 4f)
                    sPath.lineTo(x, y + 14f); sPath.lineTo(x - 11f, y + 4f); sPath.lineTo(x - 11f, y - 8f)
                    sPath.close(); c.drawPath(sPath, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = C_INK
                    c.drawPath(sPath, p)
                    p.style = Paint.Style.FILL; p.color = C_PAPER
                    c.drawCircle(x, y - 1f, 3.5f, p)
                }
                't' -> {
                    p.style = Paint.Style.FILL; p.color = C_INK
                    c.drawCircle(x, y, 13f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = C_PAPER
                    p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(x, y, x, y - 8f, p)
                    c.drawLine(x, y, x + 6f, y + 2f, p)
                }
            }
        }
    }

    private fun drawProps(c: Canvas) {
        for (pr in w.props) {
            val b = pr.body
            when (pr.kind) {
                'w', 'h' -> {}
                'b' -> {
                    c.save(); c.translate(b.position.x * PX, b.position.y * PX)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2.5f
                    p.strokeCap = Paint.Cap.ROUND
                    for (k in 0..2) {
                        val xx = -20f + k * 20f
                        c.drawLine(xx, -6f, xx + 8f, 4f, p)
                        c.drawLine(xx + 8f, 4f, xx + 16f, -6f, p)
                    }
                    p.style = Paint.Style.FILL; p.color = C_VERM
                    c.drawRoundRect(-28f, -16f, 28f, -6f, 5f, 5f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-28f, -16f, 28f, -6f, 5f, 5f, p)
                    c.restore()
                }
                'c' -> {
                    c.save(); c.translate(b.position.x * PX, b.position.y * PX)
                    p.style = Paint.Style.FILL; p.color = C_INK
                    c.drawRoundRect(-2f, -14f, 34f, 2f, 4f, 4f, p)
                    p.style = Paint.Style.FILL; p.color = C_STONE
                    c.drawRoundRect(-20f, -2f, 20f, 14f, 4f, 4f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-20f, -2f, 20f, 14f, 4f, 4f, p)
                    c.drawCircle(-2f, -6f, 11f, p)
                    p.style = Paint.Style.FILL; p.color = C_PAPER
                    c.drawCircle(-2f, -6f, 3.5f, p)
                    c.restore()
                }
                'p' -> {
                    c.save(); c.translate(b.position.x * PX, b.position.y * PX)
                    p.style = Paint.Style.FILL; p.color = C_TEAL
                    c.drawRoundRect(-pr.w / 2, -pr.h / 2, pr.w / 2, pr.h / 2, 4f, 4f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-pr.w / 2, -pr.h / 2, pr.w / 2, pr.h / 2, 4f, 4f, p)
                    c.restore()
                }
                else -> {
                    c.save()
                    c.translate(b.position.x * PX, b.position.y * PX)
                    c.rotate(b.angle * 180f / PIf)
                    p.style = Paint.Style.FILL; p.color = C_STONE
                    c.drawRoundRect(-pr.w / 2, -pr.h / 2, pr.w / 2, pr.h / 2, 4f, 4f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-pr.w / 2, -pr.h / 2, pr.w / 2, pr.h / 2, 4f, 4f, p)
                    c.restore()
                }
            }
        }
    }

    private fun drawSpinners(c: Canvas) {
        for (pr in w.props) {
            if (pr.kind == 'h') {
                p.style = Paint.Style.FILL; p.color = C_INK
                c.drawCircle(pr.body.position.x * PX, pr.body.position.y * PX, 6f, p)
                continue
            }
            if (pr.kind != 'w') continue
            val b = pr.body
            c.save()
            c.translate(b.position.x * PX, b.position.y * PX)
            c.rotate(b.angle * 180f / PIf)
            for (k in 0..1) {
                p.style = Paint.Style.FILL; p.color = C_VERM
                c.drawRoundRect(-68f, -7f, 68f, 7f, 6f, 6f, p)
                p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                c.drawRoundRect(-68f, -7f, 68f, 7f, 6f, 6f, p)
                c.rotate(90f)
            }
            p.style = Paint.Style.FILL; p.color = C_PAPER
            c.drawCircle(0f, 0f, 9f, p)
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
            c.drawCircle(0f, 0f, 9f, p)
            p.style = Paint.Style.FILL; p.color = C_INK
            c.drawCircle(0f, 0f, 3f, p)
            c.restore()
        }
    }

    private fun drawWaves(c: Canvas) {
        for (wv in w.waves) {
            val a = 1f - wv.traveled / WAVE_MAX
            if (a <= 0f) continue
            val rr = wv.traveled * PX
            val cx = wv.x * PX
            p.style = Paint.Style.STROKE
            p.color = C_INK; p.strokeWidth = 4f * a + 1f; p.alpha = (a * 200).toInt()
            c.drawArc(cx - rr, GROUND_Y - rr, cx + rr, GROUND_Y + rr, 180f, 180f, false, p)
            p.color = C_VERM; p.strokeWidth = 2f; p.alpha = (a * 160).toInt()
            val r2 = rr * 0.8f
            c.drawArc(cx - r2, GROUND_Y - r2, cx + r2, GROUND_Y + r2, 180f, 180f, false, p)
        }
        p.alpha = 255
    }

    private fun drawBlocks(c: Canvas) {
        val L2 = w.camX * PX - 120f; val R2 = w.camX * PX + viewW + 120f
        for (bl in w.blocks) {
            if (bl.dead) continue
            val b = bl.body.position; val bx = b.x * PX
            if (bx < L2 || bx > R2) continue
            c.save()
            c.translate(bx, b.y * PX)
            c.rotate(bl.body.angle * 180f / PIf)
            drawBlockShape(c, bl)
            c.restore()
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, ro: Float, ri: Float, n: Int, col: Int) {
        sPath.reset()
        for (i in 0 until n * 2) {
            val a = i * PIf / n - PIf / 2f
            val rr = if (i % 2 == 0) ro else ri
            val px = x + cos(a) * rr; val py = y + sin(a) * rr
            if (i == 0) sPath.moveTo(px, py) else sPath.lineTo(px, py)
        }
        sPath.close()
        p.style = Paint.Style.FILL; p.color = col; c.drawPath(sPath, p)
    }

    private fun drawBlockShape(c: Canvas, bl: GameWorld.Block) {
        val w2 = bl.w; val h2 = bl.h; val r = bl.r; val O = 1.8f
        p.strokeJoin = Paint.Join.ROUND
        val strain = bl.strain
        if (strain > 0.5f) {
            c.translate(sin(w.simTime * 42f + bl.seed * 20f) * 1.4f * (strain - 0.5f) * 2f, 0f)
        }
        if (r > 0f) {
            when (bl.type) {
                GameWorld.T.BARREL -> {
                    p.style = Paint.Style.FILL; p.color = C_TNT
                    c.drawCircle(O, O, r, p)
                    p.style = Paint.Style.STROKE; p.color = C_TNTDARK; p.strokeWidth = 5f
                    c.drawLine(-r * 0.9f, -r * 0.25f, r * 0.9f, -r * 0.25f, p)
                    c.drawLine(-r * 0.9f, r * 0.25f, r * 0.9f, r * 0.25f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawCircle(0f, 0f, r, p)
                    pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER
                    pTxt.textSize = 15f; pTxt.color = C_PAPER
                    c.drawText("!", 0f, 6f, pTxt)
                }
                GameWorld.T.BALLOON -> {
                    p.style = Paint.Style.FILL; p.color = C_TEAL
                    c.drawCircle(0f, -3f, r, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawCircle(0f, -3f, r, p)
                    p.style = Paint.Style.FILL; p.color = C_TEAL
                    sPath.reset()
                    sPath.moveTo(-5f, r - 5f); sPath.lineTo(5f, r - 5f); sPath.lineTo(0f, r + 6f)
                    sPath.close(); c.drawPath(sPath, p)
                    p.style = Paint.Style.FILL; p.color = C_SNOW; p.alpha = 200
                    c.drawCircle(-r * 0.3f, -r * 0.45f - 3f, r * 0.28f, p)
                    p.alpha = 255
                }
                else -> {
                    p.style = Paint.Style.FILL; p.color = bl.col
                    c.drawCircle(O, O, r, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawCircle(0f, 0f, r, p)
                    if (bl.type == GameWorld.T.ANVIL) {
                        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 4f
                        c.drawLine(-r * 0.55f, 0f, r * 0.55f, 0f, p)
                    } else drawStar(c, 0f, 0f, r * 0.5f, r * 0.2f, 4, C_PAPER)
                    if (bl.type == GameWorld.T.BONUS) {
                        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                        p.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f)
                        c.drawCircle(0f, 0f, r + 6f, p)
                        p.pathEffect = null
                    }
                }
            }
        } else {
            val rad = if (bl.type == GameWorld.T.PLANK) 5f else 8f
            when (bl.type) {
                GameWorld.T.GLASS -> {
                    p.style = Paint.Style.FILL; p.color = C_GLASS; p.alpha = 153
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, rad, rad, p)
                    p.alpha = 255
                    p.style = Paint.Style.STROKE; p.color = C_TEAL; p.strokeWidth = 2.5f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, rad, rad, p)
                    p.color = Color.WHITE; p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(-w2 * 0.26f, h2 * 0.3f, w2 * 0.06f, -h2 * 0.3f, p)
                }
                GameWorld.T.ICE -> {
                    p.style = Paint.Style.FILL; p.color = C_ICE; p.alpha = 215
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, rad, rad, p)
                    p.alpha = 255
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, rad, rad, p)
                    p.color = Color.WHITE; p.strokeWidth = 2.5f; p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(-w2 * 0.3f, h2 * 0.28f, w2 * 0.05f, -h2 * 0.28f, p)
                    c.drawLine(-w2 * 0.12f, h2 * 0.3f, w2 * 0.22f, -h2 * 0.22f, p)
                }
                GameWorld.T.DOMINO -> {
                    p.style = Paint.Style.FILL; p.color = C_PAPER
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, 4f, 4f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, 4f, 4f, p)
                    p.style = Paint.Style.FILL; p.color = C_INK
                    c.drawLine(-w2 / 2 + 4f, 0f, w2 / 2 - 4f, 0f, p)
                    val top = bl.hp > bl.maxHp * 0.5f
                    for (dy in intArrayOf(-1, 1)) {
                        if (dy == 1 && !top) continue
                        val oy = dy * h2 * 0.25f
                        c.drawCircle(-w2 * 0.18f, oy, 3f, p)
                        c.drawCircle(w2 * 0.18f, oy, 3f, p)
                    }
                }
                GameWorld.T.SAND -> {
                    p.style = Paint.Style.FILL; p.color = C_SAND
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, 12f, 12f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, 12f, 12f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.alpha = 150
                    c.drawLine(-w2 * 0.3f, -h2 * 0.3f, w2 * 0.3f, -h2 * 0.3f, p)
                    p.alpha = 110
                    c.drawCircle(-w2 * 0.18f, h2 * 0.15f, 1.6f, p)
                    c.drawCircle(w2 * 0.1f, h2 * 0.28f, 1.4f, p)
                    c.drawCircle(w2 * 0.22f, h2 * 0.02f, 1.6f, p)
                    p.alpha = 255
                }
                GameWorld.T.HIVE -> {
                    p.style = Paint.Style.FILL; p.color = C_MUSTARD
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, 10f, 10f, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, 10f, 10f, p)
                    p.style = Paint.Style.STROKE; p.color = C_HIVED; p.strokeWidth = 3f
                    c.drawLine(-w2 * 0.32f, -h2 * 0.22f, w2 * 0.32f, -h2 * 0.22f, p)
                    c.drawLine(-w2 * 0.36f, 0f, w2 * 0.36f, 0f, p)
                    c.drawLine(-w2 * 0.32f, h2 * 0.22f, w2 * 0.32f, h2 * 0.22f, p)
                    p.style = Paint.Style.FILL; p.color = C_INK
                    c.drawCircle(0f, h2 * 0.32f, 2.5f, p)
                }
                else -> {
                    p.style = Paint.Style.FILL; p.color = bl.col
                    c.drawRoundRect(-w2 / 2 + O, -h2 / 2 + O, w2 / 2 + O, h2 / 2 + O, rad, rad, p)
                    p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
                    c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, rad, rad, p)
                    when (bl.type) {
                        GameWorld.T.CRATE -> {
                            p.strokeWidth = 2.5f
                            c.drawLine(-w2 / 2 + 10f, -h2 / 2 + 10f, w2 / 2 - 10f, h2 / 2 - 10f, p)
                            c.drawLine(w2 / 2 - 10f, -h2 / 2 + 10f, -w2 / 2 + 10f, h2 / 2 - 10f, p)
                        }
                        GameWorld.T.PLANK -> {
                            p.style = Paint.Style.FILL
                            c.drawCircle(-w2 / 2 + 10f, 0f, 2.2f, p)
                            c.drawCircle(w2 / 2 - 10f, 0f, 2.2f, p)
                        }
                        GameWorld.T.STONE -> {
                            p.strokeWidth = 1.5f; p.alpha = 128
                            c.drawLine(-w2 / 2 + 9f, -h2 * 0.12f, -2f, -h2 * 0.12f, p)
                            c.drawLine(4f, h2 * 0.2f, w2 / 2 - 9f, h2 * 0.2f, p)
                            p.alpha = 255
                        }
                        GameWorld.T.BOOM -> {
                            p.style = Paint.Style.FILL
                            c.drawCircle(0f, 4f, 10.5f, p)
                            c.drawRect(-2.4f, -15f, 2.4f, -6f, p)
                            p.style = Paint.Style.STROKE; p.color = C_PAPER
                            p.strokeWidth = 2.2f; p.strokeCap = Paint.Cap.ROUND
                            c.drawLine(0f, -17f, 0f, -22f, p)
                            c.drawLine(-3f, -19f, 3f, -19f, p)
                        }
                        GameWorld.T.TNT -> {
                            pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER
                            pTxt.textSize = 13f; pTxt.color = C_PAPER
                            c.drawText("TNT", 0f, 5f, pTxt)
                            p.style = Paint.Style.STROKE; p.color = C_TNTDARK; p.strokeWidth = 3f
                            c.drawLine(-w2 * 0.3f, h2 * 0.3f, w2 * 0.3f, h2 * 0.3f, p)
                            p.style = Paint.Style.STROKE; p.color = C_INK
                            p.strokeWidth = 2.2f; p.strokeCap = Paint.Cap.ROUND
                            c.drawLine(0f, -h2 / 2, 4f, -h2 / 2 - 9f, p)
                            p.style = Paint.Style.FILL
                            c.drawCircle(4f, -h2 / 2 - 10f, 2.6f, p)
                        }
                        else -> {}
                    }
                }
            }
        }
        val hr = bl.hp / bl.maxHp
        if (hr < 0.55f && r <= 0f) {
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f; p.alpha = 166
            val s = bl.seed
            var cx = -w2 * 0.28f; var cy = -h2 * 0.3f
            sPath.reset(); sPath.moveTo(cx, cy)
            for (i in 1..3) { cx += w2 * (0.14f + hash(s + i) * 0.12f)
                cy += h2 * (hash(s + i * 2.7f) - 0.34f); sPath.lineTo(cx, cy) }
            c.drawPath(sPath, p)
            if (hr < 0.28f) {
                cx = w2 * 0.2f; cy = -h2 * 0.35f
                sPath.reset(); sPath.moveTo(cx, cy)
                for (i in 1..3) { cx -= w2 * 0.13f * hash(s + i * 4.2f + 1f)
                    cy += h2 * (hash(s + i * 5.1f) - 0.3f); sPath.lineTo(cx, cy) }
                c.drawPath(sPath, p)
            }
            p.alpha = 255
        }
        if (strain > 0.6f && r <= 0f) {
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
            p.alpha = (min(1f, strain) * 200).toInt()
            var cx2 = w2 * 0.1f; var cy2 = h2 * 0.35f
            sPath.reset(); sPath.moveTo(cx2, cy2)
            for (i in 1..3) { cx2 += w2 * 0.1f * hash(bl.seed + i * 3.3f + 5f)
                cy2 -= h2 * 0.16f; sPath.lineTo(cx2, cy2) }
            c.drawPath(sPath, p)
            p.alpha = 255
        }
        if (bl.fuse >= 0f && bl.fuse < 10f) {
            val on = sin(w.simTime * 30f + bl.seed * 9f) > 0f
            p.style = Paint.Style.FILL
            p.color = if (on) C_VERM else C_MUSTARD
            c.drawCircle(0f, -h2 / 2 - 7f, 3f + (if (on) 1.5f else 0f), p)
        }
        if (bl.flash > 0f) {
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.alpha = (bl.flash * 140).toInt()
            if (r > 0f) c.drawCircle(0f, 0f, r, p)
            else c.drawRoundRect(-w2 / 2, -h2 / 2, w2 / 2, h2 / 2, 8f, 8f, p)
            p.alpha = 255
        }
    }

    private fun drawFrags(c: Canvas) {
        for (f in w.frags) {
            val al = if (f.life > 2.1f) 1f - (f.life - 2.1f) / 0.5f else 1f
            if (al <= 0f) continue
            val b = f.body
            p.alpha = (al * 255).toInt()
            val shp = b.fixtureList.shape
            if (shp is org.jbox2d.collision.shapes.CircleShape) {
                p.style = Paint.Style.FILL; p.color = f.col
                c.drawCircle(b.position.x * PX, b.position.y * PX, shp.radius * PX, p)
                p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
                c.drawCircle(b.position.x * PX, b.position.y * PX, shp.radius * PX, p)
            } else if (shp is org.jbox2d.collision.shapes.PolygonShape) {
                val ca = cos(b.angle); val sa = sin(b.angle)
                sPath.reset()
                for (i in 0 until shp.vertexCount) {
                    val v = shp.getVertex(i)
                    val wx = (b.position.x + v.x * ca - v.y * sa) * PX
                    val wy = (b.position.y + v.x * sa + v.y * ca) * PX
                    if (i == 0) sPath.moveTo(wx, wy) else sPath.lineTo(wx, wy)
                }
                sPath.close()
                p.style = Paint.Style.FILL; p.color = f.col; c.drawPath(sPath, p)
                p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
                c.drawPath(sPath, p)
            }
        }
        p.alpha = 255
    }

    private fun drawBall(c: Canvas) {
        val b = w.ball
        val px = b.position.x * PX; val py = b.position.y * PX
        val vx = b.linearVelocity.x; val vy = b.linearVelocity.y
        val hgt = clamp(1f - (GROUND_Y - py) / 420f, 0.35f, 1f)
        p.style = Paint.Style.FILL; p.color = C_INK; p.alpha = (0.12f * hgt * 255).toInt()
        val sw = 26f * hgt + 8f
        c.drawOval(px - sw, GROUND_Y + 1f, px + sw, GROUND_Y + 13f, p)
        p.alpha = 255
        if (abs(vx) > 18f) {
            p.style = Paint.Style.STROKE; p.color = C_VERM
            p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND; p.alpha = 128
            val sgn = sign(vx)
            for (i in 0..2) {
                val ly = py - 9f + i * 9f
                val len = min(58f, abs(vx) / 1.5f * (2f + i * 1.5f))
                val x1 = px - sgn * (36f + i * 7f)
                c.drawLine(x1, ly, x1 - sgn * len, ly, p)
            }
            p.alpha = 255
        }
        if (w.slamT > 0f) {
            p.style = Paint.Style.STROKE; p.color = C_VERM
            p.strokeWidth = 3f; p.alpha = 217
            c.drawCircle(px, py, BALL_R + 9f + sin(System.nanoTime() / 4e7f) * 2.5f, p)
            p.alpha = 255
        }
        val grounded = w.simTime - w.lastGroundAt < 0.13f
        val st = if (!grounded) clamp(abs(vy) / 90f, 0f, 0.16f) else 0f
        val sx = (1f + 0.3f * w.sq) * (1f - st * 0.5f)
        val sy = (1f - 0.3f * w.sq) * (1f + st)
        val blink = w.iframes > 0f && sin(System.nanoTime() / 4e7f) > 0f
        c.save()
        c.translate(px, py); c.scale(sx, sy); c.rotate(b.angle * 180f / PIf)
        p.style = Paint.Style.FILL; p.color = C_INK
        p.alpha = if (blink) 90 else 255
        c.drawCircle(0f, 0f, BALL_R, p)
        if (!blink) {
            ballClip.reset(); ballClip.addCircle(0f, 0f, BALL_R, Path.Direction.CW)
            c.save(); c.clipPath(ballClip)
            p.color = C_PAPER; c.drawCircle(15f, 0f, 8.5f, p)
            c.restore()
        }
        p.alpha = 255
        c.restore()
        if (w.shield > 0) {
            p.style = Paint.Style.STROKE; p.color = C_TEAL; p.strokeWidth = 3f
            p.alpha = (150 + 60 * sin(System.nanoTime() / 1.5e8f)).toInt()
            c.drawCircle(px, py, BALL_R + 11f, p)
            p.alpha = 255
        }
    }

    private fun drawParts(c: Canvas) {
        for (q in w.parts) {
            val t = q.life / q.max
            when (q.k) {
                'P' -> { p.style = Paint.Style.FILL; p.color = q.col
                    p.alpha = ((1f - t) * 217).toInt()
                    c.drawCircle(q.x, q.y, q.s * (1f + t * 0.9f), p) }
                'S' -> { p.style = Paint.Style.FILL; p.color = q.col
                    p.alpha = ((1f - t) * 255).toInt()
                    c.save(); c.translate(q.x, q.y); c.rotate(q.rot * 180f / PIf)
                    c.drawRect(-q.s, -1.6f, q.s, 1.6f, p); c.restore() }
                'R' -> { p.style = Paint.Style.STROKE; p.color = q.col
                    p.strokeWidth = max(1f, q.lw * (1f - t)); p.alpha = ((1f - t) * 255).toInt()
                    c.drawCircle(q.x, q.y, q.s + q.vr * q.life, p) }
                'F' -> { p.style = Paint.Style.FILL; p.color = q.col
                    p.alpha = ((1f - t) * 220).toInt()
                    c.drawCircle(q.x, q.y, q.s, p) }
                'E' -> { p.style = Paint.Style.FILL
                    p.color = if (t < 0.5f) C_VERM else C_MUSTARD
                    p.alpha = ((1f - t) * 255).toInt()
                    c.drawCircle(q.x, q.y, q.s * (1f - t * 0.5f), p) }
                'Y' -> { p.style = Paint.Style.FILL; p.color = q.col
                    p.alpha = ((0.4f + 0.6f * abs(sin(q.life * 4f + q.rot))) * (1f - t) * 255).toInt()
                    c.drawCircle(q.x, q.y, q.s + sin(q.life * 6f) * 0.8f, p) }
            }
        }
        p.alpha = 255
    }

    private fun drawPops(c: Canvas) {
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER
        for (q in w.pops) {
            val t = q.life / q.max; val y = q.y - t * 46f
            pTxt.alpha = ((if (t > 0.65f) 1f - (t - 0.65f) / 0.35f else 1f) * 255).toInt()
            pTxt.textSize = q.size
            pTxt.color = C_VERM; c.drawText(q.txt, q.x + 2.5f, y + 2.5f, pTxt)
            pTxt.color = q.col; c.drawText(q.txt, q.x, y, pTxt)
        }
        pTxt.alpha = 255
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float, s: Float, on: Boolean) {
        sPath.reset()
        sPath.addCircle(x - s * 0.35f, y - s * 0.25f, s * 0.42f, Path.Direction.CW)
        sPath.addCircle(x + s * 0.35f, y - s * 0.25f, s * 0.42f, Path.Direction.CW)
        sPath.moveTo(x - s * 0.74f, y - s * 0.05f)
        sPath.lineTo(x, y + s * 0.75f)
        sPath.lineTo(x + s * 0.74f, y - s * 0.05f)
        sPath.close()
        p.style = Paint.Style.FILL
        p.color = if (on) C_VERM else C_PAPER
        c.drawPath(sPath, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = C_INK
        c.drawPath(sPath, p)
    }

    private fun drawHud(c: Canvas) {
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.LEFT; pTxt.textSize = 32f
        val sc = "${w.shownScore.toInt()}"
        pTxt.color = C_VERM; c.drawText(sc, 18.5f, 48.5f, pTxt)
        pTxt.color = C_INK; c.drawText(sc, 16f, 46f, pTxt)
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 10f; pTxt.color = 0xFF8F8570.toInt()
        c.drawText("BEST $best", 17f, 62f, pTxt)
        for (i in 0 until GameWorld.MAX_HEARTS)
            drawHeart(c, 25f + i * 27f, 84f, 11f, i < w.hearts)
        if (w.combo >= 2) {
            pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = 26f
            pTxt.color = C_INK; c.drawText("×${w.combo}", viewW / 2 + 2f, 36f, pTxt)
            pTxt.color = C_VERM; c.drawText("×${w.combo}", viewW / 2, 34f, pTxt)
            p.style = Paint.Style.FILL; p.color = C_PAPER
            c.drawRect(viewW / 2 - 42f, 42f, viewW / 2 + 42f, 48f, p)
            p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
            c.drawRect(viewW / 2 - 42f, 42f, viewW / 2 + 42f, 48f, p)
            p.style = Paint.Style.FILL; p.color = C_VERM
            c.drawRect(viewW / 2 - 42f, 42f,
                viewW / 2 - 42f + 84f * clamp(w.comboT / 1.7f, 0f, 1f), 48f, p)
        }
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 12f; pTxt.textAlign = Paint.Align.CENTER; pTxt.color = C_INK
        val dTxt = "${w.distM.toInt()}m"
        val tw = pTxt.measureText(dTxt)
        p.style = Paint.Style.FILL; p.color = C_PAPER
        c.drawRoundRect(viewW - 30f - tw, 14f, viewW - 16f, 36f, 6f, 6f, p)
        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
        c.drawRoundRect(viewW - 30f - tw, 14f, viewW - 16f, 36f, 6f, 6f, p)
        c.drawText(dTxt, viewW - 23f - tw / 2f, 29f, pTxt)
        drawButtons(c)
    }

    private fun circleBase(c: Canvas, cx: Float) {
        p.style = Paint.Style.FILL; p.color = C_PAPER
        c.drawCircle(cx, 76f, 20f, p)
        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2.5f
        c.drawCircle(cx, 76f, 20f, p)
    }

    private fun drawButtons(c: Canvas) {
        btnPause.set(viewW - 150f, 54f, viewW - 106f, 98f)
        btnRestart.set(viewW - 104f, 54f, viewW - 60f, 98f)
        btnSound.set(viewW - 56f, 54f, viewW - 12f, 98f)

        circleBase(c, viewW - 128f)
        p.style = Paint.Style.FILL; p.color = C_INK
        c.drawRoundRect(viewW - 134f, 68f, viewW - 129f, 84f, 2f, 2f, p)
        c.drawRoundRect(viewW - 127f, 68f, viewW - 122f, 84f, 2f, 2f, p)

        circleBase(c, viewW - 82f)
        val cx = viewW - 82f; val r = 10f
        c.drawArc(cx - r, 76f - r, cx + r, 76f + r, 40f, 300f, false, p)
        val ex = cx + r * cos(-20f * PIf / 180f)
        val ey = 76f + r * sin(-20f * PIf / 180f)
        sPath.reset()
        sPath.moveTo(ex + 2f, ey - 7f); sPath.lineTo(ex + 9f, ey - 1f)
        sPath.lineTo(ex - 3f, ey + 2f); sPath.close()
        p.style = Paint.Style.FILL; c.drawPath(sPath, p)

        circleBase(c, viewW - 34f)
        val sx = viewW - 34f
        sPath.reset()
        sPath.moveTo(sx - 8f, 72f); sPath.lineTo(sx - 3f, 72f); sPath.lineTo(sx + 3f, 67f)
        sPath.lineTo(sx + 3f, 85f); sPath.lineTo(sx - 3f, 80f); sPath.lineTo(sx - 8f, 80f)
        sPath.close()
        p.style = Paint.Style.FILL; c.drawPath(sPath, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.strokeCap = Paint.Cap.ROUND
        if (!muted) c.drawArc(sx + 4f, 69f, sx + 12f, 83f, -55f, 110f, false, p)
        else { c.drawLine(sx + 6f, 71f, sx + 12f, 81f, p); c.drawLine(sx + 12f, 71f, sx + 6f, 81f, p) }
    }

    private fun menuButton(c: Canvas, r: RectF, label: String, primary: Boolean) {
        p.style = Paint.Style.FILL; p.color = C_INK
        c.drawRoundRect(r.left + 4f, r.top + 4f, r.right + 4f, r.bottom + 4f, 6f, 6f, p)
        p.color = if (primary) C_VERM else C_PAPER
        c.drawRoundRect(r, 6f, 6f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = C_INK
        c.drawRoundRect(r, 6f, 6f, p)
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = 19f
        pTxt.color = if (primary) C_PAPER else C_INK
        c.drawText(label, r.centerX(), r.centerY() + 7f, pTxt)
    }

    private fun adTag(c: Canvas, cx: Float, cy: Float) {
        p.style = Paint.Style.FILL; p.color = C_MUSTARD
        c.drawRoundRect(cx - 16f, cy - 9f, cx + 16f, cy + 9f, 4f, 4f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = C_INK
        c.drawRoundRect(cx - 16f, cy - 9f, cx + 16f, cy + 9f, 4f, 4f, p)
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 10f; pTxt.color = C_INK; pTxt.textAlign = Paint.Align.CENTER
        c.drawText("AD", cx, cy + 3.5f, pTxt)
    }

    private fun drawPause(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = C_PAPER; p.alpha = 208
        c.drawRect(0f, 0f, viewW, 740f, p); p.alpha = 255
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = 44f
        pTxt.color = C_VERM; c.drawText("PAUSED", viewW / 2 + 3f, 223f, pTxt)
        pTxt.color = C_INK; c.drawText("PAUSED", viewW / 2, 220f, pTxt)
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 12f; pTxt.color = 0xFF6E6650.toInt()
        c.drawText("SCORE ${w.score} · ${w.distM.toInt()}m · ${w.blocksSmashed} BLOCKS",
            viewW / 2, 252f, pTxt)
        pResume.set(viewW / 2 - 130f, 280f, viewW / 2 + 130f, 336f)
        pRestart.set(viewW / 2 - 130f, 352f, viewW / 2 + 130f, 408f)
        pSound.set(viewW / 2 - 130f, 424f, viewW / 2 + 130f, 480f)
        pHaptics.set(viewW / 2 - 130f, 496f, viewW / 2 + 130f, 552f)
        pShake.set(viewW / 2 - 130f, 568f, viewW / 2 + 130f, 624f)
        menuButton(c, pResume, "RESUME", true)
        menuButton(c, pRestart, "RESTART", false)
        menuButton(c, pSound, "SOUND: ${if (muted) "OFF" else "ON"}", false)
        menuButton(c, pHaptics, "HAPTICS: ${if (hapticsOn) "ON" else "OFF"}", false)
        menuButton(c, pShake, "SHAKE: ${if (shakeOn) "ON" else "OFF"}", false)
        if (ads.privacyOptionsRequired) {
            pPrivacy.set(viewW / 2 - 130f, 640f, viewW / 2 + 130f, 694f)
            menuButton(c, pPrivacy, "PRIVACY OPTIONS", false)
        } else pPrivacy.set(0f, 0f, 0f, 0f)
    }

    private fun drawResults(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = C_PAPER; p.alpha = 238
        c.drawRect(0f, 0f, viewW, 740f, p); p.alpha = 255
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = 46f
        pTxt.color = C_VERM; c.drawText("WRECKED!", viewW / 2 + 3f, 183f, pTxt)
        pTxt.color = C_INK; c.drawText("WRECKED!", viewW / 2, 180f, pTxt)

        if (lastRunNewBest) {
            pTxt.textSize = 16f; pTxt.color = C_VERM
            c.drawText("★ NEW BEST! ★", viewW / 2, 216f, pTxt)
        }

        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 15f; pTxt.textAlign = Paint.Align.LEFT
        val lx = viewW / 2 - 120f; val rx = viewW / 2 + 120f
        var y = 262f
        fun row(k: String, v: String) {
            pTxt.color = 0xFF6E6650.toInt(); c.drawText(k, lx, y, pTxt)
            pTxt.textAlign = Paint.Align.RIGHT; pTxt.color = C_INK
            c.drawText(v, rx, y, pTxt); pTxt.textAlign = Paint.Align.LEFT
            y += 30f
        }
        row("SCORE", String.format("%,d", w.score))
        row("BEST", String.format("%,d", best))
        row("DISTANCE", "${w.distM.toInt()}m")
        row("BLOCKS", "${w.blocksSmashed}")
        row("MAX COMBO", "×${w.maxCombo}")

        var by = y + 6f
        if (reviveOffered()) {
            goRevive.set(viewW / 2 - 120f, by, viewW / 2 + 120f, by + 52f)
            menuButton(c, goRevive, "SECOND CHANCE", true)
            adTag(c, goRevive.right - 34f, goRevive.top - 1f)
            by += 62f
        } else goRevive.set(0f, 0f, 0f, 0f)
        if (doubleOffered()) {
            goDouble.set(viewW / 2 - 120f, by, viewW / 2 + 120f, by + 52f)
            menuButton(c, goDouble, "DOUBLE SCORE", false)
            adTag(c, goDouble.right - 34f, goDouble.top - 1f)
            by += 62f
        } else goDouble.set(0f, 0f, 0f, 0f)
        goRetry.set(viewW / 2 - 120f, by + 6f, viewW / 2 + 120f, by + 62f)
        goMenu.set(viewW / 2 - 120f, by + 78f, viewW / 2 + 120f, by + 134f)
        menuButton(c, goRetry, "RETRY", true)
        menuButton(c, goMenu, "MENU", false)

        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 10.5f; pTxt.textAlign = Paint.Align.CENTER
        pTxt.color = 0xFF8F8570.toInt()
        c.drawText("LIFETIME · $runsN RUNS · ${String.format("%,d", totalBlocks)} BLOCKS · TOP ×$bestCombo",
            viewW / 2, 700f, pTxt)
    }

    private fun drawAnnounce(c: Canvas) {
        val t = w.annText ?: return
        if (w.annT >= 1.05f) return
        val k = w.annT / 1.05f
        val alpha = if (k < 0.14f) k / 0.14f
                    else if (k > 0.72f) 1f - (k - 0.72f) / 0.28f else 1f
        val scale = when {
            k < 0.14f -> 0.4f + (k / 0.14f) * 0.72f
            k < 0.26f -> 1.12f - ((k - 0.14f) / 0.12f) * 0.12f
            else -> 1f
        }
        c.save()
        c.translate(viewW / 2, 252f); c.scale(scale, scale)
        pTxt.typeface = heavy; pTxt.textAlign = Paint.Align.CENTER
        pTxt.textSize = min(54f, viewW * 0.14f); pTxt.alpha = (alpha * 255).toInt()
        pTxt.color = C_VERM; c.drawText(t, 3f, 3f, pTxt)
        pTxt.color = C_INK; c.drawText(t, 0f, 0f, pTxt)
        pTxt.alpha = 255
        c.restore()
    }

    private fun drawHint(c: Canvas) {
        val h = w.hintMsg ?: return
        if (mode != Mode.PLAYING) return
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 12f; pTxt.textAlign = Paint.Align.CENTER
        val tw = pTxt.measureText(h)
        val left = viewW / 2 - tw / 2 - 14f; val right = viewW / 2 + tw / 2 + 14f
        val top = 740f - 60f; val bot = 740f - 26f
        p.style = Paint.Style.FILL; p.color = C_PAPER
        c.drawRoundRect(left, top, right, bot, 4f, 4f, p)
        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 2f
        c.drawRoundRect(left, top, right, bot, 4f, 4f, p)
        pTxt.color = C_INK
        c.drawText(h, viewW / 2, (top + bot) / 2 + 4f, pTxt)
    }

    private fun rankFor(b: Int) = when {
        b >= 20000 -> "WRECKER ROYALTY"; b >= 10000 -> "DEMOLITION EXPERT"
        b >= 5000  -> "TOWER TOPPLER";   b >= 2000  -> "WALL WRECKER"
        b >= 500   -> "DEBRIS DABBLER";  else       -> "CRATE ROOKIE"
    }

    private fun drawTitle(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = C_PAPER; p.alpha = 235
        c.drawRect(0f, 0f, viewW, 740f, p); p.alpha = 255
        pTxt.textAlign = Paint.Align.CENTER; pTxt.typeface = Typeface.MONOSPACE
        pTxt.isFakeBoldText = true; pTxt.textSize = 11f; pTxt.color = C_TEAL
        c.drawText("A  P O C K E T  W R E C K I N G  G A M E", viewW / 2, 150f, pTxt)
        val ts = min(120f, viewW * 0.21f)
        pTxt.typeface = heavy; pTxt.textSize = ts
        pTxt.color = C_VERM; c.drawText("ROLLAGE", viewW / 2 + 5f, 255f, pTxt)
        pTxt.alpha = 90; pTxt.color = C_TEAL
        c.drawText("ROLLAGE", viewW / 2 - 3f, 247f, pTxt); pTxt.alpha = 255
        pTxt.color = C_INK; c.drawText("ROLLAGE", viewW / 2, 250f, pTxt)
        pTxt.typeface = Typeface.MONOSPACE; pTxt.textSize = 13f; pTxt.color = C_INK
        c.drawText("Roll hard. Smash everything. Chain the combo.", viewW / 2, 292f, pTxt)
        drawHowTo(c)
        val bl = viewW / 2 - 125f; val br = viewW / 2 + 125f
        val bt = 452f; val bb = 516f
        p.style = Paint.Style.FILL; p.color = C_INK
        c.drawRoundRect(bl + 5f, bt + 5f, br + 5f, bb + 5f, 6f, 6f, p)
        p.color = C_VERM; c.drawRoundRect(bl, bt, br, bb, 6f, 6f, p)
        p.style = Paint.Style.STROKE; p.color = C_INK; p.strokeWidth = 3f
        c.drawRoundRect(bl, bt, br, bb, 6f, 6f, p)
        pTxt.typeface = heavy; pTxt.textSize = 20f; pTxt.color = C_PAPER
        c.drawText("START ROLLING", viewW / 2, 491f, pTxt)

        mnBoost.set(viewW / 2 - 125f, 524f, viewW / 2 + 125f, 566f)
        p.style = Paint.Style.FILL; p.color = C_PAPER
        c.drawRoundRect(mnBoost, 6f, 6f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = C_INK
        c.drawRoundRect(mnBoost, 6f, 6f, p)
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 12.5f
        pTxt.color = if (w.boostNext) C_TEAL else C_INK
        c.drawText(if (w.boostNext) "SHIELD START ARMED!" else "FREE SHIELD START",
            viewW / 2, 551f, pTxt)
        adTag(c, viewW / 2 + 106f, 524f)

        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 12f; pTxt.color = C_INK
        if (best > 0) c.drawText("BEST ${String.format("%,d", best)}", viewW / 2, 584f, pTxt)
        pTxt.textSize = 11f; pTxt.color = C_VERM
        c.drawText("RANK: ${rankFor(best)}", viewW / 2, 606f, pTxt)
        pTxt.textSize = 10f; pTxt.color = 0xFF8F8570.toInt()
        c.drawText("$runsN RUNS · ${String.format("%,d", totalBlocks)} BLOCKS · TOP ×$bestCombo",
            viewW / 2, 628f, pTxt)
        c.drawText("PRINT-NOISE EDITION · BEST WITH SOUND", viewW / 2, 650f, pTxt)
    }

    private fun drawHowTo(c: Canvas) {
        val ys = 352f
        val cs = listOf(viewW * 0.24f, viewW * 0.5f, viewW * 0.76f)
        p.style = Paint.Style.FILL; p.color = C_VERM
        c.drawCircle(cs[0], ys, 6f, p)
        p.style = Paint.Style.STROKE; p.color = C_INK
        p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND
        var cx = cs[0]
        c.drawLine(cx - 14f, ys, cx - 26f, ys, p); c.drawLine(cx + 14f, ys, cx + 26f, ys, p)
        c.drawLine(cx - 26f, ys, cx - 20f, ys - 5f, p); c.drawLine(cx - 26f, ys, cx - 20f, ys + 5f, p)
        c.drawLine(cx + 26f, ys, cx + 20f, ys - 5f, p); c.drawLine(cx + 26f, ys, cx + 20f, ys + 5f, p)
        cx = cs[1]
        c.drawLine(cx, ys + 12f, cx, ys - 12f, p)
        c.drawLine(cx, ys - 12f, cx - 7f, ys - 4f, p); c.drawLine(cx, ys - 12f, cx + 7f, ys - 4f, p)
        cx = cs[2]
        c.drawLine(cx, ys - 14f, cx, ys + 2f, p)
        c.drawLine(cx, ys + 2f, cx - 6f, ys - 4f, p); c.drawLine(cx, ys + 2f, cx + 6f, ys - 4f, p)
        p.style = Paint.Style.FILL; p.color = C_MUSTARD
        for (i in -1..1) {
            val bx = cx + i * 15f
            c.drawRoundRect(bx - 6f, ys + 10f, bx + 6f, ys + 22f, 2f, 2f, p)
            p.style = Paint.Style.STROKE
            c.drawRoundRect(bx - 6f, ys + 10f, bx + 6f, ys + 22f, 2f, 2f, p)
            p.style = Paint.Style.FILL
        }
        pTxt.typeface = Typeface.MONOSPACE; pTxt.isFakeBoldText = true
        pTxt.textSize = 9.5f; pTxt.color = 0xFF6E6650.toInt()
        c.drawText("DRAG — ROLL", cs[0], ys + 42f, pTxt)
        c.drawText("TAP — JUMP", cs[1], ys + 42f, pTxt)
        c.drawText("TAP IN AIR — SLAM", cs[2], ys + 42f, pTxt)
    }
}
