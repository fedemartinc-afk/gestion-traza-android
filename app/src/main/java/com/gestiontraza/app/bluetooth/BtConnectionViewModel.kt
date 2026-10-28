package com.gestiontraza.app.bluetooth

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData

/**
 * Acceso de las pantallas a la unica conexion con el lector, compartida entre
 * Inicio, Lectura y cualquier otra pantalla (ver [BtConexion]). Antes cada
 * fragment creaba su propio BtManager: conectar el lector desde Inicio no
 * servia en Lectura porque era un socket distinto que nunca llegaba a
 * conectarse, y por eso no se agregaban las caravanas leidas por Bluetooth.
 */
class BtConnectionViewModel(app: Application) : AndroidViewModel(app) {

    sealed class Estado {
        object Desconectado : Estado()
        data class Conectando(val deviceName: String) : Estado()
        data class Conectado(val deviceName: String) : Estado()
        data class Error(val msg: String) : Estado()
    }

    // La conexión vive en BtConexion (y la sostiene BtService), no acá: este
    // ViewModel muere con la Activity y antes cortaba el lector al cerrar la app.
    val estado: LiveData<Estado> = BtConexion.estado
    val hayLineas: LiveData<Unit> = BtConexion.hayLineas

    private val app: Application get() = getApplication()

    fun pairedDevices(): List<BluetoothDevice> = BtConexion.pairedDevices(app)

    fun conectar(device: BluetoothDevice) = BtConexion.conectar(app, device)

    fun isConnected(): Boolean = BtConexion.isConnected(app)

    /** Suelta la conexión de lectura en vivo (ej. para que otra pantalla use el lector). */
    fun desconectar() = BtConexion.desconectar(app)

    /** Vacía y devuelve todas las líneas acumuladas desde el último drenaje. */
    fun drenarLineas(): List<String> = BtConexion.drenarLineas()
}
