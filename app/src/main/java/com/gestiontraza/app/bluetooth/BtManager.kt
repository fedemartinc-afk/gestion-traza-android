package com.gestiontraza.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

// UUID estándar Bluetooth SPP (Serial Port Profile)
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class BtManager(private val adapter: BluetoothAdapter?) {

    interface Listener {
        fun onConnected(deviceName: String)
        fun onLine(line: String)
        fun onDisconnected()
        fun onError(msg: String)
    }

    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    var listener: Listener? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(device: BluetoothDevice) {
        disconnect()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter?.cancelDiscovery()
                sock.connect()
                socket = sock
                withContext(Dispatchers.Main) { listener?.onConnected(device.name ?: "Lector") }
                startReading(sock)
            } catch (e: Exception) {
                Log.e("BtManager", "connect error", e)
                withContext(Dispatchers.Main) { listener?.onError("No se pudo conectar: ${e.message}") }
            }
        }
    }

    private fun startReading(sock: BluetoothSocket) {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    val l = line.trim()
                    if (l.isNotEmpty()) {
                        withContext(Dispatchers.Main) { listener?.onLine(l) }
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("BtManager", "read error", e)
            }
            withContext(Dispatchers.Main) { listener?.onDisconnected() }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }
}
