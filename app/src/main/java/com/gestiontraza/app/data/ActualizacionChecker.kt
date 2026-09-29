package com.gestiontraza.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ActualizacionInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notas: String
)

/**
 * Consulta el último release publicado en GitHub y compara su versión contra la
 * instalada. El release debe traer, además del APK, un asset "version.json" con
 * {"versionCode": N, "versionName": "X.Y", "notas": "..."} — lo sube el mismo
 * proceso que publica el release (no alcanza con el nombre de la etiqueta/tag,
 * que no siempre cambia en cada publicación).
 */
object ActualizacionChecker {
    private const val RELEASE_LATEST_URL =
        "https://api.github.com/repos/fedemartinc-afk/gestion-traza-android/releases/latest"
    private const val ASSET_APK = "Gestion-traza.apk"
    private const val ASSET_VERSION = "version.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** null si no hay una versión más nueva, o si no se pudo consultar — un error de
     *  red acá nunca debe interrumpir al usuario, es solo un aviso opcional. */
    suspend fun verificar(versionCodeActual: Int): ActualizacionInfo? = withContext(Dispatchers.IO) {
        try {
            val release = obtenerJson(RELEASE_LATEST_URL) ?: return@withContext null
            val assets = release.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            var versionUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.optString("name")) {
                    ASSET_APK -> apkUrl = a.optString("browser_download_url")
                    ASSET_VERSION -> versionUrl = a.optString("browser_download_url")
                }
            }
            if (apkUrl.isNullOrBlank() || versionUrl.isNullOrBlank()) return@withContext null
            val version = obtenerJson(versionUrl) ?: return@withContext null
            val remoto = version.optInt("versionCode", -1)
            if (remoto <= versionCodeActual) return@withContext null
            ActualizacionInfo(
                versionCode = remoto,
                versionName = version.optString("versionName", ""),
                apkUrl = apkUrl,
                notas = version.optString("notas", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun obtenerJson(url: String): JSONObject? {
        val req = Request.Builder().url(url).addHeader("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }
}
