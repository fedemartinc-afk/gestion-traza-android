package com.gestiontraza.app.bluetooth

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mantiene una unica conexion SPP compartida entre Inicio y Lectura (y cualquier
 * otra pantalla). Antes cada fragment creaba su propio BtManager: conectar el
 * lector desde Inicio no servia en Lectura porque era un socket distinto que
 * nunca llegaba a conectarse, y por eso no se agregaban las caravanas leidas
 * por Bluetooth SPP.
 */
class BtConnectionViewModel(app: Application) : AndroidViewModel(app) {

    sealed class Estado {
        object Desconectado : Estado()
        data class Conectando(val deviceName: String) : Estado()
        data class Conectado(val deviceName: String) : Estado()
        data class Error(val msg: String) : Estado()
    }

    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val btManager = BtManager(adapter, app)

    private val _estado = MutableLiveData<Estado>(Estado.Desconectado)
    val estado: LiveData<Estado> = _estado

    // Cola en vez de un valor unico: onLine puede llegar mas rapido de lo que
    // la pantalla activa procesa, y una LiveData simple perderia lecturas.
    private val pendientes = ConcurrentLinkedQueue<String>()
    private val _hayLineas = MutableLiveData<Unit>()
    val hayLineas: LiveData<Unit> = _hayLineas

    init {
        btManager.listener = object : BtManager.Listener {
            override fun onConnected(deviceName: String) { _estado.value = Estado.Conectado(deviceName) }
            override fun onLine(line: String) {
                pendientes.add(line)
                _hayLineas.value = Unit
            }
            override fun onDisconnected() { _estado.value = Estado.Desconectado }
            override fun onError(msg: String) { _estado.value = Estado.Error(msg) }
        }
    }

    fun pairedDevices(): List<BluetoothDevice> = btManager.pairedDevices()

    fun conectar(device: BluetoothDevice) {
        _estado.value = Estado.Conectando(device.name ?: device.address)
        btManager.connect(device)
    }

    fun isConnected(): Boolean = btManager.isConnected

    /** Suelta la conexión de lectura en vivo (ej. para que otra pantalla use el lector). */
    fun desconectar() {
        btManager.disconnect()
        _estado.value = Estado.Desconectado
    }

    /** Vacía y devuelve todas las líneas acumuladas desde el último drenaje. */
    fun drenarLineas(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(pendientes.poll() ?: break)
        return out
    }

    override fun onCleared() {
        super.onCleared()
        btManager.disconnect()
    }
}
