package com.example.handarm

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private fun check(cond: Boolean, msg: String) = assertTrue(msg, cond)
private fun near(a: Float, b: Float, tol: Float) = abs(a - b) <= tol
private const val D2R = 0.017453292f

private fun v(x: Float, y: Float, z: Float) = V3(x, y, z)
private operator fun V3.plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)

/**
 * Synthetic anatomical right hand (metres) in a "hand frame":
 * origin = wrist, u = (0,1,0) along the hand, s = (1,0,0) index->pinky, palm normal n = u x s = (0,0,-1).
 * Fingers flex towards +n (the side the palm faces).
 */
private object Synth {
    private val lat = floatArrayOf(-0.027f, -0.009f, 0.009f, 0.027f)       // index, middle, ring, pinky
    private val hgt = floatArrayOf(0.092f, 0.095f, 0.090f, 0.080f)
    private val seg = arrayOf(
        floatArrayOf(0.040f, 0.024f, 0.020f), floatArrayOf(0.045f, 0.028f, 0.021f),
        floatArrayOf(0.042f, 0.026f, 0.020f), floatArrayOf(0.032f, 0.019f, 0.018f),
    )

    /**
     * curl[0..3] = index, middle, ring, pinky in 0..1; thumbCurl in 0..1 (1 = full flexion);
     * swing in 0..1 (0 = thumb out beside the palm, 1 = swung ~70 deg out of the palm plane, in front of it).
     */
    fun hand(curl: FloatArray, thumbCurl: Float, swing: Float = 0f): Array<V3> {
        val w = Array(21) { v(0f, 0f, 0f) }
        for (k in 0 until 4) {
            val t = curl[k]
            val a1 = 85f * t * D2R; val a2 = (85f + 100f) * t * D2R; val a3 = (85f + 100f + 65f) * t * D2R
            fun dir(th: Float) = v(0f, cos(th), -sin(th))                 // from +u towards +n=(0,0,-1)
            val mcp = v(lat[k], hgt[k], 0f)
            val pip = mcp + dir(a1) * seg[k][0]
            val dip = pip + dir(a2) * seg[k][1]
            val tip = dip + dir(a3) * seg[k][2]
            val b = 5 + 4 * k
            w[b] = mcp; w[b + 1] = pip; w[b + 2] = dip; w[b + 3] = tip
        }
        // thumb metacarpal: 45 deg away from the hand axis, swung out of the palm plane towards +n by 'swing'
        val alpha = 45f * D2R
        val beta = 70f * swing * D2R
        val d0 = v(-sin(alpha) * cos(beta), cos(alpha), -sin(alpha) * sin(beta))
        val b0 = v(0.5f, 0f, -0.85f)
        val b = (b0 - d0 * b0.dot(d0)).unit()                             // curl bends towards the palm
        fun tdir(th: Float) = d0 * cos(th) + b * sin(th)
        val thA = 55f * thumbCurl * D2R; val thB = (55f + 65f) * thumbCurl * D2R
        w[1] = v(-0.018f, 0.02f, 0f)
        w[2] = w[1] + d0 * 0.045f
        w[3] = w[2] + tdir(thA) * 0.032f
        w[4] = w[3] + tdir(thB) * 0.026f
        return w
    }

    private fun rotAbout(a: V3, deg: Float, p: V3): V3 {            // Rodrigues
        val k = a.unit(); val t = deg * D2R
        return p * cos(t) + k.cross(p) * sin(t) + k * (k.dot(p) * (1f - cos(t)))
    }

    /** Place the hand-frame hand in camera space. back=true shows the back of the hand; mirror => left hand. */
    fun place(w: Array<V3>, rollDeg: Float = 0f, pitchDeg: Float = 0f, back: Boolean = false, mirror: Boolean = false): List<V3> =
        w.map { p0 ->
            var p = if (mirror) v(-p0.x, p0.y, p0.z) else p0
            p = v(-p.x, -p.y, p.z)                                   // palm to camera, fingers up (camera: x right, y down, z away)
            if (back) p = v(-p.x, p.y, -p.z)                         // 180 deg about the hand axis
            p = rotAbout(v(0f, -1f, 0f), rollDeg, p)                 // forearm roll about the hand axis
            val pt = -pitchDeg * D2R                                 // + pitch = fingers tilt away from camera
            v(p.x, p.y * cos(pt) - p.z * sin(pt), p.y * sin(pt) + p.z * cos(pt))
        }

    fun open() = hand(floatArrayOf(0f, 0f, 0f, 0f), 0f)
    fun fist() = hand(floatArrayOf(1f, 1f, 1f, 1f), 1f, 1f)        // thumb curled AND swung in front of the palm
}

class LogicTest {

    @Test fun protocolMatchesDocumentedExample() {
        val s = String(Protocol.encode(intArrayOf(90, 90, 0, 0, 0, 0, 0, 0)), Charsets.US_ASCII)
        check(s == "\$H,90,90,0,0,0,0,0,0*48\n", "got '$s'")
    }

    @Test fun palmAndBackBothTrackedWithSameRollSense() {
        for (delta in floatArrayOf(-45f, -20f, 0f, 20f, 45f)) {
            val p = HandMath.compute(Synth.place(Synth.open(), rollDeg = delta), true)!!
            val b = HandMath.compute(Synth.place(Synth.open(), rollDeg = delta, back = true), true)!!
            check(p.palmFacingCamera && !b.palmFacingCamera, "palm flag wrong at roll $delta")
            check(near(abs(p.rollDeg), abs(delta), 2f), "palm roll ${p.rollDeg} vs $delta")
            check(near(p.rollDeg, b.rollDeg, 2f), "palm roll ${p.rollDeg} != back roll ${b.rollDeg} at $delta")
            for (i in 0 until HandMath.RAW_COUNT) check(near(p.fingersRaw[i], b.fingersRaw[i], 1e-3f), "${HandMath.RAW_NAMES[i]} differs palm/back")
        }
    }

    @Test fun rollIsAlwaysFoldedIntoPlusMinus90() {
        var r = -170f
        while (r <= 170f) {
            val x = HandMath.compute(Synth.place(Synth.open(), rollDeg = r), true)!!
            check(abs(x.rollDeg) <= 90.5f, "roll ${x.rollDeg} at input $r")
            r += 10f
        }
    }

    @Test fun leftHandMirrorGivesSameReadingAsRightHand() {
        val curl = floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f)
        val right = HandMath.compute(Synth.place(Synth.hand(curl, 0.4f, 0.6f), rollDeg = 25f, pitchDeg = 10f), true)!!
        val left = HandMath.compute(Synth.place(Synth.hand(curl, 0.4f, 0.6f), rollDeg = 25f, pitchDeg = 10f, mirror = true), false)!!
        for (i in 0 until HandMath.RAW_COUNT) check(near(right.fingersRaw[i], left.fingersRaw[i], 1e-3f), "${HandMath.RAW_NAMES[i]} right/left differ")
        check(near(right.rollDeg, left.rollDeg, 1f) || near(right.rollDeg, -left.rollDeg, 1f), "roll ${right.rollDeg} vs ${left.rollDeg}")
        check(left.palmFacingCamera, "left palm should face camera")
    }

    @Test fun pitchFollowsFingerTilt() {
        for (p in floatArrayOf(-40f, -20f, 0f, 20f, 40f)) {
            val x = HandMath.compute(Synth.place(Synth.open(), pitchDeg = p), true)!!
            check(near(x.pitchDeg, p, 1.5f), "pitch ${x.pitchDeg} vs $p")
            check(near(x.rollDeg, 0f, 2f), "pitch leaked into roll: ${x.rollDeg}")
        }
    }

    @Test fun curlIsMonotonicAndDefaultRangeIsSane() {
        val names = HandMath.RAW_NAMES
        var prev = FloatArray(HandMath.RAW_COUNT) { -9f }
        for (step in 0..10) {
            val t = step / 10f
            val r = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(t, t, t, t), t)), true)!!
            if (step == 0 || step == 10) println("curl t=$t raw=" + r.fingersRaw.joinToString { "%.2f".format(it) })
            for (i in 0 until 5) {                                   // 0..3 fingers, 4 = thumb curl
                check(r.fingersRaw[i] > prev[i] - 1e-4f, "${names[i]} not monotonic at t=$t")
                if (step == 0) check(r.fingersRaw[i] in -0.3f..0.3f, "${names[i]} open raw ${r.fingersRaw[i]}")
                if (step == 10) check(r.fingersRaw[i] in 0.6f..1.4f, "${names[i]} fist raw ${r.fingersRaw[i]}")
            }
            prev = r.fingersRaw
        }
    }

    @Test fun fingersAreIndependent() {
        val x = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(1f, 0f, 0f, 0f), 0f)), true)!!   // index only
        check(x.fingersRaw[3] > 0.6f, "index should be curled: ${x.fingersRaw[3]}")
        for (i in intArrayOf(0, 1, 2, 4, 5)) check(abs(x.fingersRaw[i]) < 0.3f, "${HandMath.RAW_NAMES[i]} leaked ${x.fingersRaw[i]}")
    }

    @Test fun thumbSwingIsMonotonicAndSpansTheRange() {
        var prev = -9f
        for (step in 0..10) {
            val t = step / 10f
            val r = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(0f, 0f, 0f, 0f), 0f, t)), true)!!
            val sw = r.fingersRaw[5]
            if (step == 0 || step == 10) println("swing t=$t raw=%.2f".format(sw))
            check(sw > prev + 1e-4f, "swing not monotonic at t=$t (raw $sw)")
            prev = sw
            if (step == 0) check(sw in -0.3f..0.3f, "beside-palm raw $sw")
            if (step == 10) check(sw in 0.7f..1.4f, "in-front raw $sw")
        }
    }

    @Test fun thumbSwingAndThumbCurlAreIndependent() {
        for (swing in floatArrayOf(0f, 0.5f, 1f)) {
            val straight = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(0f, 0f, 0f, 0f), 0f, swing)), true)!!
            val curled = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(0f, 0f, 0f, 0f), 1f, swing)), true)!!
            check(near(straight.fingersRaw[5], curled.fingersRaw[5], 0.05f), "curl changed swing at $swing: ${straight.fingersRaw[5]} vs ${curled.fingersRaw[5]}")
            check(curled.fingersRaw[4] - straight.fingersRaw[4] > 0.6f, "curl not detected at swing $swing")
        }
        for (curl in floatArrayOf(0f, 1f)) {
            val a = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(0f, 0f, 0f, 0f), curl, 0f)), true)!!
            val b = HandMath.compute(Synth.place(Synth.hand(floatArrayOf(0f, 0f, 0f, 0f), curl, 1f)), true)!!
            check(near(a.fingersRaw[4], b.fingersRaw[4], 0.1f), "swing changed thumb curl at curl $curl: ${a.fingersRaw[4]} vs ${b.fingersRaw[4]}")
        }
    }

    @Test fun thumbSwingWorksFromAnyCameraAngle() {
        val h = Synth.hand(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f), 0.3f, 0.8f)
        val ref = HandMath.compute(Synth.place(h), true)!!.fingersRaw[5]
        for (roll in floatArrayOf(-40f, 25f)) for (pitch in floatArrayOf(-25f, 25f)) for (back in booleanArrayOf(false, true)) {
            val x = HandMath.compute(Synth.place(h, rollDeg = roll, pitchDeg = pitch, back = back), true)!!.fingersRaw[5]
            check(near(x, ref, 0.03f), "swing $x vs $ref (roll $roll pitch $pitch back $back)")
        }
    }

    @Test fun calibrationMapsOpenToZeroAndFistTo180() {
        val open = HandMath.compute(Synth.place(Synth.open()), true)!!
        val fist = HandMath.compute(Synth.place(Synth.fist(), rollDeg = 15f, pitchDeg = 8f), true)!!
        val cal = Calibration(open.fingersRaw.copyOf(), fist.fingersRaw.copyOf(), 15f, 8f, true)
        val a = cal.apply(open); val b = cal.apply(fist)
        for (i in 2 until Config.NUM_VALUES) { check(near(a[i], 0f, 1f), "open[$i]=${a[i]}"); check(near(b[i], 180f, 1f), "fist[$i]=${b[i]}") }
        check(near(b[0], 90f, 3f) && near(b[1], 90f, 3f), "wrist centre ${b[0]},${b[1]}")
    }

    @Test fun calibrationSurvivesEncodeDecode() {
        val c = Calibration(floatArrayOf(.1f, .2f, .3f, .4f, .5f, .6f), floatArrayOf(.9f, .8f, .7f, .6f, 1.2f, 1.1f), 12.5f, -3f, true)
        val d = Calibration.decode(c.encode())
        check(d.calibrated && near(d.rollZero, 12.5f, 1e-4f) && near(d.pitchZero, -3f, 1e-4f), "scalars")
        for (i in 0 until HandMath.RAW_COUNT) check(near(c.openRaw[i], d.openRaw[i], 1e-5f) && near(c.fistRaw[i], d.fistRaw[i], 1e-5f), "arrays")
        check(!Calibration.decode("garbage").calibrated && !Calibration.decode(null).calibrated, "bad input must give defaults")
        check(!Calibration.decode("0.1,0.2,0.3,0.4,0.5,0.9,0.8,0.7,0.6,1.2,12.5,-3").calibrated, "old 12-value calibration must be discarded")
    }

    @Test fun pipelineTrackHoldNeutralAndSlew() {
        val p = CommandPipeline()
        var t = 1000L
        val first = p.step(t)
        for (i in 0 until Config.NUM_VALUES) check(first[i] == Config.NEUTRAL[i].toInt(), "starts neutral")
        val target = floatArrayOf(120f, 60f, 100f, 90f, 80f, 70f, 150f, 40f)
        var last = first
        repeat(60) {
            t += 33; p.onHand(target, t)
            val out = p.step(t)
            for (i in 0 until Config.NUM_VALUES) check(abs(out[i] - last[i]) <= Config.MAX_STEP_PER_TICK + 1f, "slew exceeded")
            last = out
        }
        check(p.mode == CommandPipeline.Mode.TRACKING, "mode ${p.mode}")
        for (i in 0 until Config.NUM_VALUES) check(abs(last[i] - target[i]) <= 2f, "ch $i ${last[i]} vs ${target[i]}")
        t += 400; val held = p.step(t)
        check(p.mode == CommandPipeline.Mode.HOLDING, "should hold, was ${p.mode}")
        for (i in 0 until Config.NUM_VALUES) check(held[i] == last[i], "hold changed ch $i")
        t += 700
        var out = p.step(t); repeat(80) { t += 33; out = p.step(t) }
        check(p.mode == CommandPipeline.Mode.NEUTRAL, "mode ${p.mode}")
        for (i in 0 until Config.NUM_VALUES) check(abs(out[i] - Config.NEUTRAL[i]) <= 2f, "did not return to neutral ch $i = ${out[i]}")
        p.enabled = false; p.onHand(target, t); repeat(5) { t += 33; p.step(t) }
        check(p.mode == CommandPipeline.Mode.PAUSED, "paused")
    }

    @Test fun oneEuroReducesJitterButFollowsSteps() {
        val rnd = Random(7)
        fun noise() = (List(12) { rnd.nextFloat() }.sum() - 6f) * 3f          // ~N(0, 3 deg)
        val f = OneEuro(Config.EURO_MIN_CUTOFF_HZ, Config.EURO_BETA, Config.EURO_D_CUTOFF_HZ)
        val dt = 1f / 30f
        var rawSq = 0f; var outSq = 0f; var n = 0
        repeat(150) { k ->
            val x = 90f + noise(); val y = f.filter(x, dt)
            if (k >= 30) { rawSq += (x - 90f) * (x - 90f); outSq += (y - 90f) * (y - 90f); n++ }
        }
        check(outSq / n < 0.4f * rawSq / n, "jitter not reduced: raw ${rawSq / n} out ${outSq / n}")
        var y = 0f; var frames = 0
        for (k in 1..15) { y = f.filter(150f + noise(), dt); if (y >= 140f && frames == 0) frames = k }
        check(frames in 1..6, "step response too slow: $frames frames")
    }
}
