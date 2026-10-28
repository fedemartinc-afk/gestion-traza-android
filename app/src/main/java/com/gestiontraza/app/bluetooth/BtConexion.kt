package com.gestiontraza.app.bluetooth

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * La única conexión con el lector, compartida por todas las pantallas y por
 * [BtService]. Vive mientras viva el proceso (el servicio en primer plano lo
 * mantiene vivo aunque se cierre la app), a diferencia del ViewModel, que
 * moría con la Activity y cortaba el lector.
 */
object BtConexion {

    private var manager: BtManager? = null

    private val _estado = MutableLiveData<BtConnectionViewModel.Estado>(BtConnectionViewModel.Estado.Desconectado)
    val estado: LiveData<BtConnectionViewModel.Estado> = _estado

    // Cola en vez de un valor unico: onLine puede llegar mas rapido de lo que
    // la pantalla activa procesa, y una LiveData simple perderia lecturas. Con la
    // app cerrada las caravanas se acumulan acá y se entregan al abrir una pantalla.
    private val pendientes = ConcurrentLinkedQueue<String>()
    private val _hayLineas = MutableLiveData<Unit>()
    val hayLineas: LiveData<Unit> = _hayLineas

    fun manager(app: Application): BtManager = manager ?: BtManager(
        app.getSystemService(BluetoothManager::class.java)?.adapter, app
    ).also { m ->
        m.listener = object : BtManager.Listener {
            override fun onConnected(deviceName: String) { _estado.value = BtConnectionViewModel.Estado.Conectado(deviceName) }
            override fun onLine(line: String) {
                pendientes.add(line)
                _hayLineas.value = Unit
            }
            override fun onDisconnected() { _estado.value = BtConnectionViewModel.Estado.Desconectado }
            override fun onError(msg: String) { _estado.value = BtConnectionViewModel.Estado.Error(msg) }
        }
        manager = m
    }

    fun pairedDevices(app: Application): List<BluetoothDevice> = manager(app).pairedDevices()

    fun isConnected(app: Application): Boolean = manager(app).isConnected

    fun conectar(app: Application, device: BluetoothDevice) {
        _estado.value = BtConnectionViewModel.Estado.Conectando(device.name ?: device.address)
        BtService.iniciar(app)
        manager(app).connect(device)
    }

    fun desconectar(app: Application) {
        manager(app).disconnect()
        _estado.value = BtConnectionViewModel.Estado.Desconectado
        BtService.detener(app)
    }

    /** Vacía y devuelve todas las líneas acumuladas desde el último drenaje. */
    fun drenarLineas(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(pendientes.poll() ?: break)
        return out
    }
}
