package com.gestiontraza.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// UUID estándar Bluetooth SPP (Serial Port Profile)
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private data class PerfilBle(val nombre: String, val servicio: UUID, val caracteristica: UUID)

// Candidatos de servicio/característica BLE a probar cuando el lector no tiene
// SPP clásico (ej. Gallagher HR5 v3). Ninguno está confirmado contra hardware
// real todavía — salen de descompilar apps oficiales y de perfiles genéricos
// muy usados por fabricantes de módulos BLE-serie:
private val PERFILES_BLE = listOf(
    // Gallagher "Tagging" — app oficial "Gallagher Animal Performance"
    // (clase BleServiceUUID: TaggingServiceUUID / TaggingCharaUUID).
    PerfilBle(
        "Gallagher Tagging",
        UUID.fromString("f9c5aa00-e843-4ab3-b7ec-bf840ac14980"),
        UUID.fromString("f9c5aa01-e843-4ab3-b7ec-bf840ac14980")
    ),
    // Gallagher "RealTraceTagging" (misma app) — usa el UUID genérico CC254X,
    // un chip BLE-serie muy común en varios fabricantes.
    PerfilBle(
        "CC254X genérico (HM-10 y similares)",
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    ),
    // Nordic UART Service — estándar de facto para módulos BLE-serie basados
    // en chips Nordic nRF5x (visto también referenciado en la app de Tru-Test).
    PerfilBle(
        "Nordic UART genérico",
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    ),
)

@SuppressLint("MissingPermission")
class BtManager(private val adapter: BluetoothAdapter?, private val context: Context) {

    interface Listener {
        fun onConnected(deviceName: String)
        fun onLine(line: String)
        fun onDisconnected()
        fun onError(msg: String)
    }

    private var socket: BluetoothSocket? = null
    private var gatt: BluetoothGatt? = null
    private var readJob: Job? = null
    var listener: Listener? = null

    val isConnected: Boolean get() = socket?.isConnected == true || gatt != null

    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(device: BluetoothDevice) {
        disconnect()
        CoroutineScope(Dispatchers.IO).launch {
            adapter?.cancelDiscovery()
            var ultimoError: Exception? = null
            // Algunos lectores (ej. Gallagher) no completan bien el handshake del
            // socket "seguro" — se prueban variantes hasta encontrar una que ande.
            for (intento in listOf("segura", "insegura", "canal1")) {
                try {
                    val sock = when (intento) {
                        "segura"   -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                        "insegura" -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        else       -> device.javaClass
                            .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(device, 1) as BluetoothSocket
                    }
                    sock.connect()
                    socket = sock
                    withContext(Dispatchers.Main) { listener?.onConnected(device.name ?: "Lector") }
                    startReading(sock)
                    return@launch
                } catch (e: Exception) {
                    Log.e("BtManager", "connect error ($intento)", e)
                    ultimoError = e
                    runCatching { socket?.close() }
                    socket = null
                }
            }
            // Si ninguna variante de socket RFCOMM/SPP funcionó, puede ser un
            // lector que solo tiene BLE (ej. Gallagher HR5 v3) — se intenta como
            // último recurso.
            try {
                withTimeout(15000) { connectBle(device) }
                withContext(Dispatchers.Main) { listener?.onConnected(device.name ?: "Lector") }
                return@launch
            } catch (e: Exception) {
                Log.e("BtManager", "connect error (ble)", e)
                ultimoError = e
                runCatching { gatt?.close() }
                gatt = null
            }
            withContext(Dispatchers.Main) { listener?.onError("No se pudo conectar: ${ultimoError?.message}") }
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

    /**
     * Conexión BLE para lectores sin SPP clásico (ej. Gallagher HR5 v3). Busca
     * el servicio "Tagging" (lectura de caravanas) y se suscribe a sus
     * notificaciones — cada una se entrega como una línea, igual que en modo
     * SPP, para que el resto de la app no tenga que distinguir el origen.
     */
    private suspend fun connectBle(device: BluetoothDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            gatt = null
                            if (cont.isActive) {
                                cont.resumeWithException(Exception("BLE desconectado (status $status)"))
                            } else {
                                CoroutineScope(Dispatchers.Main).launch { listener?.onDisconnected() }
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (!cont.isActive) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(Exception("Fallo al descubrir servicios BLE ($status)"))
                        return
                    }
                    var chara: BluetoothGattCharacteristic? = null
                    var perfilUsado: String? = null
                    for (perfil in PERFILES_BLE) {
                        val service = g.getService(perfil.servicio)
                        val c = service?.getCharacteristic(perfil.caracteristica)
                        if (service != null && c != null) {
                            chara = c
                            perfilUsado = perfil.nombre
                            break
                        }
                    }
                    if (chara == null) {
                        cont.resumeWithException(Exception("El dispositivo no tiene ninguno de los servicios BLE conocidos"))
                        return
                    }
                    Log.i("BtManager", "BLE conectado usando perfil: $perfilUsado")
                    g.setCharacteristicNotification(chara, true)
                    val cccd = chara.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    gatt = g
                    cont.resume(Unit)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    val texto = characteristic.value?.toString(Charsets.UTF_8)?.trim() ?: return
                    if (texto.isNotEmpty()) {
                        CoroutineScope(Dispatchers.Main).launch { listener?.onLine(texto) }
                    }
                }
            }
            val g = device.connectGatt(context, false, callback)
            cont.invokeOnCancellation { runCatching { g.close() } }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        runCatching { gatt?.close() }
        gatt = null
    }
}
