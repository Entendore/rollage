package com.rollage.game

import org.jbox2d.collision.Manifold
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.ContactListener
import org.jbox2d.dynamics.Filter
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact
import org.jbox2d.dynamics.contacts.ContactImpulse
import org.jbox2d.dynamics.joints.Joint
import org.jbox2d.dynamics.joints.RevoluteJointDef
import org.jbox2d.dynamics.joints.RopeJointDef
import org.jbox2d.dynamics.joints.WeldJointDef
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

// ---- riso palette ----
val C_PAPER = 0xFFF3EDDF.toInt(); val C_PAPER2 = 0xFFE9DFC8.toInt()
val C_INK = 0xFF26221C.toInt();   val C_VERM = 0xFFE2532F.toInt()
val C_TEAL = 0xFF177E74.toInt();  val C_MUSTARD = 0xFFEFB43C.toInt()
val C_STONE = 0xFFCBC0A5.toInt(); val C_GLASS = 0xFFBCE0D4.toInt()
val C_CLOUD = 0xFFFBF7EC.toInt(); val C_MESA1 = 0xFFE5DAC0.toInt()
val C_MESA2 = 0xFFD8CAAB.toInt(); val C_DUST = 0xFFCFC2A6.toInt()
val C_ICE = 0xFFBFE3EA.toInt();   val C_TNT = 0xFFC13A20.toInt()
val C_TNTDARK = 0xFF7E2413.toInt()
val C_SAND = 0xFFD9C08A.toInt();  val C_HIVED = 0xFFB98A2F.toInt()
val C_SNOW = 0xFFFFFFFF.toInt()

const val PX = 40f
const val VIEW_H = 740f
const val GROUND_Y = 585f
const val BALL_R = 27f
const val VPS = 60f / PX
val G_M = GROUND_Y / PX
val STEP = 1f / 60f

// ---- destruction tuning ----
const val CRUSH_MIN = 16f
const val CRUSH_SCALE = 0.9f
const val JOINT_BREAK = 26f
const val DEBRIS_ARM = 0.12f
const val DEBRIS_V = 7.5f
const val WAVE_SPEED = 13f
const val WAVE_MAX = 9f
const val CREEP = 3f
const val SPLIT_V = 13f
const val FRAG_CAP = 140
const val BALLOON_LIFT = 37f
const val BIOME_LEN = 650f

// ---- biomes ----
class Biome(val name: String, val announce: String,
            val mesa1: Int, val mesa2: Int, val accent: Int,
            val decor: Int,
            val weights: Map<String, Float>)

val BIOMES = listOf(
    Biome("DUNE SEA", "THE DUNE SEA",
        C_MESA1, C_MESA2, C_TEAL, 0, mapOf("barrelrow" to 1.2f)),
    Biome("RED CANYON", "RED CANYON AHEAD",
        0xFFE3B795.toInt(), 0xFFD0906A.toInt(), 0xFFB0512E.toInt(), 1,
        mapOf("wall" to 1.6f, "pyramid" to 1.4f, "barrelrow" to 1.6f,
              "arch" to 1.3f, "icehouse" to 0.3f, "spinner" to 0.7f)),
    Biome("FROSTBITE FLATS", "FROSTBITE FLATS",
        0xFFE8EEF0.toInt(), 0xFFCCDCE2.toInt(), C_TEAL, 2,
        mapOf("icehouse" to 1.9f, "dominoes" to 1.5f, "pendulum" to 1.2f,
              "tntcellar" to 0.35f, "boomnest" to 0.6f, "barrelrow" to 0.4f)),
    Biome("EMBER RIDGE", "EMBER RIDGE — MIND THE TNT",
        0xFFE0B49B.toInt(), 0xFFC08A70.toInt(), C_VERM, 3,
        mapOf("tntcellar" to 1.8f, "boomnest" to 1.5f, "barrelrow" to 1.4f,
              "sandbunker" to 1.2f, "icehouse" to 0.2f)),
    Biome("MOONLIT RUINS", "MOONLIT RUINS",
        0xFFCDC7DA.toInt(), 0xFFAFA6C4.toInt(), 0xFF6E5F9E.toInt(), 4,
        mapOf("pendulum" to 1.5f, "spinner" to 1.4f, "shelf" to 1.5f,
              "arch" to 1.3f, "beetree" to 1.4f, "balloons" to 1.2f)))

fun hash(i: Float): Float {
    val s = sin(i * 127.1f + 311.7f) * 43758.5453f
    return s - floor(s)
}
fun clamp(v: Float, a: Float, b: Float) = v.coerceIn(a, b)
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun rand(a: Float, b: Float) = a + Math.random().toFloat() * (b - a)

class GameWorld(private val sounds: SoundBank) {

    interface Events {
        fun onTier(text: String); fun onSlam(); fun onBoom(); fun onHurt()
    }
    var events: Events? = null

    enum class T { CRATE, PLANK, STONE, GLASS, BOOM, TNT, ICE, ORB, BONUS, ANVIL,
                   SAND, BARREL, HIVE, BALLOON, DOMINO }

    class Spec(val w: Float, val h: Float, val r: Float, val hp: Float, val score: Int,
               val col: Int, val dens: Float = 1f, val fric: Float = 0.45f,
               val rest: Float = 0.04f, val cap: Float = 26f)

    class Block(
        val type: T, val seed: Float, val w: Float, val h: Float, val r: Float,
        val col: Int, val score: Int, val statik: Boolean, val maxHp: Float,
        val cap: Float, val body: Body
    ) {
        var hp = maxHp; var flash = 0f; var dead = false
        var load = 0f; var fuse = -1f
        val strain: Float get() = if (cap > 500f) 0f else load / cap
    }

    /** Debris. gen 0 may secondary-fragment. lethal/sting fragments hurt the ball. */
    class Frag(val body: Body, val col: Int, val born: Float, val sz: Float,
               val circle: Boolean, var gen: Int,
               var lethal: Boolean = false, var sting: Boolean = false) {
        var life = 0f; var dead = false
    }
    class Ramp(val body: Body, val w: Float)
    class Weld(val j: Joint, val a: Body, val b: Body)
    class Prop(val body: Body, val w: Float, val h: Float, val kind: Char = 's')
    class Rope(val j: Joint, val a: Body, val b: Body)
    class Cannon(val body: Body, val x: Float, val y: Float, var cd: Float)
    class Pickup(val x: Float, val y: Float, val kind: Char)   // 'h' heart 's' shield 't' slow

    class Wave(var x: Float, val dir: Float) {
        var traveled = 0f
        val hit = HashSet<Block>()
    }

    class Part(
        val k: Char, var x: Float, var y: Float,
        var vx: Float = 0f, var vy: Float = 0f, var rot: Float = 0f, var vr: Float = 0f,
        val s: Float = 0f, val lw: Float = 0f, val max: Float = 1f,
        val col: Int = C_INK, var life: Float = 0f
    )
    class Pop(val x: Float, val y: Float, val txt: String, val size: Float,
              val max: Float, val col: Int, var life: Float)

    private class Hit(val a: Body, val b: Body, val rel: Float, val hasBall: Boolean)
    private class Crush(val a: Body, val b: Body, val imp: Float)

    val world = World(Vec2(0f, 28.75f))
    lateinit var ball: Body

    val specs = mapOf(
        T.CRATE   to Spec(1.55f, 1.55f, 0f,    70f, 25,  C_MUSTARD),
        T.PLANK   to Spec(3.30f, 0.55f, 0f,    48f, 15,  C_TEAL,    cap = 40f),
        T.STONE   to Spec(1.60f, 1.60f, 0f,   170f, 40,  C_STONE,   dens = 1.45f, cap = 70f),
        T.GLASS   to Spec(1.35f, 1.35f, 0f,    16f, 12,  C_GLASS,   cap = 9f),
        T.BOOM    to Spec(1.40f, 1.40f, 0f,    34f, 35,  C_VERM,    cap = 18f),
        T.TNT     to Spec(1.70f, 1.70f, 0f,    40f, 45,  C_TNT,     dens = 1.1f, cap = 24f),
        T.ICE     to Spec(1.45f, 1.45f, 0f,    22f, 18,  C_ICE,     dens = 0.9f,
                           fric = 0.02f, rest = 0.10f, cap = 12f),
        T.ORB     to Spec(0f, 0f, 0.55f,       26f, 120, C_VERM,    cap = 18f),
        T.BONUS   to Spec(0f, 0f, 0.50f,       24f, 60,  C_MUSTARD, cap = 999f),
        T.ANVIL   to Spec(0f, 0f, 0.85f,      380f, 40,  C_STONE,   dens = 3.0f, cap = 999f),
        T.SAND    to Spec(1.50f, 0.95f, 0f,   130f, 30,  C_SAND,    dens = 1.6f, cap = 55f),
        T.BARREL  to Spec(0f, 0f, 0.52f,      40f, 40,  C_TNT,     dens = 0.85f, cap = 40f),
        T.HIVE    to Spec(1.15f, 1.15f, 0f,    20f, 35,  C_MUSTARD, cap = 12f),
        T.BALLOON to Spec(0f, 0f, 0.62f,        8f, 30,  C_TEAL,    dens = 0.25f, cap = 999f),
        T.DOMINO  to Spec(0.42f, 2.30f, 0f,    28f, 15,  C_PAPER,   dens = 0.9f, cap = 20f))

    @Volatile var playing = false
    var score = 0; var shownScore = 0f
    var combo = 0; var comboT = 0f
    var shake = 0f; var slowmoT = 0f; var timeScale = 1f
    var distM = 0f; var nextMile = 250
    var spawnX = 17f
    var slamT = 0f; var sq = 0f; var dustT = 0f; var dustT2 = 0f
    var hintStage = 0; var hintT = 0f
    @Volatile var hintMsg: String? = null
    @Volatile var tutDone = false
    var annText: String? = null; var annT = 10f
    var camX = -0.75f; var camY = 0f
    @Volatile var viewWM = 8.5f
    var simTime = 0f; var lastGroundAt = -1e9f
    var prevGround = false; var prevVy = 0f
    @Volatile var keyDir = 0f

    // ---- run vitals ----
    @Volatile var hearts = MAX_HEARTS
    @Volatile var iframes = 0f
    var shield = 0
    @Volatile var dying = false; private var dieT = 0f
    @Volatile var over = false
    @Volatile var boostNext = false          // rewarded shield for the next run
    var scoreDoubled = false                 // rewarded 2x on the results screen
    var blocksSmashed = 0; var maxCombo = 0
    var biomeIdx = 0

    val blocks = ArrayList<Block>(); val frags = ArrayList<Frag>()
    val parts = ArrayList<Part>();   val pops = ArrayList<Pop>()
    val ramps = ArrayList<Ramp>();   val waves = ArrayList<Wave>()
    val props = ArrayList<Prop>();   val ropes = ArrayList<Rope>()
    val cannons = ArrayList<Cannon>()
    val pickups = ArrayList<Pickup>()
    private val welds = ArrayList<Weld>()

    private val toRemove = ArrayList<Body>()
    private val hits = ArrayList<Hit>()
    private val crushQ = ArrayList<Crush>()
    private val splitQ = ArrayList<Frag>()
    private val dragQ = ConcurrentLinkedQueue<Float>()
    private val cmdQ = ConcurrentLinkedQueue<Char>()
    private var acc = 0f; private var bid = 0f; private var cleanT = 0f
    private var thudBudget = 3; private var creakBudget = 2
    private var fuseSparkT = 0f; private var ambientT = 0f
    private var recentMass = 0f; private var massT = 0f
    private var setIdx = 0; private var nextSetM = 320f
    private var curBiome: Biome = BIOMES[0]
    private var nextPickM = 90f

    private val tierTexts = mapOf(5 to "NICE!", 8 to "SUPER!", 12 to "MEGA!",
                                  17 to "ULTRA!", 24 to "WRECKER!!")
    private val BP = 1.6f

    private fun pushPart(q: Part) { if (parts.size > 380) parts.removeAt(0); parts.add(q) }
    private fun pushPop(q: Pop)  { if (pops.size > 48)  pops.removeAt(0);  pops.add(q)  }

    fun biomeAtX(xm: Float): Biome {
        val i = floor(xm / BIOME_LEN).toInt()
        if (i < 0) return BIOMES[0]
        return BIOMES[i % BIOMES.size]
    }

    init {
        world.setContinuousPhysics(true)
        staticBox(0f, G_M + 7.5f, 12500f, 7.5f, 0.8f)

        val bd = BodyDef()
        bd.type = BodyType.DYNAMIC
        bd.position.set(4.5f, G_M - 4f)
        bd.bullet = true; bd.linearDamping = 0.5f; bd.angularDamping = 0.8f
        ball = world.createBody(bd)
        val cs = CircleShape(); cs.radius = BALL_R / PX
        val fd = FixtureDef()
        fd.shape = cs; fd.density = 3.8f; fd.friction = 0.35f; fd.restitution = 0.12f
        ball.createFixture(fd)

        world.setContactListener(object : ContactListener() {
            override fun beginContact(c: Contact) {
                val a = c.fixtureA.body; val b = c.fixtureB.body
                val pa = a.userData as? Prop; val pb = b.userData as? Prop
                if ((pa != null && pa.kind == 'b' && b === ball) ||
                    (pb != null && pb.kind == 'b' && a === ball)) {
                    sounds.play("boing"); sq = 0.9f
                }
                val rel = hypot(a.linearVelocity.x - b.linearVelocity.x,
                                a.linearVelocity.y - b.linearVelocity.y)
                if (rel > 0.4f && hits.size < 220)
                    hits.add(Hit(a, b, rel, a === ball || b === ball))
                if (rel > SPLIT_V) {
                    val fa = a.userData as? Frag
                    val fb = b.userData as? Frag
                    if (fa != null && fa.gen == 0 && b.isStatic && b.userData == null) splitQ.add(fa)
                    else if (fb != null && fb.gen == 0 && a.isStatic && a.userData == null) splitQ.add(fb)
                }
            }
            override fun endContact(c: Contact) {}
            override fun preSolve(c: Contact, m: Manifold) {}
            override fun postSolve(c: Contact, ci: ContactImpulse) {
                var imp = 0f
                for (v in ci.normalImpulses) imp += v
                if (imp > 0.5f && crushQ.size < 400)
                    crushQ.add(Crush(c.fixtureA.body, c.fixtureB.body, imp))
            }
        })

        while (spawnX < viewWM * 1.7f + 7.5f) buildNext()
    }

    // ---------- input ----------
    fun offerDrag(dxVirtual: Float) { dragQ.offer(dxVirtual) }
    fun offerCmd(c: Char) { cmdQ.offer(c) }
    fun clearInput() { dragQ.clear(); cmdQ.clear() }

    private fun processInput() {
        var d = 0f
        while (true) { val v = dragQ.poll() ?: break; d += v }
        if (d != 0f && playing) {
            ball.setAwake(true)
            ball.linearVelocity = Vec2(
                clamp(ball.linearVelocity.x + d * 0.93f, -31.5f, 31.5f),
                ball.linearVelocity.y)
        }
        while (true) {
            when (cmdQ.poll() ?: break) {
                'T' -> tapAction(); 'J' -> jump(); 'S' -> doSlam()
                'R' -> reset(); 'M' -> { reset(); playing = false }
            }
        }
    }

    fun tapAction() { if (simTime - lastGroundAt < 0.13f) jump() else doSlam() }

    fun jump() {
        if (!playing || simTime - lastGroundAt > 0.13f) return
        ball.setAwake(true)
        ball.linearVelocity = Vec2(ball.linearVelocity.x, -24f)
        sounds.play("jump"); sq = -0.6f; lastGroundAt = -1e9f
    }

    fun doSlam() {
        if (!playing || simTime - lastGroundAt < 0.14f) return
        ball.setAwake(true)
        ball.linearVelocity = Vec2(ball.linearVelocity.x * 0.5f, 39f)
        slamT = 0.8f; sounds.play("slamFall")
        if (hintStage == 1) { hintStage = 2; hintMsg = null; tutDone = true }
    }

    // ---------- vitals: hurt / shield / death / revive ----------
    fun hurtBall() {
        if (dying || over || !playing) return
        if (shield > 0) {
            shield = 0
            iframes = max(iframes, 0.5f)
            val p = ball.position
            pushPart(Part('R', p.x * PX, p.y * PX, vr = 260f, s = 26f, lw = 6f,
                          max = 0.45f, col = C_TEAL))
            pushPop(Pop(p.x * PX, p.y * PX - 50f, "BLOCKED!", 20f, 0.8f, C_TEAL, 0f))
            sounds.play("shieldhit")
            return
        }
        if (iframes > 0f) return
        hearts--; iframes = 1.5f
        shake = max(shake, 11f)
        sounds.play("hurt"); events?.onHurt()
        val p = ball.position
        pushPop(Pop(p.x * PX, p.y * PX - 54f, "-1 HEART", 20f, 0.9f, C_VERM, 0f))
        for (i in 0 until 10) pushPart(Part('S', p.x * PX, p.y * PX,
            rand(-6f, 6f), rand(-7f, 2f), rand(0f, 6.28f), rand(-8f, 8f),
            s = rand(3f, 6f), max = rand(0.3f, 0.5f), col = C_VERM))
        if (hearts <= 0) startDeath()
    }

    private fun startDeath() {
        dying = true; dieT = 1.15f
        playing = false
        slowmoT = 1.2f                       // long dramatic dive (clamped in timeScale)
        shake = max(shake, 15f)
        announce("WRECKED!!"); sounds.play("over"); sounds.setRoll(0f)
        val p = ball.position
        for (i in 0 until 24) pushPart(Part('S', p.x * PX, p.y * PX,
            rand(-9f, 9f), rand(-10f, 3f), rand(0f, 6.28f), rand(-9f, 9f),
            s = rand(3f, 7f), max = rand(0.4f, 0.9f),
            col = if (Math.random() < 0.5f) C_INK else C_VERM))
    }

    private fun killFrag(f: Frag) {
        if (f.dead) return
        f.dead = true
        frags.remove(f)
        toRemove.add(f.body)
        val b = f.body
        for (i in 0 until 4) pushPart(Part('S', b.position.x * PX, b.position.y * PX,
            rand(-5f, 5f), rand(-5f, 2f), rand(0f, 6.28f), rand(-7f, 7f),
            s = rand(2f, 4f), max = rand(0.2f, 0.4f), col = f.col))
    }

    fun doubleScore(): Boolean {
        if (scoreDoubled || score <= 0) return false
        scoreDoubled = true
        score *= 2
        return true
    }

    /** Rewarded revive: one heart, 3 s of i-frames, lethal debris cleared. */
    fun revive() {
        hearts = 1; iframes = 3f; shield = 0
        dying = false; dieT = 0f; over = false; playing = true
        slowmoT = 0f; shake = 0f
        val bp = ball.position
        val fit = frags.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            if ((f.lethal || f.sting) &&
                hypot(f.body.position.x - bp.x, f.body.position.y - bp.y) < 3f) {
                f.dead = true; toRemove.add(f.body); fit.remove()
            }
        }
        for (bl in blocks) {                  // defuse nearby cook-offs: no instant re-death
            if (bl.dead || bl.fuse < 0f) continue
            if (hypot(bl.body.position.x - bp.x, bl.body.position.y - bp.y) < 4.5f) bl.fuse = -1f
        }
        ball.setTransform(Vec2(bp.x, max(bp.y, G_M - 2.5f)), 0f)
        ball.linearVelocity = Vec2(0f, -2f); ball.angularVelocity = 0f
        ball.setAwake(true)
        announce("SECOND CHANCE!"); sounds.play("heal")
    }

    // ---------- joints ----------
    private fun weld(a: Body, b: Body) {
        val jd = WeldJointDef()
        jd.initialize(a, b, Vec2((a.position.x + b.position.x) / 2f,
                                 (a.position.y + b.position.y) / 2f))
        jd.frequencyHz = 5f; jd.dampingRatio = 0.35f
        try { welds.add(Weld(world.createJoint(jd), a, b)) } catch (_: Exception) {}
    }

    private fun breakWeldsAround(b: Body) {
        if (welds.isEmpty()) return
        val it = welds.iterator()
        while (it.hasNext()) {
            val wd = it.next()
            if (wd.a === b || wd.b === b) {
                try { world.destroyJoint(wd.j) } catch (_: Exception) {}
                it.remove()
            }
        }
    }

    // ---------- construction ----------
    private fun staticBox(x: Float, y: Float, hw: Float, hh: Float,
                          friction: Float, angle: Float = 0f, rest: Float = 0f): Body {
        val bd = BodyDef(); bd.type = BodyType.STATIC
        bd.position.set(x, y); bd.angle = angle
        val b = world.createBody(bd)
        val ps = PolygonShape(); ps.setAsBox(hw, hh)
        val fd = FixtureDef(); fd.shape = ps; fd.friction = friction; fd.restitution = rest
        b.createFixture(fd)
        return b
    }

    private fun addBlock(type: T, x: Float, y: Float): Block {
        bid += 1f
        val seed = hash(bid * 7.13f)
        val sp = specs[type]!!
        val bd = BodyDef()
        bd.type = if (type == T.BONUS) BodyType.STATIC else BodyType.DYNAMIC
        bd.position.set(x, y)
        if (type == T.BALLOON) bd.linearDamping = 1.2f
        val body = world.createBody(bd)
        val fd = FixtureDef()
        if (sp.r > 0f) { val cs = CircleShape(); cs.radius = sp.r; fd.shape = cs }
        else { val ps = PolygonShape(); ps.setAsBox(sp.w / 2f, sp.h / 2f); fd.shape = ps }
        fd.friction = sp.fric; fd.restitution = sp.rest; fd.density = sp.dens
        body.createFixture(fd)
        val bl = Block(type, seed, sp.w, sp.h, sp.r, sp.col, sp.score,
                       type == T.BONUS, sp.hp, sp.cap, body)
        body.userData = bl
        blocks.add(bl)
        return bl
    }

    private fun crateLike(xm: Float): T {
        val b = biomeAtX(xm)
        return when {
            b.decor == 2 && Math.random() < 0.35f -> T.ICE
            b.decor == 3 && Math.random() < 0.14f -> T.BOOM
            else -> T.CRATE
        }
    }

    // ---------- patterns ----------
    private fun pCrates(x: Float, d: Float): Float {
        val cols = 2 + floor(rand(0f, if (d > 0.4f) 3f else 2f)).toInt()
        val rows = 1 + floor(rand(0f, if (d > 0.3f) 3f else 2f)).toInt()
        for (c in 0 until cols) for (r in 0 until rows)
            addBlock(crateLike(x), x + 0.8f + c * BP, G_M - 0.775f - r * 1.6f)
        if (Math.random() < 0.35)
            addBlock(T.GLASS, x + cols * 0.8f, G_M - 0.775f - rows * 1.6f)
        return cols * BP
    }

    private fun pTower(x: Float, d: Float): Float {
        val lv = 3 + floor(rand(0f, if (d > 0.5f) 3f else 2f)).toInt()
        var y = G_M
        var prevP: Body? = null
        for (i in 0 until lv) {
            val bl1 = addBlock(crateLike(x), x - 0.85f, y - 0.775f)
            val bl2 = addBlock(crateLike(x), x + 0.85f, y - 0.775f)
            prevP?.let { weld(bl1.body, it); weld(bl2.body, it) }
            val pl = addBlock(T.PLANK, x, y - 1.84f)
            weld(bl1.body, pl.body); weld(bl2.body, pl.body)
            prevP = pl.body
            y -= 2.15f
        }
        val roll = Math.random()
        val top = when {
            roll < 0.40 -> addBlock(T.ORB,   x, y - 0.56f)
            roll < 0.72 -> addBlock(crateLike(x), x, y - 0.775f)
            roll < 0.86 -> addBlock(T.BOOM,  x, y - 0.70f)
            else        -> addBlock(T.TNT,   x, y - 0.85f)
        }
        prevP?.let { weld(top.body, it) }
        return 4.75f
    }

    private fun pPyramid(x: Float, d: Float): Float {
        val rows = if (d > 0.5f) 5 else 4
        val wpx = rows * BP
        var below: List<Body>? = null
        for (r in 0 until rows) {
            val n = rows - r
            val rowBodies = ArrayList<Body>(n)
            for (c in 0 until n)
                rowBodies.add(addBlock(crateLike(x),
                    x + wpx / 2 - n * 0.8f + c * 1.6f + 0.8f,
                    G_M - 0.775f - r * 1.6f).body)
            below?.let { prev ->
                for (b in rowBodies) {
                    var best: Body? = null; var bd = 99f
                    for (p in prev) {
                        val dd = abs(p.position.x - b.position.x)
                        if (dd < bd) { bd = dd; best = p }
                    }
                    best?.let { weld(b, it) }
                }
            }
            below = rowBodies
        }
        return wpx
    }

    private fun pWall(x: Float): Float {
        val rows = 3 + floor(rand(0f, 2f)).toInt()
        var prevL: Body? = null; var prevR: Body? = null
        for (r in 0 until rows) {
            val sl = addBlock(T.STONE, x - 1.65f, G_M - 0.8f - r * 1.65f)
            val sr = addBlock(T.STONE, x + 1.65f, G_M - 0.8f - r * 1.65f)
            val gl = addBlock(T.GLASS, x, G_M - 0.675f - r * 1.36f)
            prevL?.let { weld(sl.body, it) }
            prevR?.let { weld(sr.body, it) }
            weld(gl.body, sl.body); weld(gl.body, sr.body)
            prevL = sl.body; prevR = sr.body
        }
        val pl = addBlock(T.PLANK, x, G_M - 1.9f - (rows - 1) * 1.65f)
        prevL?.let { weld(pl.body, it); weld(pl.body, prevR!!) }
        return 5.5f
    }

    private fun pBoomNest(x: Float): Float {
        addBlock(T.BOOM, x, G_M - 0.7f)
        val c1 = addBlock(T.CRATE, x - 1.6f, G_M - 0.775f)
        val c2 = addBlock(T.CRATE, x + 1.6f, G_M - 0.775f)
        val c3 = addBlock(T.CRATE, x, G_M - 2.25f)
        val p1 = addBlock(T.PLANK, x - 1.7f, G_M - 3.275f)
        val p2 = addBlock(T.PLANK, x + 1.7f, G_M - 3.275f)
        weld(c3.body, c1.body); weld(c3.body, c2.body)
        weld(p1.body, c3.body); weld(p2.body, c3.body)
        return 5.25f
    }

    private fun pVases(x: Float): Float {
        val n = 2 + floor(rand(0f, 2f)).toInt()
        for (i in 0 until n) {
            val px = x + 1.0f + i * 2.4f
            addBlock(T.STONE, px, G_M - 0.8f)
            addBlock(T.ORB, px, G_M - 2.2f)
        }
        return n * 2.4f + 1.0f
    }

    private fun pIcehouse(x: Float): Float {
        val s1 = addBlock(T.STONE, x - 1.2f, G_M - 0.8f)
        val s2 = addBlock(T.STONE, x + 1.2f, G_M - 0.8f)
        val i1 = addBlock(T.ICE, x - 0.75f, G_M - 2.325f)
        val i2 = addBlock(T.ICE, x + 0.75f, G_M - 2.325f)
        val i3 = addBlock(T.ICE, x, G_M - 3.775f)
        weld(i1.body, s1.body); weld(i2.body, s2.body)
        weld(i3.body, i1.body); weld(i3.body, i2.body)
        addBlock(T.GLASS, x - 0.7f, G_M - 5.175f)
        addBlock(T.GLASS, x + 0.7f, G_M - 5.175f)
        addBlock(T.ORB, x, G_M - 6.4f)
        return 4.4f
    }

    private fun pTntCellar(x: Float): Float {
        val t1 = addBlock(T.TNT, x - 1.75f, G_M - 0.85f)
        val t2 = addBlock(T.TNT, x, G_M - 0.85f)
        val t3 = addBlock(T.TNT, x + 1.75f, G_M - 0.85f)
        val roof = addBlock(T.PLANK, x, G_M - 1.9f)
        val c1 = addBlock(T.CRATE, x - 0.85f, G_M - 2.95f)
        val c2 = addBlock(T.CRATE, x + 0.85f, G_M - 2.95f)
        val cap = addBlock(T.STONE, x, G_M - 4.55f)
        weld(roof.body, t1.body); weld(roof.body, t2.body); weld(roof.body, t3.body)
        weld(cap.body, c1.body); weld(cap.body, c2.body)
        return 5.6f
    }

    private fun pPendulum(x: Float): Float {
        val post = staticBox(x - 2.6f, G_M - 1.7f, 0.28f, 1.7f, 0.6f)
        val arm  = staticBox(x - 1.7f, G_M - 3.35f, 1.2f, 0.2f, 0.6f)
        props.add(Prop(post, 0.56f, 3.4f)); props.add(Prop(arm, 2.4f, 0.4f))
        val peg = addBlock(T.GLASS, x - 0.65f, G_M - 3.35f)
        weld(peg.body, arm)
        val anvil = addBlock(T.ANVIL, x - 0.65f, G_M - 3.35f - 2.3f)
        val rjd = RopeJointDef()
        rjd.bodyA = peg.body; rjd.bodyB = anvil.body
        rjd.localAnchorA.set(0f, 0f); rjd.localAnchorB.set(0f, 0f)
        rjd.maxLength = 2.3f
        try { ropes.add(Rope(world.createJoint(rjd), peg.body, anvil.body)) }
        catch (_: Exception) {}
        addBlock(T.CRATE, x + 0.3f, G_M - 0.775f)
        addBlock(T.CRATE, x + 0.3f, G_M - 2.375f)
        addBlock(T.ORB, x + 0.3f, G_M - 3.4f)
        addBlock(T.CRATE, x + 1.9f, G_M - 0.775f)
        addBlock(T.CRATE, x + 1.9f, G_M - 2.375f)
        return 6.0f
    }

    private fun pCatapult(x: Float): Float {
        val ful = staticBox(x - 0.9f, G_M - 0.45f, 0.35f, 0.45f, 0.5f)
        props.add(Prop(ful, 0.7f, 0.9f))
        val plank = addBlock(T.PLANK, x - 0.9f, G_M - 1.02f)
        plank.body.setTransform(plank.body.position, 0.374f)
        val rjd = RevoluteJointDef()
        rjd.initialize(ful, plank.body, Vec2(x - 0.9f, G_M - 1.02f))
        rjd.lowerAngle = -0.2f; rjd.upperAngle = 0.65f; rjd.enableLimit = true
        try { world.createJoint(rjd) } catch (_: Exception) {}
        val ca = cos(0.374f); val sa = sin(0.374f)
        val c1 = addBlock(T.CRATE, x - 0.9f + ca * 1.05f, G_M - 1.02f + sa * 1.05f - 1.02f)
        val c2 = addBlock(T.CRATE, x - 0.9f + ca * 1.5f,  G_M - 1.02f + sa * 1.5f - 1.02f)
        weld(c1.body, plank.body); weld(c2.body, plank.body)
        val orb = addBlock(T.ORB, c1.body.position.x, c1.body.position.y - 1.3f)
        weld(orb.body, c1.body)
        addBlock(T.GLASS, x + 2.6f, G_M - 0.675f)
        addBlock(T.CRATE, x + 3.4f, G_M - 0.775f)
        addBlock(T.CRATE, x + 3.4f, G_M - 2.375f)
        return 6.4f
    }

    private fun pDominoes(x: Float): Float {
        val n = 6 + floor(rand(0f, 3f)).toInt()
        for (i in 0 until n)
            addBlock(T.DOMINO, x + 0.4f + i * 1.02f, G_M - 1.15f)
        addBlock(T.CRATE, x + 0.4f + n * 1.02f, G_M - 0.775f)
        addBlock(T.ORB, x + 0.4f + n * 1.02f, G_M - 2.2f)
        return n * 1.02f + 2.2f
    }

    private fun pBalloons(x: Float): Float {
        for (i in 0..1) {
            val bx = x + 0.8f + i * 2.6f
            val bal = addBlock(T.BALLOON, bx, G_M - 6.6f)
            val orb = addBlock(T.ORB, bx, G_M - 3.6f)
            val rjd = RopeJointDef()
            rjd.bodyA = bal.body; rjd.bodyB = orb.body
            rjd.localAnchorA.set(0f, 0f); rjd.localAnchorB.set(0f, 0f)
            rjd.maxLength = 2.6f
            try { ropes.add(Rope(world.createJoint(rjd), bal.body, orb.body)) }
            catch (_: Exception) {}
            addBlock(T.GLASS, bx - 0.7f, G_M - 0.675f)
            addBlock(T.GLASS, bx + 0.7f, G_M - 0.675f)
        }
        return 6.2f
    }

    private fun pCannonFort(x: Float): Float {
        val s1 = addBlock(T.STONE, x - 1.8f, G_M - 0.8f)
        val s2 = addBlock(T.STONE, x + 1.8f, G_M - 0.8f)
        val roof = addBlock(T.PLANK, x, G_M - 1.9f)
        weld(roof.body, s1.body); weld(roof.body, s2.body)
        val cbody = staticBox(x, G_M - 2.55f, 0.5f, 0.35f, 0.5f)
        props.add(Prop(cbody, 1.0f, 0.7f, 'c'))
        cannons.add(Cannon(cbody, x, G_M - 2.55f, 1.8f))
        addBlock(T.TNT, x, G_M - 0.85f)
        addBlock(T.SAND, x - 3.2f, G_M - 0.475f)
        addBlock(T.SAND, x + 3.2f, G_M - 0.475f)
        addBlock(T.ORB, x + 3.2f, G_M - 1.75f)
        return 8.0f
    }

    private fun pSpinner(x: Float): Float {
        val hub = staticBox(x, G_M - 1.8f, 0.12f, 0.12f, 0.5f)
        props.add(Prop(hub, 0.4f, 0.4f, 'h'))
        val bd = BodyDef(); bd.type = BodyType.DYNAMIC
        bd.position.set(x, G_M - 1.8f)
        val cross = world.createBody(bd)
        val fd1 = FixtureDef()
        val p1 = PolygonShape(); p1.setAsBox(1.7f, 0.17f)
        fd1.shape = p1; fd1.density = 1.2f; fd1.friction = 0.4f
        cross.createFixture(fd1)
        val fd2 = FixtureDef()
        val p2 = PolygonShape(); p2.setAsBox(0.17f, 1.7f)
        fd2.shape = p2; fd2.density = 1.2f; fd2.friction = 0.4f
        cross.createFixture(fd2)
        val rjd = RevoluteJointDef()
        rjd.initialize(hub, cross, Vec2(x, G_M - 1.8f))
        rjd.enableMotor = true
        rjd.motorSpeed = (if (Math.random() < 0.5f) 1f else -1f) * rand(2.6f, 3.4f)
        rjd.maxMotorTorque = 1200f
        try { world.createJoint(rjd) } catch (_: Exception) {}
        cross.userData = Prop(cross, 3.4f, 3.4f, 'w')
        props.add(cross.userData as Prop)
        addBlock(T.CRATE, x - 2.6f, G_M - 0.775f)
        addBlock(T.ORB, x - 2.6f, G_M - 2.2f)
        addBlock(T.CRATE, x + 2.6f, G_M - 0.775f)
        addBlock(T.ORB, x + 2.6f, G_M - 2.2f)
        return 7.0f
    }

    private fun pSandBunker(x: Float): Float {
        addBlock(T.SAND, x - 0.8f, G_M - 0.475f)
        addBlock(T.SAND, x + 0.8f, G_M - 0.475f)
        addBlock(T.SAND, x - 0.8f, G_M - 1.425f)
        addBlock(T.SAND, x + 0.8f, G_M - 1.425f)
        addBlock(T.ORB, x, G_M - 0.9f)
        addBlock(T.BOOM, x, G_M - 2.2f)
        addBlock(T.SAND, x - 0.8f, G_M - 2.6f)
        addBlock(T.SAND, x + 0.8f, G_M - 2.6f)
        return 4.4f
    }

    private fun pArch(x: Float): Float {
        var pl: Body? = null; var pr: Body? = null
        for (r in 0 until 3) {
            val sl = addBlock(T.STONE, x - 1.9f, G_M - 0.8f - r * 1.65f)
            val sr = addBlock(T.STONE, x + 1.9f, G_M - 0.8f - r * 1.65f)
            pl?.let { weld(sl.body, it) }; pr?.let { weld(sr.body, it) }
            pl = sl.body; pr = sr.body
        }
        val lin = addBlock(T.PLANK, x, G_M - 5.6f)
        weld(lin.body, pl!!); weld(lin.body, pr!!)
        addBlock(T.ORB, x, G_M - 6.7f)
        addBlock(T.GLASS, x - 0.9f, G_M - 0.675f)
        addBlock(T.GLASS, x + 0.9f, G_M - 0.675f)
        return 5.6f
    }

    private fun pBarrelRow(x: Float): Float {
        val n = 3 + floor(rand(0f, 3f)).toInt()
        for (i in 0 until n) {
            addBlock(T.BARREL, x + 0.6f + i * 1.35f, G_M - 0.52f)
            if (i % 2 == 1) addBlock(T.CRATE, x + 0.6f + i * 1.35f - 0.67f, G_M - 0.775f)
        }
        return n * 1.35f + 1.6f
    }

    private fun pBeeTree(x: Float): Float {
        val t1 = addBlock(T.STONE, x, G_M - 0.8f)
        val t2 = addBlock(T.STONE, x, G_M - 2.45f)
        weld(t2.body, t1.body)
        addBlock(T.HIVE, x + 1.0f, G_M - 2.5f)
        addBlock(T.GLASS, x - 0.95f, G_M - 0.675f)
        return 3.6f
    }

    private fun pShelf(x: Float): Float {
        val sh = staticBox(x, G_M - 3.2f, 1.2f, 0.12f, 0.6f)
        props.add(Prop(sh, 2.4f, 0.24f, 'p'))
        addBlock(T.BONUS, x - 0.6f, G_M - 3.9f)
        addBlock(T.BONUS, x + 0.6f, G_M - 3.9f)
        if (Math.random() < 0.35f) pickups.add(Pickup(x, G_M - 4.6f, 's'))
        addBlock(T.GLASS, x, G_M - 0.675f)
        addBlock(T.GLASS, x, G_M - 2.05f)
        return 3.6f
    }

    private fun pPads(x: Float): Float {
        val b1 = staticBox(x, G_M - 0.12f, 0.7f, 0.12f, 0.4f, rest = 1.9f)
        props.add(Prop(b1, 2.8f, 1.0f, 'b'))
        val b2 = staticBox(x + 3.2f, G_M - 0.12f, 0.7f, 0.12f, 0.4f, rest = 1.9f)
        props.add(Prop(b2, 2.8f, 1.0f, 'b'))
        addBlock(T.BONUS, x, G_M - 3.6f)
        addBlock(T.ORB, x + 3.2f, G_M - 4.6f)
        return 5.4f
    }

    private fun pRampRun(x: Float, d: Float): Float {
        ramps.add(Ramp(staticBox(x + 3.25f, G_M - 0.75f, 3.375f, 0.325f, 0.05f, -0.34f), 6.75f))
        for (i in 0 until 3) addBlock(T.BONUS, x + 8.5f + i * 2.375f, G_M - 4.75f - i * 0.85f)
        val lw = pCrates(x + 10.75f, d)
        return 10.75f + lw + 1.0f
    }

    // ---------- setpieces (every 420 m) ----------
    private fun buildSetpiece() {
        val x = spawnX
        when (setIdx % 5) {
            0 -> {
                var y = G_M; var prev: Body? = null
                for (lv in 0 until 6) {
                    val l = if (lv % 2 == 0) T.CRATE else T.STONE
                    val b1 = addBlock(l, x - 0.9f, y - 0.8f)
                    val b2 = addBlock(l, x + 0.9f, y - 0.8f)
                    prev?.let { weld(b1.body, it); weld(b2.body, it) }
                    val pl = addBlock(T.PLANK, x, y - 1.9f)
                    weld(b1.body, pl.body); weld(b2.body, pl.body)
                    prev = pl.body; y -= 2.2f
                }
                addBlock(T.ORB, x, y - 0.6f)
                addBlock(T.BOOM, x + 2.4f, G_M - 0.7f)
                spawnX += 7.5f
            }
            1 -> {
                var pl: Body? = null; var pr: Body? = null
                for (r in 0 until 8) {
                    val sl = addBlock(T.STONE, x - 1.7f, G_M - 0.8f - r * 1.65f)
                    val sr = addBlock(T.STONE, x + 1.7f, G_M - 0.8f - r * 1.65f)
                    pl?.let { weld(sl.body, it) }; pr?.let { weld(sr.body, it) }
                    pl = sl.body; pr = sr.body
                    addBlock(T.GLASS, x, G_M - 0.675f - r * 1.36f)
                }
                spawnX += 7.0f
            }
            2 -> {
                pCannonFort(x)
                addBlock(T.SAND, x - 3.2f, G_M - 1.425f)
                addBlock(T.BARREL, x + 4.6f, G_M - 0.52f)
                spawnX += 8.0f
            }
            3 -> {
                pArch(x)
                pBalloons(x + 6.4f)
                spawnX += 6.0f
            }
            else -> {
                for (i in 0..2) pBeeTree(x + i * 2.8f)
                addBlock(T.BARREL, x + 1.4f, G_M - 0.52f)
                addBlock(T.CRATE, x + 6.2f, G_M - 0.775f)
                addBlock(T.ORB, x + 6.2f, G_M - 2.2f)
                spawnX += 9.5f
            }
        }
        setIdx++
        nextSetM += 420f
        spawnX += 6f
    }

    // ---------- map generation ----------
    private fun pickPattern(x: Float): String {
        if (x < 37.5f) return "crates"
        val d = clamp(x / 400f, 0f, 1f)
        val bw = biomeAtX(x).weights
        fun w(name: String, base: Float) = base * (bw[name] ?: 1f)
        val list = listOf(
            "crates" to w("crates", 1.05f), "tower" to w("tower", 0.9f + d * 0.5f),
            "pyramid" to w("pyramid", 0.7f + d * 0.4f), "wall" to w("wall", 0.5f + d * 0.55f),
            "boomnest" to w("boomnest", 0.5f), "vases" to w("vases", 0.45f),
            "icehouse" to w("icehouse", 0.4f), "tntcellar" to w("tntcellar", 0.32f),
            "pendulum" to w("pendulum", 0.38f), "catapult" to w("catapult", 0.3f),
            "dominoes" to w("dominoes", 0.42f), "balloons" to w("balloons", 0.4f),
            "cannonfort" to w("cannonfort", 0.22f + d * 0.15f), "spinner" to w("spinner", 0.32f),
            "sandbunker" to w("sandbunker", 0.35f), "arch" to w("arch", 0.3f),
            "barrelrow" to w("barrelrow", 0.4f), "beetree" to w("beetree", 0.35f),
            "shelf" to w("shelf", 0.3f), "pads" to w("pads", 0.26f),
            "ramp" to w("ramp", 0.28f + d * 0.3f))
        var tot = 0f; for (e in list) tot += e.second
        var r = Math.random().toFloat() * tot
        for (e in list) { r -= e.second; if (r <= 0f) return e.first }
        return "crates"
    }

    private fun buildOne(type: String, x: Float, d: Float): Float = when (type) {
        "tower"     -> pTower(x, d)
        "pyramid"   -> pPyramid(x, d)
        "wall"      -> pWall(x)
        "boomnest"  -> pBoomNest(x)
        "vases"     -> pVases(x)
        "icehouse"  -> pIcehouse(x)
        "tntcellar" -> pTntCellar(x)
        "pendulum"  -> pPendulum(x)
        "catapult"  -> pCatapult(x)
        "dominoes"  -> pDominoes(x)
        "balloons"  -> pBalloons(x)
        "cannonfort"-> pCannonFort(x)
        "spinner"   -> pSpinner(x)
        "sandbunker"-> pSandBunker(x)
        "arch"      -> pArch(x)
        "barrelrow" -> pBarrelRow(x)
        "beetree"   -> pBeeTree(x)
        "shelf"     -> pShelf(x)
        "pads"      -> pPads(x)
        "ramp"      -> pRampRun(x, d)
        else        -> pCrates(x, d)
    }

    private fun maybePickup(cx: Float) {
        val forced = spawnX >= nextPickM
        if (!forced && Math.random() >= 0.20f) return
        val r = Math.random().toFloat()
        val kind = if (r < 0.34f) 'h' else if (r < 0.67f) 's' else 't'
        pickups.add(Pickup(cx + rand(0.5f, 2.5f), G_M - rand(2.3f, 4.8f), kind))
        nextPickM = spawnX + rand(110f, 150f)
    }

    private fun buildNext() {
        if (spawnX >= nextSetM) { buildSetpiece(); return }
        val d = clamp(spawnX / 400f, 0f, 1f)
        val type = pickPattern(spawnX)
        val wpx = buildOne(type, spawnX, d)
        maybePickup(spawnX + wpx * 0.5f)
        spawnX += wpx + lerp(10.75f, 7.25f, d) + rand(0f, 3.75f)
        if (Math.random() < 0.15f) {
            val small = listOf("crates", "boomnest", "vases", "dominoes", "barrelrow").random()
            val w2 = buildOne(small, spawnX, d)
            spawnX += w2 + lerp(10.75f, 7.25f, d) + rand(0f, 3.75f)
        }
    }

    // ---------- scoring / failure ----------
    private fun comboUp() {
        combo++; comboT = 1.7f
        if (combo > maxCombo) maxCombo = combo
        sounds.play("ding${combo % 10}")
        tierTexts[combo]?.let { announce(it); slowmoT = SLOW_DUR; events?.onTier(it) }
    }

    private fun wakeAround(xm: Float, ym: Float, r: Float) {
        var b = world.bodyList
        while (b != null) {
            val nxt = b.next
            if (!b.isStatic) {
                val dx = b.position.x - xm; val dy = b.position.y - ym
                if (dx * dx + dy * dy < r * r) b.setAwake(true)
            }
            b = nxt
        }
    }

    private fun damage(target: Body, other: Body, relMs: Float) {
        val bl = target.userData as? Block ?: return
        if (bl.dead || bl.statik) return
        if (other.userData is Frag) return
        val isBall = other === ball
        var dmg = relMs * if (isBall) 6f else 1.13f
        if (isBall && slamT > 0f) dmg *= 2.4f
        if (dmg < 3f) return
        bl.hp -= dmg; bl.flash = 1f
        if (bl.hp <= 0f) destroyBlock(bl)
        else if (bl.type == T.BOOM || bl.type == T.TNT) ignite(bl)
        else if (dmg > 14f && bl.type != T.GLASS) sounds.crack(clamp(dmg / 70f, 0f, 0.6f))
    }

    private fun ignite(bl: Block) {
        if (bl.fuse >= 0f) return
        bl.fuse = if (bl.type == T.TNT) rand(0.7f, 1.05f) else rand(0.4f, 0.7f)
        sounds.play("fuse")
    }

    private fun tryDebrisHit(frag: Body, target: Body, rel: Float, mark: Frag) {
        val bl = target.userData as? Block ?: return
        if (bl.dead || bl.statik) return
        if (simTime - mark.born < DEBRIS_ARM) return
        if (rel < DEBRIS_V) return
        val dmg = min(34f, 12f + rel * 1.1f)
        bl.hp -= dmg; bl.flash = 1f
        val px = frag.position.x * PX; val py = frag.position.y * PX
        for (i in 0 until 3) pushPart(Part('S', px, py, rand(-5f, 5f), rand(-6f, 1f),
            rand(0f, 6.28f), rand(-7f, 7f), s = rand(2f, 4f),
            max = rand(0.2f, 0.4f), col = mark.col))
        if (thudBudget > 0) { thudBudget--; sounds.crack(clamp(dmg / 50f, 0f, 0.5f)) }
        if (bl.hp <= 0f) destroyBlock(bl)
        else if (bl.type == T.BOOM || bl.type == T.TNT) ignite(bl)
    }

    // ---------- fragments & fracture ----------
    private fun fragBody(x: Float, y: Float, vel: Vec2, col: Int, circle: Boolean,
                         hw: Float, hh: Float,
                         dens: Float = 0.7f, rest: Float = 0.22f, gScale: Float = 1f): Frag {
        val bd = BodyDef(); bd.type = BodyType.DYNAMIC
        bd.position.set(x, y)
        val fb = world.createBody(bd)
        val fd = FixtureDef()
        fd.density = dens; fd.friction = 0.4f; fd.restitution = rest
        val fl = Filter(); fl.groupIndex = -7; fd.filter = fl
        if (circle) { val cs = CircleShape(); cs.radius = hw; fd.shape = cs }
        else { val ps = PolygonShape(); ps.setAsBox(hw, hh); fd.shape = ps }
        fb.createFixture(fd)
        fb.linearVelocity = Vec2(vel.x * 0.5f + rand(-7f, 7f) * VPS,
                                 vel.y * 0.4f - rand(2f, 9f) * VPS)
        fb.angularVelocity = rand(-15f, 15f)
        fb.gravityScale = gScale
        val f = Frag(fb, col, simTime, hw, circle, 0)
        fb.userData = f
        frags.add(f)
        return f
    }

    private fun spawnHalves(bl: Block, pos: Vec2, vel: Vec2): Boolean {
        val vert = bl.w >= bl.h
        val hw: Float; val hh: Float; val off: Float
        if (vert) { hw = bl.w * 0.25f * 0.94f; hh = bl.h * 0.5f; off = bl.w * 0.25f }
        else     { hw = bl.w * 0.5f;           hh = bl.h * 0.25f * 0.94f; off = bl.h * 0.25f }
        val th = bl.body.angle
        val ca = cos(th); val sa = sin(th)
        var ok = false
        for (s in floatArrayOf(-1f, 1f)) {
            if (frags.size > FRAG_CAP) break
            val lx = if (vert) s * off else 0f
            val ly = if (vert) 0f else s * off
            val bd = BodyDef(); bd.type = BodyType.DYNAMIC
            bd.position.set(pos.x + lx * ca - ly * sa, pos.y + lx * sa + ly * ca)
            bd.angle = th
            val body = world.createBody(bd)
            val fd = FixtureDef()
            fd.density = 0.9f; fd.friction = 0.45f; fd.restitution = 0.06f
            val fl = Filter(); fl.groupIndex = -7; fd.filter = fl
            val ps = PolygonShape(); ps.setAsBox(hw, hh); fd.shape = ps
            body.createFixture(fd)
            val nx = if (vert) ca * s else 0f
            val ny = if (vert) sa * s else s
            body.linearVelocity = Vec2(vel.x + nx * 2.4f, vel.y + ny * 2.4f)
            body.angularVelocity = -s * rand(2f, 6f)
            val f = Frag(body, bl.col, simTime, if (vert) hw else hh, false, 1)
            body.userData = f
            frags.add(f)
            ok = true
        }
        return ok
    }

    private fun spawnFragments(bl: Block, pos: Vec2, vel: Vec2) {
        val pxv = pos.x * PX; val pyv = pos.y * PX
        if (bl.type == T.HIVE) {
            sounds.play("buzz")
            for (i in 0 until 9) {
                if (frags.size > FRAG_CAP) break
                val bee = fragBody(pxv + rand(-10f, 10f), pyv + rand(-10f, 10f),
                                   Vec2(0f, 0f), C_MUSTARD, true,
                                   rand(0.10f, 0.14f), 0f, rest = 0.9f, gScale = 0.25f)
                bee.gen = 2; bee.sting = true
                bee.body.linearVelocity = Vec2(rand(-6f, 6f), -rand(3f, 8f))
                bee.body.angularVelocity = rand(-10f, 10f)
            }
            for (i in 0 until 6) pushPart(Part('P', pxv, pyv, rand(-2f, 2f), rand(-3f, -1f),
                s = rand(6f, 12f), max = 0.6f, col = C_MUSTARD))
            return
        }
        val halved = bl.r <= 0f && bl.type != T.BOOM && bl.type != T.TNT &&
                bl.type != T.SAND && spawnHalves(bl, pos, vel)
        val n = when {
            bl.type == T.ORB -> 7
            bl.type == T.SAND -> 8
            halved && bl.type == T.GLASS -> 4
            halved -> 3
            bl.type == T.ICE -> 8
            else -> 4
        }
        for (i in 0 until n) {
            if (frags.size > FRAG_CAP) { val o = frags.removeAt(0); o.dead = true; toRemove.add(o.body) }
            if (bl.r > 0f) fragBody(pxv + rand(-8f, 8f), pyv + rand(-8f, 8f),
                                    vel, bl.col, true, rand(5f, 10f) / PX, 0f)
            else { val s = rand(9f, 19f) / PX
                   fragBody(pxv + rand(-8f, 8f), pyv + rand(-8f, 8f),
                            vel, bl.col, false, s, s * rand(0.5f, 1f)) }
        }
        for (i in 0 until 5) pushPart(Part('P', pxv + rand(-14f, 14f), pyv + rand(-14f, 14f),
            rand(-2f, 2f), rand(-3f, -1f), s = rand(7f, 14f), max = rand(0.5f, 0.9f), col = C_DUST))
        for (i in 0 until 9) pushPart(Part('S', pxv, pyv,
            rand(-8f, 8f), rand(-9f, 3f), rand(0f, 6.28f), rand(-9f, 9f),
            s = rand(3f, 7f), max = rand(0.3f, 0.65f), col = bl.col))
    }

    private fun processSplits() {
        if (splitQ.isEmpty()) return
        for (f in splitQ) {
            if (f.dead) continue
            val b = f.body
            val v = b.linearVelocity
            val sp = max(v.length(), 0.001f)
            f.dead = true
            frags.remove(f)
            toRemove.add(b)
            val pxn = -v.y / sp; val pyn = v.x / sp
            for (i in 0 until 2) {
                if (frags.size > FRAG_CAP) break
                val sgn = if (i == 0) -1f else 1f
                val nf = fragBody(b.position.x, b.position.y, Vec2(0f, 0f),
                                  f.col, f.circle, f.sz * 0.55f, f.sz * 0.35f)
                nf.gen = f.gen + 1
                nf.body.linearVelocity = Vec2(v.x * 0.7f + pxn * sgn * 3f,
                                              max(v.y * 0.4f, 1.5f) + pyn * sgn * 3f)
                nf.body.angularVelocity = sgn * rand(6f, 12f)
            }
            pushPart(Part('P', b.position.x * PX, b.position.y * PX, 0f, -1.5f,
                          s = rand(5f, 9f), max = 0.4f, col = C_DUST))
        }
        splitQ.clear()
    }

    fun destroyBlock(bl: Block) {
        if (bl.dead) return
        bl.dead = true
        blocksSmashed++
        wakeAround(bl.body.position.x, bl.body.position.y, 3.2f)
        val bx = bl.body.position.x * PX; val by = bl.body.position.y * PX
        val vel = bl.body.linearVelocity
        recentMass += bl.body.mass; massT = 0.5f
        toRemove.add(bl.body)
        comboUp()
        val pts = bl.score * combo
        score += pts
        pushPop(Pop(bx, by - 8f, "+$pts",
            if (pts >= 400) 30f else if (pts >= 150) 24f else 18f, 0.95f,
            if (pts >= 150) C_VERM else C_INK, 0f))
        spawnFragments(bl, bl.body.position, vel)
        shake = max(shake, if (bl.type == T.STONE || bl.type == T.ORB) 5f else 3f)
        when (bl.type) {
            T.GLASS -> sounds.play("shatter")
            T.ICE   -> sounds.play("shatter", 1f, 1.35f)
            T.BALLOON -> sounds.play("pop")
            T.HIVE  -> {}
            T.STONE, T.ANVIL -> sounds.thud(0.8f)
            else    -> sounds.crack(0.9f)
        }
        if (bl.type == T.ORB) pushPop(Pop(bx, by - 34f, "GEM!", 20f, 0.9f, C_TEAL, 0f))
        when (bl.type) {
            T.BOOM, T.BARREL -> explode(bx, by, big = false)
            T.TNT  -> explode(bx, by, big = true)
            else   -> {}
        }
    }

    private fun explode(x: Float, y: Float, big: Boolean) {
        if (big) { sounds.play("bigboom"); events?.onBoom() } else sounds.play("boom")
        shake = max(shake, if (big) 22f else 16f)
        val R = if (big) 3.9f else 2.35f
        val FB = if (big) 30f else 24f
        val FBALL = if (big) 24f else 19.5f
        val UP = if (big) 13f else 10.5f
        val DMG = if (big) 230f else 150f

        pushPart(Part('R', x, y, vr = if (big) 460f else 340f, s = 12f, lw = 8f, max = 0.5f, col = C_INK))
        pushPart(Part('R', x, y, vr = if (big) 300f else 220f, s = 6f, lw = 5f, max = 0.4f, col = C_VERM))
        if (big) pushPart(Part('R', x, y, vr = 520f, s = 20f, lw = 3f, max = 0.55f, col = C_VERM))
        for (i in 0 until (if (big) 40 else 26)) pushPart(Part('S', x, y,
            rand(-11f, 11f) * (if (big) 1.3f else 1f), rand(-13f, 4f),
            rand(0f, 6.28f), rand(-8f, 8f), s = rand(4f, 9f), max = rand(0.4f, 0.8f),
            col = if (Math.random() < 0.5) C_VERM else C_INK))
        for (i in 0 until (if (big) 10 else 6)) pushPart(Part('P',
            x + rand(-16f, 16f), y + rand(-16f, 16f), rand(-2f, 2f), rand(-3f, 0f),
            s = rand(12f, 22f), max = rand(0.6f, 1f), col = C_DUST))

        val xm = x / PX; val ym = y / PX
        var b = world.bodyList
        while (b != null) {
            val nxt = b.next
            if (!(b.isStatic && b.userData !is Block)) {
                val dx = b.position.x - xm; val dy = b.position.y - ym
                val d = sqrt(dx * dx + dy * dy)
                if (d > 0.01f && d < R) {
                    val f = 1f - d / R
                    if (b === ball) {
                        b.linearVelocity = Vec2(b.linearVelocity.x + dx / d * FBALL * f,
                                                b.linearVelocity.y + dy / d * FBALL * f - UP * f)
                        if (d < R * 0.5f) hurtBall()   // point-blank blasts bite
                    } else if (!b.isStatic) {
                        b.setAwake(true)
                        b.linearVelocity = Vec2(b.linearVelocity.x + dx / d * FB * f,
                                                b.linearVelocity.y + dy / d * FB * f - UP * f)
                        b.angularVelocity += (if (dx >= 0f) -1f else 1f) *
                            (6f + 10f * f) * rand(0.6f, 1.3f)
                        if (f > 0.4f) breakWeldsAround(b)
                    }
                    val bl = b.userData as? Block
                    if (bl != null && !bl.dead) {
                        bl.hp -= DMG * f; bl.flash = 1f
                        if (bl.hp <= 0f) destroyBlock(bl)
                    }
                }
            }
            b = nxt
        }
    }

    private fun doSlamLand() {
        slamT = 0f; sq = 1f; shake = max(shake, 12f); sounds.play("slamHit"); events?.onSlam()
        val px = ball.position.x * PX; val py = ball.position.y * PX
        pushPart(Part('R', px, py, vr = 300f, s = 14f, lw = 7f, max = 0.4f, col = C_INK))
        for (i in 0 until 14) pushPart(Part('S', px, py, rand(-10f, 10f), rand(-8f, 8f),
            rand(0f, 6.28f), rand(-8f, 8f), s = rand(4f, 8f), max = rand(0.3f, 0.6f), col = C_DUST))
        pushPop(Pop(px, py - 46f, "SLAM!", 24f, 0.8f, C_VERM, 0f))
        waves.add(Wave(px / PX, -1f)); waves.add(Wave(px / PX, 1f))
        sounds.play("slamFall", 0.5f, 0.65f)
        for (bl in blocks) {
            if (bl.dead) continue
            val d = hypot(bl.body.position.x * PX - px, bl.body.position.y * PX - py)
            if (d < 3.125f) {
                bl.hp -= 95f * (1f - d / 3.5f); bl.flash = 1f
                if (bl.hp <= 0f) destroyBlock(bl)
                else if (bl.type == T.BOOM || bl.type == T.TNT) ignite(bl)
            }
        }
    }

    private fun processHits() {
        if (hits.isEmpty()) return
        for (h in hits) {
            val fa = h.a.userData as? Frag
            val fb = h.b.userData as? Frag
            if (h.hasBall) {
                val f = fa ?: fb
                if (f != null && (f.lethal || f.sting)) {
                    if (f.lethal) shake = max(shake, 9f)
                    killFrag(f)
                    hurtBall()
                    continue
                }
                if (fa == null && fb == null) {
                    lastGroundAt = simTime
                    damage(h.a, h.b, h.rel); damage(h.b, h.a, h.rel)
                    if (h.rel > JOINT_BREAK / 3f) { breakWeldsAround(h.a); breakWeldsAround(h.b) }
                    if (slamT > 0f) doSlamLand()
                    val relPx = h.rel / VPS
                    if (relPx > 7f) sounds.thud(clamp(relPx / 26f, 0f, 1f))
                    if (relPx > 15f) shake = max(shake, clamp((relPx - 12f) * 0.45f, 0f, 7f))
                }
            } else if (fa != null && fb == null) {
                tryDebrisHit(h.a, h.b, h.rel, fa)
            } else if (fb != null && fa == null) {
                tryDebrisHit(h.b, h.a, h.rel, fb)
            } else {
                damage(h.a, h.b, h.rel); damage(h.b, h.a, h.rel)
            }
        }
        hits.clear()
    }

    private fun applyCrush(bl: Block, dmg: Float, imp: Float) {
        bl.hp -= dmg; bl.flash = 1f
        shake = max(shake, clamp(imp * 0.07f, 0f, 6f))
        if (bl.hp <= 0f) destroyBlock(bl)
        else if (thudBudget > 0 && dmg > 20f) { thudBudget--; sounds.crack(clamp(dmg / 60f, 0f, 0.7f)) }
    }

    private fun processCrushes() {
        if (crushQ.isEmpty()) return
        for (cr in crushQ) {
            if (cr.a === ball || cr.b === ball) continue
            if (cr.a.userData is Frag || cr.b.userData is Frag) continue
            val ya = cr.a.position.y; val yb = cr.b.position.y
            if (abs(ya - yb) > 0.25f) {
                val sup = if (ya < yb) cr.a else cr.b
                val sb = sup.userData as? Block
                if (sb != null && !sb.dead && !sb.statik) sb.load += cr.imp
            }
            if (cr.imp > JOINT_BREAK) { breakWeldsAround(cr.a); breakWeldsAround(cr.b) }
            val dmg = (cr.imp - CRUSH_MIN) * CRUSH_SCALE
            if (dmg <= 0f) continue
            val ba = cr.a.userData as? Block
            val bb = cr.b.userData as? Block
            if (ba != null && !ba.dead && !ba.statik) applyCrush(ba, dmg, cr.imp)
            if (bb != null && !bb.dead && !bb.statik) applyCrush(bb, dmg, cr.imp)
        }
        crushQ.clear()
    }

    private fun flushRemovals() {
        if (toRemove.isEmpty()) return
        val gone = HashSet<Body>(toRemove)
        welds.removeAll { it.a in gone || it.b in gone }
        ropes.removeAll { it.a in gone || it.b in gone }
        for (b in toRemove) world.destroyBody(b)
        toRemove.clear()
    }

    fun announce(t: String) { annText = t; annT = 0f }

    fun reset() {
        for (bl in blocks) if (!bl.dead) toRemove.add(bl.body)
        blocks.clear()
        for (f in frags) toRemove.add(f.body)
        frags.clear(); parts.clear(); pops.clear()
        for (r in ramps) toRemove.add(r.body)
        ramps.clear(); waves.clear()
        for (p in props) toRemove.add(p.body)
        props.clear(); ropes.clear(); cannons.clear(); pickups.clear()
        hits.clear(); crushQ.clear(); splitQ.clear(); welds.clear()
        recentMass = 0f
        flushRemovals()
        score = 0; shownScore = 0f; combo = 0; comboT = 0f
        distM = 0f; nextMile = 250; spawnX = 17f
        slowmoT = 0f; timeScale = 1f; shake = 0f; slamT = 0f; sq = 0f; acc = 0f
        simTime = 0f; lastGroundAt = -1e9f; prevGround = false; prevVy = 0f
        annText = null; annT = 0f
        hearts = MAX_HEARTS; iframes = 0f; shield = 0
        if (boostNext) { boostNext = false; shield = 1; iframes = 2f }
        scoreDoubled = false
        dying = false; dieT = 0f; over = false
        blocksSmashed = 0; maxCombo = 0
        setIdx = 0; nextSetM = 320f; curBiome = BIOMES[0]; biomeIdx = 0
        nextPickM = 90f
        hintStage = if (tutDone) 4 else 0
        hintMsg = if (tutDone) null else "DRAG TO ROLL"
        dragQ.clear(); cmdQ.clear()
        ball.setTransform(Vec2(4.5f, G_M - 4f), 0f)
        ball.linearVelocity = Vec2(0f, 0f); ball.angularVelocity = 0f
        ball.setAwake(true)
        camX = -0.75f; camY = 0f
        while (spawnX < viewWM * 1.7f + 7.5f) buildNext()
        flushRemovals()
    }

    private fun updateCannons(dts: Float) {
        if (cannons.isEmpty()) return
        for (cn in cannons) {
            cn.cd -= dts
            if (cn.cd > 0f) continue
            if (!playing || abs(ball.position.x - cn.x) > 20f) continue
            cn.cd = rand(2.6f, 3.6f)
            val dir = if (ball.position.x < cn.x) -1f else 1f
            val cb = fragBody(cn.x + dir * 0.8f, cn.y, Vec2(0f, 0f),
                              C_INK, true, 0.2f, 0f, dens = 2.0f, rest = 0.05f)
            cb.gen = 1; cb.lethal = true
            cb.body.linearVelocity = Vec2(dir * 13f, -1.6f)
            cb.body.angularVelocity = dir * 10f
            for (i in 0 until 5) pushPart(Part('P', cn.x * PX + dir * 34f, cn.y * PX,
                dir * rand(1f, 3f), rand(-2f, -0.5f), s = rand(5f, 10f),
                max = 0.4f, col = C_DUST))
            sounds.play("shoot")
        }
    }

    private fun updateAmbient(dt: Float) {
        ambientT -= dt
        if (ambientT > 0f) return
        ambientT = 0.2f
        val bxm = camX + viewWM * rand(0.1f, 0.95f)
        val b = biomeAtX(bxm)
        val px = bxm * PX; val top = camY * PX + 10f
        when (b.decor) {
            2 -> pushPart(Part('F', px, top, rand(-9f, 9f), rand(38f, 62f),
                rand(0f, 6.28f), s = rand(2f, 3.5f), max = 6f, col = C_SNOW))
            3 -> if (Math.random() < 0.55f) pushPart(Part('E', px, GROUND_Y - rand(10f, 120f),
                rand(-4f, 4f), -rand(26f, 55f), s = rand(2f, 4f), max = 2.2f,
                col = if (Math.random() < 0.5f) C_VERM else C_MUSTARD))
            4 -> pushPart(Part('Y', px, GROUND_Y - rand(60f, 230f), rand(-7f, 7f), rand(-4f, 4f),
                rand(0f, 6.28f), s = rand(1.8f, 2.8f), max = 5f, col = C_MUSTARD))
            else -> if (Math.random() < 0.10f) {
                if (frags.size < FRAG_CAP) {
                    val tw = fragBody(bxm, GROUND_Y - rand(0.3f, 0.6f), Vec2(0f, 0f),
                                      C_DUST, true, rand(0.32f, 0.5f), 0f, dens = 0.15f)
                    tw.gen = 2; tw.life = 1.0f
                    tw.body.linearVelocity = Vec2(-rand(3.5f, 5.5f), -1.5f)
                    tw.body.angularVelocity = -8f
                }
            }
        }
    }

    fun update(dt: Float) {
        processInput()
        thudBudget = 3; creakBudget = 2

        if (dying) { dieT -= dt; if (dieT <= 0f) { dying = false; over = true } }

        // slow-mo (clamped: long death slow-mo must not invert into speed-up)
        if (slowmoT > 0f) {
            slowmoT -= dt
            timeScale = if (slowmoT > 0f)
                0.32f + 0.68f * clamp(1f - slowmoT / SLOW_DUR, 0f, 1f).pow(2f) else 1f
        } else timeScale = 1f
        val dts = dt * timeScale
        simTime += dts

        if (iframes > 0f) iframes = max(0f, iframes - dt)

        ball.setAwake(true)
        acc += dts
        var n = 0
        while (acc >= STEP && n < 5) {
            for (bl in blocks) {
                if (!bl.dead && bl.type == T.BALLOON) {
                    bl.body.setAwake(true)
                    bl.body.applyForceToCenter(Vec2(0f, -BALLOON_LIFT))
                }
            }
            world.step(STEP, 8, 3)
            acc -= STEP; n++
            processHits()
            processCrushes()
        }
        if (n > 0) flushRemovals()
        processSplits()
        if (toRemove.isNotEmpty()) flushRemovals()

        var touching = false
        var e = ball.contactList
        while (e != null) { if (e.contact.isTouching) { touching = true; break }; e = e.next }
        if (touching) lastGroundAt = simTime

        // progressive failure
        var creaked = false
        for (bl in blocks) {
            if (bl.dead) continue
            bl.load *= 0.85f.pow(dts * 60f)
            if (bl.load > bl.cap) {
                bl.hp -= (bl.load - bl.cap) * CREEP * dts
                bl.flash = max(bl.flash, 0.22f)
                if (creakBudget > 0 && !creaked && Math.random() < 0.04f) {
                    creakBudget--; creaked = true; sounds.play("creak")
                }
                if (bl.hp <= 0f) destroyBlock(bl)
            }
        }

        // cook-off fuses
        fuseSparkT -= dts
        for (bl in blocks) {
            if (bl.dead || bl.fuse < 0f) continue
            bl.fuse -= dts
            bl.flash = 0.35f + 0.35f * sin(simTime * 26f)
            if (fuseSparkT <= 0f) {
                val bp = bl.body.position
                pushPart(Part('S', bp.x * PX + rand(-6f, 6f),
                    (bp.y + bl.h * 0.5f) * PX + 6f, rand(-1.5f, 1.5f), rand(-6f, -3f),
                    rand(0f, 6.28f), rand(-10f, 10f), s = rand(2f, 4f),
                    max = 0.3f, col = C_VERM))
            }
            if (bl.fuse <= 0f) destroyBlock(bl)
        }
        if (fuseSparkT <= 0f) fuseSparkT = 0.06f

        // collapse rumble
        if (massT > 0f) { massT -= dt
            if (massT <= 0f) {
                if (recentMass > 9f) { sounds.play("rumble"); shake = max(shake, 8f) }
                recentMass = 0f
            }
        }

        // shockwaves
        dustT2 -= dts
        if (waves.isNotEmpty()) {
            val wit = waves.iterator()
            while (wit.hasNext()) {
                val wv = wit.next()
                wv.traveled += WAVE_SPEED * dts
                wv.x += wv.dir * WAVE_SPEED * dts
                if (wv.traveled >= WAVE_MAX) { wit.remove(); continue }
                if (dustT2 <= 0f) {
                    pushPart(Part('P', wv.x, GROUND_Y - 6f, rand(-1f, 1f), rand(-3f, -1f),
                        s = rand(5f, 10f), max = rand(0.3f, 0.55f), col = C_DUST))
                }
                val dmg = 62f * (1f - wv.traveled / WAVE_MAX)
                for (bl in blocks) {
                    if (bl.dead || bl.statik || bl.body.isStatic) continue
                    if (wv.hit.contains(bl)) continue
                    val bxm = bl.body.position.x; val bym = bl.body.position.y
                    if (abs(bxm - wv.x) < 0.9f && bym > G_M - 4.2f && bym < G_M + 1f) {
                        wv.hit.add(bl)
                        bl.hp -= dmg; bl.flash = 1f
                        bl.body.setAwake(true)
                        bl.body.linearVelocity = Vec2(bl.body.linearVelocity.x + wv.dir * 1.5f,
                                                      bl.body.linearVelocity.y + 1.2f)
                        breakWeldsAround(bl.body)
                        if (bl.hp <= 0f) destroyBlock(bl)
                        else if (bl.type == T.BOOM || bl.type == T.TNT) ignite(bl)
                    }
                }
            }
            flushRemovals()
        }

        if (playing && !dying) {
            if (keyDir != 0f) {
                ball.setAwake(true)
                ball.linearVelocity = Vec2(
                    clamp(ball.linearVelocity.x + keyDir * 90f * dt, -31.5f, 31.5f),
                    ball.linearVelocity.y)
            }
            if (touching) ball.angularVelocity = ball.linearVelocity.x / (BALL_R / PX)

            if (touching && !prevGround && prevVy > 15f) sq = min(1f, prevVy / 42f)
            prevGround = touching; prevVy = ball.linearVelocity.y
            sq += -sq * 7f * dt; if (abs(sq) < 0.01f) sq = 0f
            if (slamT > 0f) slamT -= dt

            val bx = ball.position.x * PX; val vx = ball.linearVelocity.x
            dustT -= dt
            if (touching && abs(vx) > 18f && dustT <= 0f) {
                dustT = 0.07f
                pushPart(Part('P', bx - sign(vx) * 22f, GROUND_Y - 4f,
                    rand(-1f, 1f) - vx * 0.06f, rand(-2.2f, -0.6f),
                    s = rand(4f, 9f), max = rand(0.35f, 0.6f), col = C_DUST))
            }
            sounds.setRoll(abs(vx) / 31.5f)

            val tx = ball.position.x + clamp(vx * 9.33f, -1.75f, 6.25f) - viewWM * 0.34f
            camX += (tx - camX) * min(1f, dt * 6f)
            if (camX < -0.75f) camX = -0.75f
            val ty = clamp(min(0f, (ball.position.y - 11.25f) * 0.5f), -3.75f, 0f)
            camY += (ty - camY) * min(1f, dt * 4f)

            if (combo > 0) { comboT -= dt; if (comboT <= 0f) combo = 0 }

            // pickups
            val bp = ball.position
            val pkit = pickups.iterator()
            while (pkit.hasNext()) {
                val pk = pkit.next()
                val bob = sin(simTime * 2.2f + pk.x) * 0.15f
                val dx = bp.x - pk.x; val dy = bp.y - (pk.y + bob)
                if (dx * dx + dy * dy < 1.25f) {
                    pkit.remove()
                    val px2 = pk.x * PX; val py2 = (pk.y + bob) * PX
                    when (pk.kind) {
                        'h' -> if (hearts < MAX_HEARTS) {
                            hearts++; sounds.play("heal")
                            pushPop(Pop(px2, py2 - 30f, "+1 HEART", 20f, 0.9f, C_VERM, 0f))
                            pushPart(Part('R', px2, py2, vr = 220f, s = 10f, lw = 5f,
                                          max = 0.4f, col = C_VERM))
                        } else {
                            score += 250; sounds.play("pickup")
                            pushPop(Pop(px2, py2 - 30f, "+250", 20f, 0.9f, C_INK, 0f))
                        }
                        's' -> {
                            shield = 1; sounds.play("pickup")
                            pushPop(Pop(px2, py2 - 30f, "SHIELD!", 20f, 0.9f, C_TEAL, 0f))
                            pushPart(Part('R', px2, py2, vr = 220f, s = 10f, lw = 5f,
                                          max = 0.4f, col = C_TEAL))
                        }
                        't' -> {
                            slowmoT = max(slowmoT, 1.5f); sounds.play("pickup", 1f, 0.8f)
                            pushPop(Pop(px2, py2 - 30f, "SLOW-MO!", 20f, 0.9f, C_TEAL, 0f))
                        }
                    }
                }
            }

            // biome transitions
            val bi = biomeAtX(ball.position.x)
            if (bi !== curBiome) {
                curBiome = bi; biomeIdx = BIOMES.indexOf(bi)
                announce(bi.announce); sounds.play("chord")
            }

            if (hintStage == 0 && abs(vx) > 20f) { hintStage = 1; hintT = 4f; hintMsg = "TAP MID-AIR TO SLAM" }
            else if (hintStage == 1) { hintT -= dt; if (hintT <= 0f) { hintStage = 2; hintMsg = null } }
            else if (hintStage == 3) { hintT -= dt; if (hintT <= 0f) { hintStage = 4; hintMsg = null; tutDone = true } }
            if (hintStage == 2 && combo >= 4) { hintStage = 3; hintT = 2.6f; hintMsg = "CHAIN SMASHES TO MULTIPLY" }

            distM = max(distM, ball.position.x - 4.5f)
            if (distM >= nextMile) { announce("${nextMile}m"); sounds.play("chord"); nextMile += 250 }
        }

        shake = max(0f, shake - shake * 7f * dt - 2f * dt)

        while (spawnX < camX + viewWM * 1.7f) buildNext()
        cleanT -= dt
        if (cleanT <= 0f) {
            cleanT = 0.7f
            val lim = camX - 19.5f
            val it = blocks.iterator()
            while (it.hasNext()) {
                val bl = it.next()
                if (bl.dead || bl.body.position.x < lim) {
                    if (!bl.dead) toRemove.add(bl.body)
                    it.remove()
                }
            }
            val rit = ramps.iterator()
            while (rit.hasNext()) {
                val r = rit.next()
                if (r.body.position.x < camX - 20.5f) { toRemove.add(r.body); rit.remove() }
            }
            val pit2 = props.iterator()
            while (pit2.hasNext()) {
                val pr = pit2.next()
                if (pr.body.position.x < lim) { toRemove.add(pr.body); pit2.remove() }
            }
            cannons.removeAll { it.x < lim }
            pickups.removeAll { it.x < lim }
        }

        val fit = frags.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.life += dts * if (f.body.linearVelocity.length() < 1.2f) 0.45f else 1f
            if (f.life > 2.6f) { f.dead = true; toRemove.add(f.body); fit.remove() }
        }
        val pit = parts.iterator()
        while (pit.hasNext()) { val q = pit.next(); q.life += dts
            if (q.life >= q.max) { pit.remove(); continue }
            when (q.k) {
                'S' -> { q.vy += 26f * dts; q.x += q.vx * dts; q.y += q.vy * dts; q.rot += q.vr * dts }
                'P' -> { q.x += q.vx * dts; q.y += q.vy * dts; q.vx *= 0.4f.pow(dts) }
                'F' -> { q.x += q.vx * dts; q.y += q.vy * dts
                         q.vx += sin(simTime * 3f + q.rot) * 6f * dts }
                'E' -> { q.x += q.vx * dts; q.y += q.vy * dts }
                'Y' -> { q.x += q.vx * dts; q.y += q.vy * dts
                         q.vx += rand(-14f, 14f) * dts; q.vy += rand(-14f, 14f) * dts
                         q.vx = clamp(q.vx, -9f, 9f); q.vy = clamp(q.vy, -9f, 9f) }
            }
        }
        val oit = pops.iterator()
        while (oit.hasNext()) { val q = oit.next(); q.life += dts; if (q.life >= q.max) oit.remove() }
        for (bl in blocks) bl.flash = max(0f, bl.flash - 5f * dts)

        updateCannons(dts)
        updateAmbient(dt)

        shownScore += (score - shownScore) * min(1f, dt * 10f)
        if (score - shownScore < 1f) shownScore = score.toFloat()
        if (annText != null) annT += dt
    }

    companion object { const val SLOW_DUR = 0.6f; const val MAX_HEARTS = 3 }
}
