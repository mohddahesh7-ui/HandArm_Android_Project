package com.example.handarm

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun unit(): V3 {
        val l = len()
        return if (l < 1e-6f) V3(0f, 0f, 0f) else V3(x / l, y / l, z / l)
    }
}

/** Uncalibrated measurements from one frame. Calibration.apply() turns this into the 7 servo values. */
class HandReading(
    /**
     * raw metric per channel, order: pinky, ring, middle, index, thumbCurl, thumbSwing.
     * ~0 = open / thumb beside palm, ~1 = curled / thumb in front of palm (NOT clamped).
     */
    val fingersRaw: FloatArray,
    val rollDeg: Float,
    val pitchDeg: Float,
    val palmFacingCamera: Boolean,
)

object HandMath {
    private const val RAD2DEG = 57.29578f
    const val RAW_COUNT = 6
    val RAW_NAMES = arrayOf("pinky", "ring", "middle", "index", "thumb curl", "thumb swing")

    // MediaPipe landmark indices: 0 wrist; thumb 1-4; index 5-8; middle 9-12; ring 13-16; pinky 17-20
    private val FINGER_CHAINS = arrayOf(            // order: pinky, ring, middle, index
        intArrayOf(17, 18, 19, 20),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(5, 6, 7, 8),
    )

    /** Interior angle at b (deg). 180 = straight. */
    private fun angle(a: V3, b: V3, c: V3): Float {
        val cos = (a - b).unit().dot((c - b).unit()).coerceIn(-1f, 1f)
        return acos(cos) * RAD2DEG
    }

    private fun flex(a: V3, b: V3, c: V3) = 180f - angle(a, b, c)

    /**
     * @param w 21 MediaPipe WORLD landmarks (metres, camera-aligned axes: x right, y down, z away from camera)
     * @param rightHand true if the hand being tracked is a right hand
     */
    fun compute(w: List<V3>, rightHand: Boolean): HandReading? {
        if (w.size < 21) return null

        // ---------- Orientation frame (palm / back independent) ----------
        // u = along the hand (wrist -> middle MCP), s = across the palm, n = u x s = PALM outward normal for a
        // right hand (mirrored sign for a left hand). n follows the anatomy, so the maths is valid whether the
        // palm or the back of the hand faces the camera.
        // Hand axis = wrist -> centre of the four knuckles (more stable than the middle knuckle alone: it averages
        // landmark noise and sits on the anatomical axis, which removes most pitch<->roll cross-talk).
        val knuckles = V3(
            (w[5].x + w[9].x + w[13].x + w[17].x) * 0.25f,
            (w[5].y + w[9].y + w[13].y + w[17].y) * 0.25f,
            (w[5].z + w[9].z + w[13].z + w[17].z) * 0.25f,
        )
        val u = (knuckles - w[0]).unit()
        val sRaw = if (rightHand) w[17] - w[5] else w[5] - w[17]
        val s = (sRaw - u * sRaw.dot(u)).unit()
        val n = u.cross(s)
        if (u.len() < 0.5f || s.len() < 0.5f || n.len() < 0.5f) return null

        val cam = V3(0f, 0f, -1f)                    // direction from the hand towards the camera
        val palmFacing = cam.dot(n) > 0f

        // Forearm roll = angle of the camera direction around the hand axis, measured from the palm normal.
        var roll = atan2(cam.dot(s), cam.dot(n)) * RAD2DEG      // palm->camera = 0, back->camera = +/-180
        if (Config.FOLD_PALM_BACK) {
            if (roll > 90f) roll -= 180f else if (roll < -90f) roll += 180f
        }
        val pitch = asin(u.z.coerceIn(-1f, 1f)) * RAD2DEG        // + = fingers tilt away from camera

        // ---------- Finger curl ----------
        val palmLen = (w[9] - w[0]).len().coerceAtLeast(1e-4f)
        val raw = FloatArray(RAW_COUNT)
        for (i in 0 until 4) {
            val c = FINGER_CHAINS[i]
            val total = flex(w[0], w[c[0]], w[c[1]]) +     // MCP
                    flex(w[c[0]], w[c[1]], w[c[2]]) +      // PIP
                    flex(w[c[1]], w[c[2]], w[c[3]])        // DIP
            val angleM = (total - Config.FINGER_FLEX_STRAIGHT) / (Config.FINGER_FLEX_FIST - Config.FINGER_FLEX_STRAIGHT)
            val ratio = (w[c[3]] - w[0]).len() / palmLen   // depth-robust: shrinks as the finger curls
            val distM = (Config.FINGER_DIST_OPEN - ratio) / (Config.FINGER_DIST_OPEN - Config.FINGER_DIST_CLOSED)
            raw[i] = (1f - Config.FINGER_DIST_WEIGHT) * angleM + Config.FINGER_DIST_WEIGHT * distM
        }

        // ---------- Thumb curl (flexion only - opposition is handled by the swing channel) ----------
        val thumbFlex = flex(w[1], w[2], w[3]) + flex(w[2], w[3], w[4])
        raw[4] = (thumbFlex - Config.THUMB_FLEX_STRAIGHT) / (Config.THUMB_FLEX_CURLED - Config.THUMB_FLEX_STRAIGHT)

        // ---------- Thumb swing: where the thumb metacarpal (CMC -> MCP) points around the palm plane ----------
        // m is expressed in the palm frame. 'outward' = component pointing away from the fingers' side (beside the palm),
        // n = palm normal (in front of the palm). Using atan2 makes the angle independent of how far the thumb is
        // spread (abduction) and of the camera angle; it only changes when the thumb swings in front of the palm.
        val m = w[2] - w[1]
        val outward = if (rightHand) -m.dot(s) else m.dot(s)
        val swingDeg = atan2(m.dot(n), outward) * RAD2DEG
        raw[5] = (swingDeg - Config.THUMB_SWING_BESIDE_DEG) / (Config.THUMB_SWING_FRONT_DEG - Config.THUMB_SWING_BESIDE_DEG)

        return HandReading(raw, roll, pitch, palmFacing)
    }
}
