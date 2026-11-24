package com.example.handarm

import kotlin.math.abs

fun median(xs: List<Float>): Float {
    val s = xs.sorted()
    return s[s.size / 2]
}

private fun wrap180(x: Float): Float = ((x + 180f) % 360f + 360f) % 360f - 180f

/**
 * Per-user calibration: raw curl value for an OPEN hand and a FIST for each finger, plus the wrist roll/pitch
 * that should count as "centred". Default (uncalibrated) is the identity mapping.
 * Channel order: pinky, ring, middle, index, thumb curl, thumb swing.
 */
class Calibration(
    val openRaw: FloatArray = FloatArray(HandMath.RAW_COUNT) { 0f },
    val fistRaw: FloatArray = FloatArray(HandMath.RAW_COUNT) { 1f },
    val rollZero: Float = 0f,
    val pitchZero: Float = 0f,
    val calibrated: Boolean = false,
) {
    /** @return rot, lift, pinky, ring, middle, index, thumbCurl, thumbSwing (0..180) */
    fun apply(r: HandReading): FloatArray {
        val out = FloatArray(Config.NUM_VALUES)

        var roll = wrap180(r.rollDeg - rollZero)
        if (Config.FOLD_PALM_BACK) {
            if (roll > 90f) roll -= 180f else if (roll < -90f) roll += 180f
        }
        val pitch = r.pitchDeg - pitchZero
        val rollN = (roll / Config.ROLL_RANGE_DEG).coerceIn(-1f, 1f) * (if (Config.ROLL_INVERT) -1f else 1f)
        val pitchN = (pitch / Config.PITCH_RANGE_DEG).coerceIn(-1f, 1f) * (if (Config.PITCH_INVERT) -1f else 1f)
        out[0] = 90f + 90f * rollN
        out[1] = 90f + 90f * pitchN

        for (i in 0 until HandMath.RAW_COUNT) {
            var span = fistRaw[i] - openRaw[i]
            if (abs(span) < 1e-3f) span = 1e-3f
            out[2 + i] = ((r.fingersRaw[i] - openRaw[i]) / span).coerceIn(0f, 1f) * 180f
        }
        return out
    }

    /** Serialised form stored in SharedPreferences: 6 open, 6 fist, rollZero, pitchZero. */
    fun encode(): String = (openRaw.toList() + fistRaw.toList() + listOf(rollZero, pitchZero)).joinToString(",")

    companion object {
        fun decode(s: String?): Calibration {
            val parts = s?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return Calibration()
            val n = HandMath.RAW_COUNT
            if (parts.size != 2 * n + 2) return Calibration()      // also rejects calibrations saved by older versions
            return Calibration(
                parts.subList(0, n).toFloatArray(), parts.subList(n, 2 * n).toFloatArray(),
                parts[2 * n], parts[2 * n + 1], true,
            )
        }
    }
}
