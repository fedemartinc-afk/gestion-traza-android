package com.gestiontraza.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cola de operaciones pendientes de reenviar cuando no hay conexión.
 *
 * Cada ítem queda etiquetado con la cuenta que lo generó (cuentaId), para
 * que enviarlo más tarde use siempre las credenciales de esa cuenta —sin
 * importar cuál esté activa en el dispositivo en ese momento— y para que
 * el conteo/envío en Inicio opere solo sobre los pendientes de la cuenta
 * activa, no sobre los de otras cuentas guardadas en el mismo teléfono.
 */
class PendingQueue(context: Context) {

    private val prefs = context.getSharedPreferences("pending_queue", Context.MODE_PRIVATE)

    data class PendingItem(
        val tipo: String,
        val dtes: List<String>,
        val caravanas: List<String>,
        val lat: Double?,
        val lon: Double?,
        val cuentaId: String,
        val timestamp: Long = System.currentTimeMillis(),
        /** Título opcional del mensaje (máx. 25 caracteres) para "Enviar a usuario web" sin módulo. */
        val titulo: String = ""
    )

    fun add(item: PendingItem) {
        val arr = rawArray()
        arr.put(itemToJson(item))
        save(arr)
    }

    /**
     * Pendientes de una cuenta. Los que quedaron sin etiquetar (encolados
     * antes de existir multi-cuenta) se consideran de esa cuenta también:
     * en ese momento solo podía existir una sola cuenta en el dispositivo.
     */
    fun paraCuenta(cuentaId: String): List<PendingItem> =
        toList().filter { it.cuentaId == cuentaId || it.cuentaId.isBlank() }

    fun countPara(cuentaId: String): Int = paraCuenta(cuentaId).size

    /** Reemplaza los pendientes de [cuentaId] por [nuevos], sin tocar los de otras cuentas. */
    fun reemplazarCuenta(cuentaId: String, nuevos: List<PendingItem>) {
        val deOtrasCuentas = toList().filterNot { it.cuentaId == cuentaId || it.cuentaId.isBlank() }
        guardarTodos(deOtrasCuentas + nuevos)
    }

    private fun toList(): List<PendingItem> {
        val arr = rawArray()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val j = arr.getJSONObject(i)
                val dtesArr = j.getJSONArray("dtes")
                val carArr = j.getJSONArray("caravanas")
                PendingItem(
                    tipo = j.getString("tipo"),
                    dtes = (0 until dtesArr.length()).map { dtesArr.getString(it) },
                    caravanas = (0 until carArr.length()).map { carArr.getString(it) },
                    lat = if (j.isNull("lat")) null else j.getDouble("lat"),
                    lon = if (j.isNull("lon")) null else j.getDouble("lon"),
                    cuentaId = j.optString("cuentaId", ""),
                    timestamp = j.getLong("timestamp"),
                    titulo = j.optString("titulo", "")
                )
            }.getOrNull()
        }
    }

    private fun itemToJson(item: PendingItem) = JSONObject().apply {
        put("tipo", item.tipo)
        put("dtes", JSONArray(item.dtes))
        put("caravanas", JSONArray(item.caravanas))
        put("lat", item.lat ?: JSONObject.NULL)
        put("lon", item.lon ?: JSONObject.NULL)
        put("cuentaId", item.cuentaId)
        put("timestamp", item.timestamp)
        put("titulo", item.titulo)
    }

    private fun guardarTodos(items: List<PendingItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(itemToJson(it)) }
        save(arr)
    }

    private fun rawArray(): JSONArray {
        val s = prefs.getString("items", "[]") ?: "[]"
        return runCatching { JSONArray(s) }.getOrElse { JSONArray() }
    }

    private fun save(arr: JSONArray) =
        prefs.edit().putString("items", arr.toString()).apply()
}
