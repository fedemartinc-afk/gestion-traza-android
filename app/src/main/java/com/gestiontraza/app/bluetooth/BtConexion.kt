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
        guardarUltimo(app, device)
        _estado.value = BtConnectionViewModel.Estado.Conectando(device.name ?: device.address)
        BtService.iniciar(app)
        manager(app).connect(device)
    }

    // ---- Último lector: se recuerda para volver a conectarlo solo al abrir la app ----
    private const val PREFS_ULTIMO = "bt_ultimo_lector"

    private fun guardarUltimo(app: Application, device: BluetoothDevice) {
        app.getSharedPreferences(PREFS_ULTIMO, android.content.Context.MODE_PRIVATE).edit()
            .putString("direccion", device.address).putString("nombre", device.name ?: device.address).putBoolean("auto", true).apply()
    }

    /** El lector se desconectó a propósito: no se vuelve a conectar solo hasta que se lo elija otra vez. */
    fun desconectarManual(app: Application) {
        app.getSharedPreferences(PREFS_ULTIMO, android.content.Context.MODE_PRIVATE).edit().putBoolean("auto", false).apply()
        desconectar(app)
    }

    /**
     * Si no hay un lector conectado ni conectándose y quedó recordado el último (y sigue vinculado en Android), lo conecta.
     * Devuelve su nombre si arrancó la conexión. El vínculo lo guarda Android: sobrevive a las actualizaciones de la app.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun reconectarUltimo(app: Application): String? {
        val estadoActual = _estado.value
        if (estadoActual !is BtConnectionViewModel.Estado.Desconectado) return null
        val prefs = app.getSharedPreferences(PREFS_ULTIMO, android.content.Context.MODE_PRIVATE)
        val direccion = prefs.getString("direccion", null) ?: return null
        if (!prefs.getBoolean("auto", true)) return null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val device = try { pairedDevices(app).firstOrNull { it.address == direccion } } catch (e: SecurityException) { null } ?: return null
        conectar(app, device)
        return device.name ?: device.address
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
