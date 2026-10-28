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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
// Un lector dormido puede tardar ~14 s solo en responder al llamado (medido con
// el Tru-Test XRS2), así que el corte tiene que quedar bien por encima de eso.
private const val LIMITE_INTENTO_SPP_MS = 30_000L

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
    // Lector SYNO-3-SPP (confirmado con hardware real): mismo servicio ffe0 pero
    // la característica que notifica es ffe4; ffe1 solo permite leer/escribir.
    PerfilBle(
        "SYNO (ffe0/ffe4)",
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
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

// Servicios genéricos de cualquier dispositivo BLE (acceso, atributos, info del
// fabricante, batería): nunca llevan caravanas.
private fun esServicioEstandar(uuid: UUID): Boolean {
    val s = uuid.toString().lowercase()
    return s.endsWith("-0000-1000-8000-00805f9b34fb") && s.substring(4, 8) in setOf("1800", "1801", "180a", "180f")
}

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
    private var connectJob: Job? = null
    private val prefsVariantes by lazy { context.getSharedPreferences("bt_variantes", Context.MODE_PRIVATE) }
    var listener: Listener? = null

    val isConnected: Boolean get() = socket?.isConnected == true || gatt != null

    fun pairedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(device: BluetoothDevice) {
        disconnect()
        // disconnect() cancela el intento anterior: dos conexiones simultáneas al
        // mismo lector se estorban entre sí y alargan la espera.
        connectJob = CoroutineScope(Dispatchers.IO).launch {
            adapter?.cancelDiscovery()
            var errorSpp: Exception? = null
            var errorBle: Exception? = null
            val inicio = System.currentTimeMillis()
            Log.i("BtManager", "conectando a ${device.name} (tipo ${device.type})")
            // Algunos lectores (ej. Gallagher) no completan bien el handshake del
            // socket "seguro" — se prueban variantes hasta encontrar una que ande.
            suspend fun porSpp(): Boolean {
                // La variante que funcionó la última vez con este lector va primero:
                // en el Tru-Test solo conecta "canal1" y las otras esperan en vano.
                val clave = "spp_${device.address}"
                val ultima = prefsVariantes.getString(clave, null)
                val variantes = listOf("segura", "insegura", "canal1")
                val orden = if (ultima in variantes) listOf(ultima!!) + (variantes - ultima!!) else variantes
                for (intento in orden) {
                    currentCoroutineContext().ensureActive()
                    var sock: BluetoothSocket? = null
                    // connect() puede colgarse ~60 s si el lector no responde; una
                    // conexión sana tarda segundos, así que se corta cerrando el socket.
                    var vigilante: Job? = null
                    try {
                        val nuevo = when (intento) {
                            "segura"   -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                            "insegura" -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                            else       -> device.javaClass
                                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                                .invoke(device, 1) as BluetoothSocket
                        }
                        sock = nuevo
                        vigilante = CoroutineScope(Dispatchers.IO).launch {
                            delay(LIMITE_INTENTO_SPP_MS)
                            runCatching { nuevo.close() }
                        }
                        nuevo.connect()
                        vigilante.cancel()
                        socket = nuevo
                        prefsVariantes.edit().putString(clave, intento).apply()
                        Log.i("BtManager", "conectado por SPP ($intento) en ${System.currentTimeMillis() - inicio} ms")
                        withContext(Dispatchers.Main) { listener?.onConnected(device.name ?: "Lector") }
                        startReading(nuevo)
                        return true
                    } catch (e: Exception) {
                        vigilante?.cancel()
                        Log.e("BtManager", "connect error ($intento) tras ${System.currentTimeMillis() - inicio} ms", e)
                        errorSpp = e
                        runCatching { sock?.close() }
                        socket = null
                    }
                }
                return false
            }
            // Lectores que solo tienen BLE (ej. Gallagher HR5 v3, SYNO-3-SPP).
            suspend fun porBle(): Boolean {
                currentCoroutineContext().ensureActive()
                try {
                    withTimeout(15000) { connectBle(device) }
                    Log.i("BtManager", "conectado por BLE en ${System.currentTimeMillis() - inicio} ms")
                    withContext(Dispatchers.Main) { listener?.onConnected(device.name ?: "Lector") }
                    return true
                } catch (e: Exception) {
                    Log.e("BtManager", "connect error (ble)", e)
                    errorBle = e
                    runCatching { gatt?.close() }
                    gatt = null
                    return false
                }
            }
            // Cada intento SPP fallido tarda ~20 s: si Android informa que el
            // dispositivo es solo-BLE se empieza por BLE. Si falla, se prueba el
            // otro camino igual, para no perder lectores mal clasificados.
            val conectado = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                porBle() || porSpp()
            } else {
                porSpp() || porBle()
            }
            if (!conectado) {
                // Se informa el error del camino que corresponde al lector, no el
                // del último probado (un lector clásico no tiene nada que ver con BLE).
                val causa = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) errorBle ?: errorSpp else errorSpp ?: errorBle
                withContext(Dispatchers.Main) { listener?.onError("No se pudo conectar: ${causa?.message}") }
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

    /**
     * Conexión BLE para lectores sin SPP clásico (ej. Gallagher HR5 v3). Busca
     * el servicio "Tagging" (lectura de caravanas) y se suscribe a sus
     * notificaciones — cada una se entrega como una línea, igual que en modo
     * SPP, para que el resto de la app no tenga que distinguir el origen.
     */
    private suspend fun connectBle(device: BluetoothDevice) {
        val descriptoresPendientes = ArrayDeque<BluetoothGattDescriptor>()
        fun escribirSiguienteDescriptor(g: BluetoothGatt) {
            val d = descriptoresPendientes.removeFirstOrNull() ?: return
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
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
                        val avisa = c != null && c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (service != null && c != null && avisa) {
                            chara = c
                            perfilUsado = perfil.nombre
                            break
                        }
                    }
                    // Se deja en el log todo lo que ofrece el lector, para poder
                    // agregarle un perfil propio si hace falta.
                    val conNotificacion = mutableListOf<BluetoothGattCharacteristic>()
                    for (s in g.services) {
                        Log.i("BtManager", "BLE servicio ${s.uuid}")
                        for (c in s.characteristics) {
                            Log.i("BtManager", "BLE   característica ${c.uuid} propiedades 0x${Integer.toHexString(c.properties)}")
                            val avisa = c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                            if (avisa && !esServicioEstandar(s.uuid)) conNotificacion += c
                        }
                    }
                    // Sin perfil conocido se escuchan todas las características que
                    // avisan datos (fuera de los servicios genéricos del sistema).
                    val aSuscribir = if (chara != null) listOf(chara) else conNotificacion
                    if (aSuscribir.isEmpty()) {
                        cont.resumeWithException(Exception("El dispositivo no tiene ninguno de los servicios BLE conocidos"))
                        return
                    }
                    Log.i("BtManager", "BLE conectado usando perfil: ${perfilUsado ?: "genérico (${aSuscribir.size} características)"}")
                    for (c in aSuscribir) {
                        g.setCharacteristicNotification(c, true)
                        val cccd = c.getDescriptor(CCCD_UUID) ?: continue
                        val soloIndica = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                        @Suppress("DEPRECATION")
                        cccd.value = if (soloIndica) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                     else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        descriptoresPendientes.addLast(cccd)
                    }
                    escribirSiguienteDescriptor(g)
                    gatt = g
                    cont.resume(Unit)
                }

                // Android admite una sola operación GATT a la vez: los descriptores
                // se escriben de a uno, esperando la confirmación de cada uno.
                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    escribirSiguienteDescriptor(g)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    val bytes = characteristic.value ?: return
                    Log.i("BtManager", "BLE dato de ${characteristic.uuid}: ${bytes.joinToString(" ") { "%02x".format(it) }}")
                    val texto = bytes.toString(Charsets.UTF_8).trim()
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
        connectJob?.cancel()
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        runCatching { gatt?.close() }
        gatt = null
    }
}
