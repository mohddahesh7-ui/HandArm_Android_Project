package com.example.handarm

import kotlin.math.PI
import kotlin.math.abs

/** Wire format:  $H,<rot>,<lift>,<pinky>,<ring>,<middle>,<index>,<thumbCurl>,<thumbSwing>*<XOR hex>\n  (values 0..180) */
object Protocol {
    fun encode(v: IntArray): ByteArray {
        val body = "H," + v.joinToString(",")
        var cs = 0
        for (ch in body) cs = cs xor ch.code
        return ("\$" + body + "*" + "%02X".format(cs) + "\n").toByteArray(Charsets.US_ASCII)
    }
}

/** One-Euro filter (Casiez et al.): adaptive low-pass - steady when still, responsive when moving. */
class OneEuro(private val minCutoff: Float, private val beta: Float, private val dCutoff: Float) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var ready = false

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun reset() { ready = false }

    fun filter(x: Float, dt: Float): Float {
        if (!ready) { ready = true; xPrev = x; dxPrev = 0f; return x }
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        val a = alpha(minCutoff + beta * abs(dxHat), dt)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }
}

/**
 * onHand() runs on the camera thread (filters each new frame with its real dt);
 * step() runs on the fixed-rate sender thread (hold / neutral logic, deadband, slew limit).
 */
class CommandPipeline {
    enum class Mode { TRACKING, HOLDING, NEUTRAL, PAUSED }

    @Volatile var enabled = true                 // "Live" switch: false -> arm is commanded to NEUTRAL
    @Volatile var mode = Mode.NEUTRAL
        private set

    private val filters = Array(Config.NUM_VALUES) { OneEuro(Config.EURO_MIN_CUTOFF_HZ, Config.EURO_BETA, Config.EURO_D_CUTOFF_HZ) }
    @Volatile private var target: FloatArray? = null
    @Volatile private var lastSeenMs = 0L
    private val current = Config.NEUTRAL.copyOf()   // sender thread only

    fun onHand(values: FloatArray, nowMs: Long) {
        val gap = nowMs - lastSeenMs
        if (target == null || gap > Config.FILTER_RESET_GAP_MS) filters.forEach { it.reset() }
        val dt = (gap / 1000f).coerceIn(0.005f, 0.2f)
        target = FloatArray(Config.NUM_VALUES) { filters[it].filter(values[it], dt) }
        lastSeenMs = nowMs
    }

    fun step(nowMs: Long): IntArray {
        val t = target
        val age = nowMs - lastSeenMs
        val desired: FloatArray = when {
            !enabled -> { mode = Mode.PAUSED; Config.NEUTRAL }
            t != null && age <= Config.SEND_INTERVAL_MS * 4 -> { mode = Mode.TRACKING; t }
            t != null && age <= Config.HOLD_LAST_MS -> { mode = Mode.HOLDING; t }
            t != null && !Config.RETURN_TO_NEUTRAL -> { mode = Mode.HOLDING; t }
            else -> { mode = Mode.NEUTRAL; Config.NEUTRAL }
        }
        val out = IntArray(Config.NUM_VALUES)
        for (i in 0 until Config.NUM_VALUES) {
            val err = desired[i] - current[i]
            if (abs(err) >= Config.DEADBAND_DEG) {
                current[i] = (current[i] + err.coerceIn(-Config.MAX_STEP_PER_TICK, Config.MAX_STEP_PER_TICK)).coerceIn(0f, 180f)
            }
            out[i] = (current[i] + 0.5f).toInt().coerceIn(0, 180)
        }
        return out
    }
}
