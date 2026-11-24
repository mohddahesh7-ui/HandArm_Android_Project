package com.example.handarm

import java.util.UUID

/**
 * Every tunable constant lives here.
 * Output order everywhere: rot, lift, pinky, ring, middle, index, thumbCurl, thumbSwing  (8 values, 0..180).
 * (Pinky and ring are measured separately; the ESP32 averages them onto the single shared pinky+ring motor.)
 */
object Config {
    // ---------------- Bluetooth ----------------
    const val DEVICE_NAME_HINT = "HandArm"                       // bonded devices with this name are listed first
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val RECONNECT_DELAY_MS = 1500L

    const val NUM_VALUES = 8                  // fields per packet

    // ---------------- Send rate ----------------
    const val SEND_INTERVAL_MS = 33L          // ~30 packets/s, fixed-rate, independent of camera FPS

    // ---------------- Smoothing ----------------
    // One-Euro filter on every channel, run per camera frame: heavy smoothing when still, little lag when moving fast.
    const val EURO_MIN_CUTOFF_HZ = 1.2f       // lower = steadier when hand is still (more lag)
    const val EURO_BETA = 0.04f               // higher = less lag on fast moves (more jitter on fast moves)
    const val EURO_D_CUTOFF_HZ = 1.0f
    const val FILTER_RESET_GAP_MS = 500L      // hand gone this long -> restart filters (no dragging from stale values)
    // Final stage at the send rate:
    const val DEADBAND_DEG = 1.0f             // ignore changes smaller than this
    const val MAX_STEP_PER_TICK = 12f         // slew limit, degrees per send tick (~360 deg/s at 30 Hz)

    // ---------------- No-hand behaviour ----------------
    const val HOLD_LAST_MS = 800L             // keep last pose this long after the hand disappears
    const val RETURN_TO_NEUTRAL = true        // after the hold time glide to NEUTRAL (false = hold forever)
    val NEUTRAL = floatArrayOf(90f, 90f, 0f, 0f, 0f, 0f, 0f, 0f)   // wrist centred, fingers open, thumb beside palm

    // ---------------- Camera ----------------
    const val CAMERA_FACING_FRONT = true      // front (selfie) camera; the app has no camera switch

    // ---------------- Hand selection ----------------
    const val TRACK_RIGHT_HAND = true         // user's real right hand
    // MediaPipe labels assume a MIRRORED image. Raw (un-mirrored) camera frames therefore come back with the
    // label swapped: a real right hand is reported as "Left". Also togglable at runtime with the "Swap L/R" switch.
    const val SWAP_HANDEDNESS_LABEL = true
    const val MIN_HAND_SCORE = 0.6f           // ignore low-confidence handedness

    // ---------------- Finger curl (raw metric; the in-app Calibrate button refines it per finger) ----------------
    // raw = blend of (a) joint-angle flexion and (b) fingertip-to-wrist distance / palm length.
    const val FINGER_FLEX_STRAIGHT = 25f      // total MCP+PIP+DIP flexion of a straight finger (deg)
    const val FINGER_FLEX_FIST = 235f         // ... of a closed fist
    const val FINGER_DIST_OPEN = 1.90f        // tip-to-wrist / palm length, finger straight
    const val FINGER_DIST_CLOSED = 0.75f      // ... finger curled into palm
    const val FINGER_DIST_WEIGHT = 0.35f      // 0 = angles only, 1 = distance only
    const val THUMB_FLEX_STRAIGHT = 15f       // thumb curl servo: IP+MCP flexion, thumb straight (deg)
    const val THUMB_FLEX_CURLED = 105f        // ... thumb fully curled
    // Thumb swing servo: angle of the thumb metacarpal measured around the palm plane.
    // 0 deg = thumb out BESIDE the palm (in the palm plane), 90 deg = pointing straight out of the palm, IN FRONT of it.
    const val THUMB_SWING_BESIDE_DEG = 5f
    const val THUMB_SWING_FRONT_DEG = 65f

    // ---------------- Wrist orientation ----------------
    const val ROLL_RANGE_DEG = 70f            // forearm roll (+/-) that maps to the full 0..180 servo span
    const val PITCH_RANGE_DEG = 50f           // hand pitch (+/-) that maps to the full 0..180 span
    const val ROLL_INVERT = false
    const val PITCH_INVERT = false
    /** true: palm-to-camera and back-of-hand-to-camera both read roll 0 (roll folded into +/-90). */
    const val FOLD_PALM_BACK = true

    // ---------------- Calibration wizard ----------------
    const val CAL_PREP_MS = 3000L             // countdown before each capture
    const val CAL_CAPTURE_MS = 1500L          // capture window per step
    const val CAL_MIN_SAMPLES = 8             // frames needed in a window
    const val CAL_MIN_SPAN = 0.2f             // fist - open must differ by at least this (raw units)

    // ---------------- Model ----------------
    const val MODEL_ASSET = "hand_landmarker.task"
}
