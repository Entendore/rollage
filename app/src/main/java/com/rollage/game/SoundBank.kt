package com.rollage.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

class SoundBank(context: Context) {

    @Volatile var muted = false

    private val appContext = context.applicationContext
    private val sr = 22050
    private val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val TAUf = 2f * PI.toFloat()

    private val sp: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()

    private val ids = HashMap<String, Int>()
    private val nameById = HashMap<Int, String>()
    private val pendingFiles = ConcurrentHashMap<Int, File>()
    private val loaded = HashSet<String>()
    private val lock = Any()

    private var roll: AudioTrack? = null
    private var rollLevel = 0f
    private var started = false
    private var focusRequest: AudioFocusRequest? = null

    // ---- biome ambience ----
    private val ambientTracks = arrayOfNulls<AudioTrack>(5)
    private val ambientVol = floatArrayOf(0.05f, 0.05f, 0.055f, 0.05f, 0.045f)
    private var ambientIdx = -1

    private enum class F { NONE, FULL, DUCK }
    @Volatile private var focus = F.NONE

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { focus = F.NONE; applyRoll(); applyAmbient() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { focus = F.DUCK; applyRoll(); applyAmbient() }
            AudioManager.AUDIOFOCUS_GAIN -> { focus = F.FULL; applyRoll(); applyAmbient() }
        }
    }

    init {
        sp.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) synchronized(lock) { nameById[id]?.let { loaded.add(it) } }
            pendingFiles.remove(id)?.delete()
        }
        Thread { synthAll() }.start()
    }

    /** Call once from a user gesture (START tap). Grabs focus, starts loops. */
    fun ensureStarted() {
        if (started) { if (focus == F.NONE) requestFocus(); return }
        started = true
        requestFocus()
        initRoll()
        applyAmbient()
    }

    private fun requestFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= 26) requestFocus26()
        else @Suppress("DEPRECATION") {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (granted) { focus = F.FULL; applyRoll(); applyAmbient() }
        return granted
    }

    @RequiresApi(26)
    private fun requestFocus26(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun applyRoll() {
        val t = roll ?: return
        val vol = if (muted || focus != F.FULL) 0f else rollLevel.coerceIn(0f, 1f) * 0.08f
        try {
            t.setVolume(vol)
            if (started && focus == F.FULL) {
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            } else if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
        } catch (_: IllegalStateException) {}
    }

    fun setBiome(i: Int) {
        if (i == ambientIdx) return
        ambientTracks.getOrNull(ambientIdx)?.let { t -> try { t.pause() } catch (_: Exception) {} }
        ambientIdx = i
        if (i in ambientTracks.indices && ambientTracks[i] == null) ambientTracks[i] = makeAmbient(i)
        applyAmbient()
    }

    private fun applyAmbient() {
        if (ambientIdx !in ambientTracks.indices) return
        val t = ambientTracks[ambientIdx] ?: return
        val on = started && !muted && focus == F.FULL
        try {
            t.setVolume(if (on) ambientVol[ambientIdx] else 0f)
            if (on) { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() }
            else if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
        } catch (_: Exception) {}
    }

    private fun makeAmbient(idx: Int): AudioTrack? {
        val n = (3f * sr).toInt()
        val buf = ShortArray(n)
        var lp = 0f
        when (idx) {
            0, 1 -> {                                   // desert / canyon wind
                val fc = if (idx == 0) 0.045f else 0.08f
                for (i in 0 until n) {
                    lp += fc * ((Math.random().toFloat() * 2f - 1f) - lp)
                    val lfo = 0.65f + 0.35f * sin(TAUf * (0.11f + idx * 0.05f) * i / sr + idx * 2f)
                    buf[i] = (lp * lfo * 8500f).toInt().toShort()
                }
            }
            2 -> {                                      // arctic gusts
                for (i in 0 until n) {
                    lp += 0.07f * ((Math.random().toFloat() * 2f - 1f) - lp)
                    val g = max(0f, sin(TAUf * 0.27f * i / sr))
                    buf[i] = (lp * (0.2f + 0.8f * g * g) * 10500f).toInt().toShort()
                }
            }
            3 -> {                                      // ember rumble + crackle
                var cpos = (Math.random() * sr).toInt()
                var clen = 0; var camp = 0f
                for (i in 0 until n) {
                    lp += 0.018f * ((Math.random().toFloat() * 2f - 1f) - lp)
                    var s = lp * 9000f
                    if (clen > 0) { s += camp * (clen / 50f) * 7000f; clen-- }
                    if (i >= cpos) {
                        clen = 50; camp = if (Math.random() < 0.5f) -1f else 1f
                        cpos = i + ((0.10f + Math.random().toFloat() * 0.35f) * sr).toInt()
                    }
                    buf[i] = s.toInt().toShort()
                }
            }
            else -> {                                   // midnight crickets + faint wind
                val period = (1.15f * sr).toInt()
                val pulseLen = (0.02f * sr).toInt()
                val pulseGap = (0.055f * sr).toInt()
                for (i in 0 until n) {
                    lp += 0.03f * ((Math.random().toFloat() * 2f - 1f) - lp)
                    var s = lp * 3500f
                    val t = i % period
                    if (t < pulseGap * 5) {
                        val pt = t % pulseGap
                        if (pt < pulseLen)
                            s += sin(TAUf * 4300f * i / sr) * 5200f *
                                 sin(TAUf * 40f * pt / sr).coerceAtLeast(0f)
                    }
                    buf[i] = s.toInt().toShort()
                }
            }
        }
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
            t.write(buf, 0, buf.size)
            t.setLoopPoints(0, buf.size, -1)
            t
        } catch (e: Exception) { null }
    }

    // ---------- DSP helpers ----------
    private enum class Osc { SINE, TRI, SQUARE }
    private fun env(i: Int, n: Int) = exp(-6.9f * i / n)

    private fun tone(osc: Osc, f0: Float, f1: Float, dur: Float, vol: Float): FloatArray {
        val n = (dur * sr).toInt(); val out = FloatArray(n); var ph = 0f
        for (i in 0 until n) {
            val f = f0 + (f1 - f0) * i / n
            ph += TAUf * f / sr
            val s = when (osc) {
                Osc.SINE -> sin(ph)
                Osc.TRI -> 2f / PI.toFloat() * asin(sin(ph))
                Osc.SQUARE -> if (sin(ph) >= 0f) 1f else -1f
            }
            out[i] = s * vol * env(i, n)
        }
        return out
    }

    private fun noiseBuf(dur: Float, f0: Float, f1: Float, vol: Float, hp: Float = 0f): FloatArray {
        val n = (dur * sr).toInt(); val out = FloatArray(n)
        var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            val fc = f0 + (f1 - f0) * i / n
            val a = 1f - exp(-TAUf * fc / sr)
            lp += a * ((Math.random().toFloat() * 2f - 1f) - lp)
            var y = lp
            if (hp > 0f) {
                val a2 = 1f - exp(-TAUf * hp / sr)
                lp2 += a2 * (y - lp2); y -= lp2
            }
            out[i] = y * vol * env(i, n)
        }
        return out
    }

    private fun mix(vararg parts: Pair<Int, FloatArray>): FloatArray {
        var len = 0
        for ((off, a) in parts) len = maxOf(len, off + a.size)
        val out = FloatArray(len)
        for ((off, a) in parts) for (i in a.indices) out[off + i] += a[i]
        return out
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val n = pcm.size * 2; val b = ByteArray(44 + n)
        fun le(off: Int, v: Int, len: Int) { for (i in 0 until len) b[off + i] = (v shr (8 * i)).toByte() }
        "RIFF".toByteArray().copyInto(b, 0)
        le(4, 36 + n, 4); "WAVE".toByteArray().copyInto(b, 8)
        "fmt ".toByteArray().copyInto(b, 12); le(16, 16, 4)
        le(20, 1, 2); le(22, 1, 2); le(24, sr, 4); le(28, sr * 2, 4)
        le(32, 2, 2); le(34, 16, 2)
        "data".toByteArray().copyInto(b, 36); le(40, n, 4)
        for (i in pcm.indices) { val v = pcm[i].toInt(); b[44 + i * 2] = v.toByte(); b[45 + i * 2] = (v shr 8).toByte() }
        return b
    }

    private fun put(name: String, data: FloatArray) {
        val shorts = ShortArray(data.size)
        for (i in data.indices)
            shorts[i] = ((data[i].coerceIn(-1.4f, 1.4f) / 1.4f * 32000f).toInt()).toShort()
        val f = File(appContext.cacheDir, "$name.wav")
        try { f.writeBytes(wav(shorts)) } catch (e: Exception) { return }
        val id = sp.load(f.absolutePath, 1)
        pendingFiles[id] = f
        synchronized(lock) { ids[name] = id; nameById[id] = name }
    }

    private fun synthAll() {
        try {
            put("thud", mix(0 to tone(Osc.SINE, 120f, 44f, 0.13f, 0.55f),
                            0 to noiseBuf(0.09f, 300f, 300f, 0.5f)))
            put("crack", mix(0 to noiseBuf(0.06f, 1000f, 1600f, 0.45f, hp = 500f),
                             0 to tone(Osc.SQUARE, 240f, 150f, 0.04f, 0.06f)))
            val sh = ArrayList<Pair<Int, FloatArray>>()
            for (i in 0 until 5) sh.add(((i * 0.02f) * sr).toInt() to
                tone(Osc.TRI, 1400f + i * 450f, 1800f + i * 450f, 0.06f, 0.16f))
            sh.add(0 to noiseBuf(0.12f, 3500f, 3800f, 0.22f, hp = 2000f))
            put("shatter", mix(*sh.toTypedArray()))
            put("boom", mix(0 to noiseBuf(0.5f, 420f, 60f, 0.95f),
                            0 to tone(Osc.SINE, 110f, 32f, 0.45f, 0.8f)))
            put("bigboom", mix(0 to noiseBuf(0.85f, 380f, 40f, 1.0f),
                               0 to tone(Osc.SINE, 82f, 24f, 0.8f, 0.9f),
                               0 to noiseBuf(0.2f, 900f, 200f, 0.5f)))
            val penta = intArrayOf(0, 2, 4, 7, 9)
            for (i in 0 until 10) {
                val st = penta[i % 5] + 12 * (i / 5)
                val f = 523.25f * 2f.pow(st / 12f)
                put("ding$i", mix(0 to tone(Osc.TRI, f, f, 0.16f, 0.3f),
                                  0 to tone(Osc.SINE, f * 2f, f * 2f, 0.1f, 0.1f)))
            }
            val c1 = 523.25f
            put("chord", mix(
                0 to tone(Osc.TRI, c1, c1, 0.25f, 0.22f),
                (0.07f * sr).toInt() to tone(Osc.TRI, c1 * 1.122f, c1 * 1.122f, 0.25f, 0.22f),
                (0.14f * sr).toInt() to tone(Osc.TRI, c1 * 1.26f, c1 * 1.26f, 0.25f, 0.22f)))
            put("jump", tone(Osc.SINE, 260f, 560f, 0.13f, 0.2f))
            put("slamFall", noiseBuf(0.3f, 1900f, 260f, 0.35f, hp = 900f))
            put("slamHit", mix(0 to noiseBuf(0.22f, 900f, 110f, 0.6f),
                               0 to tone(Osc.SINE, 90f, 34f, 0.3f, 0.6f),
                               0 to noiseBuf(0.09f, 300f, 300f, 0.55f)))
            put("fuse", mix(0 to noiseBuf(0.45f, 3800f, 5200f, 0.28f, hp = 1800f),
                            0 to tone(Osc.SINE, 2200f, 2600f, 0.3f, 0.03f)))
            put("creak", mix(0 to tone(Osc.TRI, 190f, 120f, 0.22f, 0.22f),
                             (0.06f * sr).toInt() to tone(Osc.TRI, 150f, 95f, 0.18f, 0.16f)))
            put("rumble", mix(0 to noiseBuf(0.75f, 130f, 55f, 0.75f),
                              0 to tone(Osc.SINE, 62f, 28f, 0.65f, 0.5f)))
            put("pop", mix(0 to tone(Osc.SINE, 620f, 180f, 0.09f, 0.45f),
                           0 to noiseBuf(0.03f, 1500f, 1500f, 0.2f)))
            put("boing", mix(0 to tone(Osc.SINE, 140f, 520f, 0.16f, 0.35f),
                             0 to tone(Osc.TRI, 280f, 1040f, 0.12f, 0.12f)))
            put("buzz", mix(0 to tone(Osc.SQUARE, 210f, 190f, 0.5f, 0.12f),
                            0 to tone(Osc.SQUARE, 330f, 300f, 0.5f, 0.08f)))
            put("shoot", mix(0 to noiseBuf(0.14f, 1200f, 200f, 0.5f, hp = 400f),
                             0 to tone(Osc.SINE, 160f, 60f, 0.12f, 0.4f)))
            put("hurt", mix(0 to tone(Osc.TRI, 300f, 110f, 0.25f, 0.5f),
                            0 to noiseBuf(0.1f, 500f, 200f, 0.35f)))
            put("shieldhit", mix(0 to tone(Osc.TRI, 900f, 600f, 0.12f, 0.4f),
                                 0 to tone(Osc.SINE, 1800f, 1400f, 0.08f, 0.15f)))
            put("pickup", mix(0 to tone(Osc.TRI, 660f, 990f, 0.12f, 0.3f),
                              (0.06f * sr).toInt() to tone(Osc.TRI, 990f, 1320f, 0.1f, 0.22f)))
            put("heal", mix(0 to tone(Osc.TRI, 523f, 523f, 0.12f, 0.3f),
                            (0.09f * sr).toInt() to tone(Osc.TRI, 784f, 784f, 0.16f, 0.3f)))
            put("over", mix(0 to tone(Osc.TRI, 392f, 392f, 0.22f, 0.35f),
                            (0.18f * sr).toInt() to tone(Osc.TRI, 311f, 311f, 0.22f, 0.35f),
                            (0.36f * sr).toInt() to tone(Osc.TRI, 233f, 220f, 0.4f, 0.4f)))
        } catch (e: Exception) { L.e("sfx synth failed", e) }
    }

    private fun initRoll() {
        if (roll != null) return
        try {
            val n = (0.8f * sr).toInt(); val buf = ShortArray(n); var lp = 0f
            for (i in 0 until n) {
                lp += 0.06f * ((Math.random().toFloat() * 2f - 1f) - lp)
                val wob = 0.75f + 0.25f * sin(TAUf * 3f * i / sr)
                buf[i] = (lp * wob * 16000f).toInt().toShort()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
            t.write(buf, 0, buf.size)
            t.setLoopPoints(0, buf.size, -1)
            roll = t
            applyRoll()
        } catch (e: Exception) { roll = null; L.e("roll loop failed", e) }
    }

    // ---------- playback API ----------
    fun play(name: String, vol: Float = 1f, rate: Float = 1f) {
        if (muted || focus == F.NONE) return
        val id = synchronized(lock) { ids[name]?.takeIf { name in loaded } } ?: return
        val v = (vol * if (focus == F.DUCK) 0.3f else 0.85f).coerceAtMost(1f)
        sp.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }
    fun thud(v: Float) { play("thud", v, 0.95f + Math.random().toFloat() * 0.1f) }
    fun crack(v: Float) { play("crack", v, 0.9f + Math.random().toFloat() * 0.25f) }

    fun setRoll(v: Float) { rollLevel = v; applyRoll() }

    fun pauseAll() {
        try { sp.autoPause() } catch (_: Exception) {}
        try { roll?.pause() } catch (_: Exception) {}
        ambientTracks.forEach { t -> try { t?.pause() } catch (_: Exception) {} }
    }
    fun resumeAll() {
        if (focus != F.FULL) return
        try { sp.autoResume() } catch (_: Exception) {}
        applyRoll(); applyAmbient()
    }
    fun release() {
        try { sp.release() } catch (_: Exception) {}
        try { roll?.release() } catch (_: Exception) {}
        ambientTracks.forEach { t -> try { t?.release() } catch (_: Exception) {} }
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
    }
}
