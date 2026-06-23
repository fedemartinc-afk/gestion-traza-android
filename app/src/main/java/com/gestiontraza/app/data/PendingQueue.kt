package com.gestiontraza.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PendingQueue(context: Context) {

    private val prefs = context.getSharedPreferences("pending_queue", Context.MODE_PRIVATE)

    data class PendingItem(
        val tipo: String,
        val dtes: List<String>,
        val caravanas: List<String>,
        val lat: Double?,
        val lon: Double?,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun add(item: PendingItem) {
        val arr = rawArray()
        arr.put(JSONObject().apply {
            put("tipo", item.tipo)
            put("dtes", JSONArray(item.dtes))
            put("caravanas", JSONArray(item.caravanas))
            if (item.lat != null) put("lat", item.lat) else put("lat", JSONObject.NULL)
            if (item.lon != null) put("lon", item.lon) else put("lon", JSONObject.NULL)
            put("timestamp", item.timestamp)
        })
        save(arr)
    }

    fun count(): Int = rawArray().length()

    fun toList(): List<PendingItem> {
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
                    timestamp = j.getLong("timestamp")
                )
            }.getOrNull()
        }
    }

    fun clear() = save(JSONArray())

    private fun rawArray(): JSONArray {
        val s = prefs.getString("items", "[]") ?: "[]"
        return runCatching { JSONArray(s) }.getOrElse { JSONArray() }
    }

    private fun save(arr: JSONArray) =
        prefs.edit().putString("items", arr.toString()).apply()
}
