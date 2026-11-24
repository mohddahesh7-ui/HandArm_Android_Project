package com.example.handarm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

/** Classic Bluetooth SPP client with automatic reconnect. The ESP32 must already be paired in Android settings. */
class BluetoothLink(
    private val scope: CoroutineScope,
    private val onStatus: (State, String) -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile private var out: OutputStream? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var job: Job? = null
    @Volatile private var generation = 0

    private fun setState(gen: Int, s: State, msg: String) {
        if (gen != generation) return                // a newer connect()/disconnect() superseded this loop
        state = s
        onStatus(s, msg)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        val gen = ++generation
        val label = device.name ?: device.address
        job = scope.launch(Dispatchers.IO) {
            var first = true
            while (isActive) {
                setState(gen, State.CONNECTING, if (first) "Connecting to $label…" else "Link lost – reconnecting…")
                var s: BluetoothSocket? = null
                try {
                    s = device.createRfcommSocketToServiceRecord(Config.SPP_UUID)
                    socket = s
                    s.connect()
                    out = s.outputStream
                    first = false
                    setState(gen, State.CONNECTED, "Connected: $label")
                    val input = s.inputStream
                    val buf = ByteArray(64)
                    while (isActive && input.read(buf) >= 0) { /* ESP32 sends nothing; read() just detects link loss */ }
                } catch (e: IOException) {
                    Log.w("BluetoothLink", "link error: ${e.message}")
                } catch (e: SecurityException) {
                    Log.e("BluetoothLink", "missing permission", e)
                    setState(gen, State.DISCONNECTED, "Bluetooth permission denied")
                    return@launch
                } finally {
                    out = null
                    runCatching { s?.close() }
                    socket = null
                }
                if (!isActive) break
                setState(gen, State.CONNECTING, "Link lost – reconnecting…")
                delay(Config.RECONNECT_DELAY_MS)
            }
        }
    }

    fun disconnect() {
        generation++
        job?.cancel()
        job = null
        runCatching { socket?.close() }              // unblocks read()/connect()
        out = null
        state = State.DISCONNECTED
        onStatus(State.DISCONNECTED, "Disconnected")
    }

    /** Call from a background thread. Returns false if not connected or the write failed. */
    fun send(bytes: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            o.write(bytes)
            o.flush()
            true
        } catch (e: IOException) {
            runCatching { socket?.close() }          // read loop exits -> reconnect logic kicks in
            false
        }
    }
}
