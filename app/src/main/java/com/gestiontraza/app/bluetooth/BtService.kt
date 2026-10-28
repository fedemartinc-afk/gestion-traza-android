package com.gestiontraza.app.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.gestiontraza.app.MainActivity
import com.gestiontraza.app.R

/**
 * Servicio en primer plano que mantiene vivo el proceso mientras hay un lector
 * conectado, para que cerrar la app no corte la conexión. La conexión en sí está
 * en [BtConexion]; el servicio solo muestra la notificación y se detiene solo
 * cuando el lector se desconecta o falla.
 */
class BtService : Service() {

    private val observador = Observer<BtConnectionViewModel.Estado> { estado ->
        when (estado) {
            is BtConnectionViewModel.Estado.Conectado -> notificar("Lector conectado: ${estado.deviceName}")
            is BtConnectionViewModel.Estado.Conectando -> notificar("Conectando con ${estado.deviceName}...")
            else -> stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val canal = NotificationChannel(CANAL, "Lector Bluetooth", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DESCONECTAR) {
            BtConexion.desconectar(application)
            return START_NOT_STICKY
        }
        // Android exige llamar a startForeground enseguida después de arrancar.
        val inicial = construirNotificacion("Lector Bluetooth")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACION, inicial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID_NOTIFICACION, inicial)
        }
        // observeForever entrega el estado actual enseguida; conectar() lo deja en
        // "Conectando" antes de arrancar el servicio, así que no se detiene de entrada.
        BtConexion.estado.removeObserver(observador)
        BtConexion.estado.observeForever(observador)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        BtConexion.estado.removeObserver(observador)
        super.onDestroy()
    }

    private fun notificar(texto: String) {
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, construirNotificacion(texto))
    }

    private fun construirNotificacion(texto: String): Notification {
        val abrirApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val desconectar = PendingIntent.getService(
            this, 1, Intent(this, BtService::class.java).setAction(ACCION_DESCONECTAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(texto)
            .setContentText("Tocá para abrir la app")
            .setContentIntent(abrirApp)
            .addAction(0, "Desconectar", desconectar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CANAL = "lector_bt"
        private const val ID_NOTIFICACION = 4201
        private const val ACCION_DESCONECTAR = "com.gestiontraza.app.DESCONECTAR_LECTOR"

        fun iniciar(contexto: Context) {
            ContextCompat.startForegroundService(contexto, Intent(contexto, BtService::class.java))
        }

        fun detener(contexto: Context) {
            contexto.stopService(Intent(contexto, BtService::class.java))
        }
    }
}
