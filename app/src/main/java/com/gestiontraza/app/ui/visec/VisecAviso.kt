package com.gestiontraza.app.ui.visec

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDeepLinkBuilder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Aviso cuando termina una verificación en VISEC enviada desde esta app. Se guardan los números de las solicitudes enviadas y un
 * trabajo en segundo plano (cada ~15 minutos, el mínimo que permite Android) le pregunta a la web si ya terminaron: por cada una que
 * terminó, bien o mal, muestra una notificación que abre "Informes VISEC". Solo avisa de lo enviado desde esta app.
 */
object VisecAviso {
    private const val PREFS = "visec_avisos"
    private const val CLAVE_PENDIENTES = "pendientes"
    private const val TRABAJO = "visec_avisos"
    private const val TRABAJO_RAPIDO = "visec_avisos_rapido"
    const val CANAL = "visec_informes"

    @Synchronized
    fun pendientes(ctx: Context): Set<Long> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CLAVE_PENDIENTES, emptySet())
            .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    @Synchronized
    private fun guardar(ctx: Context, ids: Set<Long>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(CLAVE_PENDIENTES, ids.map { it.toString() }.toSet()).apply()
    }

    /** Una verificación recién enviada: queda esperando su aviso y se programa la revisión en segundo plano. */
    fun registrar(ctx: Context, id: Long) {
        if (id <= 0) return
        guardar(ctx, pendientes(ctx) + id)
        programar(ctx)
        programarRapido(ctx)
    }

    /** Las que ya se vieron en la lista dejan de esperar su aviso. */
    fun quitar(ctx: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        guardar(ctx, pendientes(ctx) - ids.toSet())
    }

    /**
     * Le pregunta a la web por los envíos que esperan y avisa de los que terminaron (bien, con error o cancelados).
     * Devuelve true si todavía queda alguno esperando. Lo usan el trabajo en segundo plano y la pantalla de Inicio (con la app abierta).
     */
    suspend fun revisar(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val ids = pendientes(ctx)
        if (ids.isEmpty()) return@withContext false
        val session = SessionManager(ctx)
        if (session.token.isBlank()) return@withContext true
        val r = ApiClient.informesVisec(session.baseUrl(), session.token, ids)
        if (!r.ok) {
            // Sin permiso (módulo dado de baja): no hay nada que avisar. Otro error (sin conexión): se reintenta en la próxima vuelta.
            if (r.message.contains("habilitado")) { quitar(ctx, ids); return@withContext false }
            return@withContext true
        }
        val lista = r.data?.optJSONArray("informes")
        val vistos = mutableSetOf<Long>()
        for (i in 0 until (lista?.length() ?: 0)) {
            val x = lista!!.getJSONObject(i)
            val id = x.optLong("id")
            vistos += id
            if (!x.optBoolean("terminado")) continue
            val nombre = x.optString("nombre")
            when (x.optString("estado")) {
                "finalizado" -> avisar(ctx, id, "Informe de VISEC listo", "$nombre: ${resumenResultadoVisec(x)}")
                "cancelada" -> avisar(ctx, id, "Informe de VISEC cancelado", nombre)
                else -> avisar(ctx, id, "Informe de VISEC con error", "$nombre: ${x.optString("error").ifBlank { "No se pudo completar." }}")
            }
            quitar(ctx, listOf(id))
        }
        // Los que la web ya no devuelve (borrados o muy viejos) dejan de esperar.
        quitar(ctx, ids - vistos)
        pendientes(ctx).isNotEmpty()
    }

    /** Una revisión dentro de un minuto (Android no deja que el trabajo periódico corra más seguido que cada 15 minutos). */
    fun programarRapido(ctx: Context, demoraSeg: Long = 60) {
        val pedido = OneTimeWorkRequestBuilder<VisecAvisoWorker>()
            .setInitialDelay(demoraSeg, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(TRABAJO_RAPIDO, ExistingWorkPolicy.REPLACE, pedido)
    }

    fun programar(ctx: Context) {
        val pedido = PeriodicWorkRequestBuilder<VisecAvisoWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.KEEP, pedido)
    }

    fun cancelar(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(TRABAJO)
        WorkManager.getInstance(ctx).cancelUniqueWork(TRABAJO_RAPIDO)
    }

    /** Al abrir la app: si quedaron envíos esperando, se vuelve a programar la revisión (por si el sistema la canceló). */
    fun reprogramarSiHayPendientes(ctx: Context) {
        if (pendientes(ctx).isNotEmpty()) programar(ctx)
    }

    // ---- Informes terminados que el usuario todavía no vio en la lista (número rojo en el botón de Inicio) ----
    private const val CLAVE_VISTOS = "vistos"

    private fun vistos(ctx: Context): Set<Long>? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CLAVE_VISTOS, null)?.mapNotNull { it.toLongOrNull() }?.toSet()

    /** Los informes que se están viendo en la lista dejan de contar como "sin ver". */
    fun marcarVistos(ctx: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(CLAVE_VISTOS, ((vistos(ctx) ?: emptySet()) + ids).map { it.toString() }.toSet()).apply()
    }

    /** Cuántos informes terminados no se vieron todavía (null si no se pudo consultar). La primera vez, los que ya existían cuentan como vistos. */
    suspend fun contarNoVistos(ctx: Context, session: SessionManager): Int? {
        val r = withContext(Dispatchers.IO) { ApiClient.informesVisec(session.baseUrl(), session.token) }
        if (!r.ok) return null
        val lista = r.data?.optJSONArray("informes")
        val terminados = (0 until (lista?.length() ?: 0)).map { lista!!.getJSONObject(it) }.filter { it.optBoolean("terminado") }.map { it.optLong("id") }
        val previos = vistos(ctx)
        if (previos == null) { marcarVistos(ctx, terminados); ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(CLAVE_VISTOS, terminados.map { it.toString() }.toSet()).apply(); return 0 }
        return terminados.count { it !in previos }
    }

    fun puedeNotificar(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun avisar(ctx: Context, id: Long, titulo: String, texto: String) {
        if (!puedeNotificar(ctx)) return
        val gestor = ctx.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Informes de VISEC", NotificationManager.IMPORTANCE_DEFAULT))
        // Abre directo la lista de informes, con Inicio detrás.
        val abrir = NavDeepLinkBuilder(ctx).setGraph(R.navigation.nav_graph).setDestination(R.id.informesVisecFragment).createPendingIntent()
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(5000 + (id % 1000).toInt(), n)
    }
}

/** El resultado de un informe terminado: la leyenda que VISEC deja en Observaciones y, si no hay, el conteo de caravanas. */
fun resumenResultadoVisec(x: org.json.JSONObject): String {
    val validas = x.optInt("habilitadas"); val invalidas = x.optInt("inhabilitadas")
    return when {
        x.optString("lote_texto").isNotBlank() -> x.optString("lote_texto")
        x.optBoolean("todas_validas") ->
            "✓ Todas las caravanas del lote fueron declaradas válidas" + if (validas > 0) " ($validas)" else ""
        validas + invalidas > 0 ->
            "✓ $validas válida${if (validas == 1) "" else "s"}   ✗ $invalidas inválida${if (invalidas == 1) "" else "s"}"
        else -> "Sin el detalle de las caravanas"
    }
}

class VisecAvisoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (VisecAviso.pendientes(ctx).isEmpty()) { VisecAviso.cancelar(ctx); return Result.success() }
        // Mientras queden envíos esperando, se vuelve a revisar enseguida (y no solo cada 15 minutos).
        if (VisecAviso.revisar(ctx)) VisecAviso.programarRapido(ctx) else if (VisecAviso.pendientes(ctx).isEmpty()) VisecAviso.cancelar(ctx)
        return Result.success()
    }
}
