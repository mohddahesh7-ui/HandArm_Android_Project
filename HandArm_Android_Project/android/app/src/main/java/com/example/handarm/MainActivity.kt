package com.example.handarm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var calibBanner: TextView
    private lateinit var connectButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var flipButton: Button
    private lateinit var liveSwitch: SwitchCompat
    private lateinit var swapSwitch: SwitchCompat

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private val pipeline = CommandPipeline()
    private lateinit var link: BluetoothLink

    @Volatile private var lensFacing =
        if (Config.CAMERA_FACING_FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    @Volatile private var swapLabels = Config.SWAP_HANDEDNESS_LABEL
    @Volatile private var lastReading: HandReading? = null

    // calibration
    @Volatile private var cal = Calibration()          // loaded from prefs in onCreate (context not ready here)
    @Volatile private var calibrating = false
    @Volatile private var capturing = false
    private val samples: MutableList<HandReading> = Collections.synchronizedList(ArrayList())

    private val prefs by lazy { getSharedPreferences("handarm", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.CAMERA)) startCamera()
        else toast("Camera permission is required")
        autoConnect()
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // phone must not sleep while driving the arm
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER          // overlay math assumes letter-boxing
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        hudText = findViewById(R.id.hudText)
        calibBanner = findViewById(R.id.calibBanner)
        connectButton = findViewById(R.id.connectButton)
        calibrateButton = findViewById(R.id.calibrateButton)
        flipButton = findViewById(R.id.flipButton)
        liveSwitch = findViewById(R.id.liveSwitch)
        swapSwitch = findViewById(R.id.swapSwitch)

        cal = Calibration.decode(prefs.getString("cal", null))
        swapLabels = prefs.getBoolean("swap", Config.SWAP_HANDEDNESS_LABEL)
        swapSwitch.isChecked = swapLabels
        swapSwitch.setOnCheckedChangeListener { _, checked ->
            swapLabels = checked
            prefs.edit().putBoolean("swap", checked).apply()
        }
        liveSwitch.setOnCheckedChangeListener { _, checked -> pipeline.enabled = checked }

        link = BluetoothLink(lifecycleScope) { state, msg -> runOnUiThread { showStatus(state, msg) } }

        connectButton.setOnClickListener {
            if (link.state == BluetoothLink.State.DISCONNECTED) pickDevice() else link.disconnect()
        }
        calibrateButton.setOnClickListener { startCalibration() }
        calibrateButton.setOnLongClickListener {
            cal = Calibration()
            prefs.edit().remove("cal").apply()
            toast("Calibration reset to defaults")
            true
        }

        flipButton.setOnClickListener {                      // always starts on the FRONT camera; this only switches for the session
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
            else CameraSelector.LENS_FACING_FRONT
            overlay.clear()
            startCamera()
        }

        createLandmarker()
        startSender()

        if (requiredPermissions().all { granted(it) }) { startCamera(); autoConnect() }
        else permLauncher.launch(requiredPermissions())
    }

    override fun onDestroy() {
        super.onDestroy()
        link.disconnect()
        analysisExecutor.shutdown()
        landmarker?.close()
    }

    // ------------------------------------------------------------------ permissions
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.CAMERA)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun btGranted() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ MediaPipe
    private fun createLandmarker() {
        try {
            val base = BaseOptions.builder().setModelAssetPath(Config.MODEL_ASSET).build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)               // synchronous; called on the analysis thread
                .setNumHands(2)                                  // see both hands, then pick the right one
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = HandLandmarker.createFromOptions(this, opts)
        } catch (t: Throwable) {
            Log.e(TAG, "Landmarker init failed", t)
            statusText.text = "Model load failed: ${t.message}"
        }
    }

    // ------------------------------------------------------------------ camera
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
                else CameraSelector.LENS_FACING_FRONT
                selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                toast("No front camera found - using the other one")
            }
            val ratio = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            val previewSel = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
            val analysisSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val preview = Preview.Builder().setResolutionSelector(previewSel).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright: Bitmap = if (rot != 0)
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            else raw
            val result = lm.detectForVideo(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
            handleResult(result, upright.width, upright.height)
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            proxy.close()
        }
    }

    private fun handleResult(res: HandLandmarkerResult, iw: Int, ih: Int) {
        // MediaPipe's label convention is for mirrored input; raw camera frames are not mirrored -> swap.
        val wantLabel = if (Config.TRACK_RIGHT_HAND xor swapLabels) "Right" else "Left"

        var best = -1
        var bestScore = -1f
        val hands = res.handedness()
        for (i in hands.indices) {
            val cat = hands[i].firstOrNull() ?: continue
            if (cat.categoryName() == wantLabel && cat.score() >= Config.MIN_HAND_SCORE && cat.score() > bestScore) {
                best = i; bestScore = cat.score()
            }
        }
        if (best < 0 || best >= res.worldLandmarks().size || best >= res.landmarks().size) {
            lastReading = null
            overlay.post { overlay.clear() }
            return                                           // pipeline keeps last pose, then glides to neutral
        }

        val world = res.worldLandmarks()[best].map { V3(it.x(), it.y(), it.z()) }
        val reading = HandMath.compute(world, Config.TRACK_RIGHT_HAND)
        if (reading == null) { overlay.post { overlay.clear() }; return }

        lastReading = reading
        if (capturing) samples.add(reading)
        // While the calibration wizard runs the arm is NOT driven (it glides to neutral).
        if (!calibrating) pipeline.onHand(cal.apply(reading), SystemClock.elapsedRealtime())

        val pts = res.landmarks()[best].map { PointF(it.x(), it.y()) }
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT      // PreviewView mirrors the front camera
        overlay.post { overlay.setHand(pts, iw, ih, mirror) }
    }

    // ------------------------------------------------------------------ calibration wizard
    private fun showBanner(text: String?) {
        calibBanner.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) calibBanner.text = text
    }

    private suspend fun captureStep(msg: String): List<HandReading>? {
        var left = Config.CAL_PREP_MS
        while (left > 0) {
            showBanner("$msg\n\n${left / 1000 + 1}")
            delay(100)
            left -= 100
        }
        samples.clear()
        capturing = true
        showBanner("$msg\n\nHold still…")
        delay(Config.CAL_CAPTURE_MS)
        capturing = false
        val got = synchronized(samples) { samples.toList() }
        return if (got.size >= Config.CAL_MIN_SAMPLES) got else null
    }

    private fun startCalibration() {
        if (calibrating) return
        calibrating = true                                   // arm glides to neutral while calibrating
        lifecycleScope.launch {
            try {
                val openS = captureStep("1/3  OPEN hand: fingers straight, thumb straight and out BESIDE the palm")
                val fistS = if (openS != null) captureStep("2/3  TIGHT FIST: thumb curled and swung IN FRONT of the palm") else null
                val neutS = if (fistS != null) captureStep("3/3  Relax: hold the wrist at the pose you want as CENTRE") else null
                if (openS == null || fistS == null || neutS == null) {
                    toast("Calibration failed: keep your right hand fully in view")
                    return@launch
                }
                val newOpen = FloatArray(HandMath.RAW_COUNT) { i -> median(openS.map { it.fingersRaw[i] }) }
                val newFist = FloatArray(HandMath.RAW_COUNT) { i -> median(fistS.map { it.fingersRaw[i] }) }
                val bad = (0 until HandMath.RAW_COUNT).filter { newFist[it] - newOpen[it] < Config.CAL_MIN_SPAN }
                if (bad.isNotEmpty()) {
                    toast("Not enough open→fist change for: ${bad.joinToString { HandMath.RAW_NAMES[it] }}. Try again.")
                    return@launch
                }
                val c = Calibration(newOpen, newFist, median(neutS.map { it.rollDeg }), median(neutS.map { it.pitchDeg }), true)
                cal = c
                prefs.edit().putString("cal", c.encode()).apply()
                toast("Calibration saved")
            } finally {
                capturing = false
                calibrating = false
                showBanner(null)
            }
        }
    }

    // ------------------------------------------------------------------ fixed-rate sender
    private fun startSender() {
        lifecycleScope.launch(Dispatchers.IO) {
            var next = SystemClock.elapsedRealtime()
            var n = 0
            while (isActive) {
                val v = pipeline.step(SystemClock.elapsedRealtime())
                if (link.state == BluetoothLink.State.CONNECTED) link.send(Protocol.encode(v))

                if (++n % 4 == 0) {
                    val r = lastReading
                    val side = if (r == null) "no hand" else if (r.palmFacingCamera) "PALM to camera" else "BACK to camera"
                    val calTxt = if (cal.calibrated) "calibrated" else "UNCALIBRATED (tap Calibrate)"
                    val txt = "${pipeline.mode} | $side | $calTxt\n" +
                            "rot %3d  lift %3d\n".format(v[0], v[1]) +
                            "P${bar(v[2])} R${bar(v[3])} M${bar(v[4])} I${bar(v[5])} Tc${bar(v[6])} Ts${bar(v[7])}\n" +
                            "%3d %3d %3d %3d  %3d  %3d".format(v[2], v[3], v[4], v[5], v[6], v[7])
                    runOnUiThread { hudText.text = txt }
                }
                next += Config.SEND_INTERVAL_MS
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait) else next = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun bar(v: Int): String {
        val n = (v * 4 + 90) / 180                       // 0..4 filled blocks
        return "█".repeat(n) + "░".repeat(4 - n)
    }

    // ------------------------------------------------------------------ Bluetooth UI
    private fun showStatus(state: BluetoothLink.State, msg: String) {
        statusText.text = msg
        statusText.setTextColor(
            when (state) {
                BluetoothLink.State.CONNECTED -> Color.rgb(80, 220, 80)
                BluetoothLink.State.CONNECTING -> Color.rgb(255, 200, 0)
                BluetoothLink.State.DISCONNECTED -> Color.rgb(255, 82, 82)
            }
        )
        connectButton.text = if (state == BluetoothLink.State.DISCONNECTED) "Connect" else "Disconnect"
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!btGranted()) { permLauncher.launch(requiredPermissions()); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { toast("Turn Bluetooth on first"); return }
        val devices = adapter.bondedDevices.sortedByDescending {
            it.name?.contains(Config.DEVICE_NAME_HINT, ignoreCase = true) == true
        }
        if (devices.isEmpty()) { toast("No paired devices. Pair the ESP32 in Android Bluetooth settings first."); return }
        val labels = devices.map { "${it.name ?: "(unnamed)"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select the ESP32")
            .setItems(labels) { _, i -> connectTo(devices[i]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        prefs.edit().putString("addr", device.address).apply()
        link.connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun autoConnect() {
        if (!btGranted()) return
        val addr = prefs.getString("addr", null) ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        runCatching { connectTo(adapter.getRemoteDevice(addr)) }
    }

    companion object {
        private const val TAG = "HandArm"
    }
}
