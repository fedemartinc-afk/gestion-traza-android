package com.gestiontraza.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Result(val ok: Boolean, val message: String, val data: JSONObject? = null)

    fun verificarSesion(baseUrl: String, token: String): Result {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/sesion/info")
                .addHeader("Authorization", "Bearer $token")
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                val json = JSONObject(body)
                Result(true, "OK", json)
            } else {
                val json = runCatching { JSONObject(body) }.getOrNull()
                Result(false, json?.optString("error") ?: "Error ${resp.code}")
            }
        } catch (e: Exception) {
            Result(false, "Sin conexión: ${e.message}")
        }
    }

    fun enviarNuevaTRI(
        baseUrl: String,
        token: String,
        nota: String,
        caravanas: List<String>
    ): Result {
        return try {
            val body = JSONObject().apply {
                put("tipo", "tri")
                put("nota", nota)
                put("caravanas", JSONArray(caravanas))
            }
            val req = Request.Builder()
                .url("$baseUrl/api/android/enviar")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                Result(true, "Enviadas para nueva TRI")
            } else {
                val json = runCatching { JSONObject(respBody) }.getOrNull()
                Result(false, json?.optString("error") ?: "Error ${resp.code}")
            }
        } catch (e: Exception) {
            Result(false, "Sin conexión: ${e.message}")
        }
    }

    fun enviarCaravanas(
        baseUrl: String,
        token: String,
        dte: String,
        caravanas: List<String>
    ): Result {
        return try {
            val body = JSONObject().apply {
                put("dte", dte)
                put("caravanas", JSONArray(caravanas))
            }
            val req = Request.Builder()
                .url("$baseUrl/api/android/enviar")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                Result(true, "Enviadas correctamente")
            } else {
                val json = runCatching { JSONObject(respBody) }.getOrNull()
                Result(false, json?.optString("error") ?: "Error ${resp.code}")
            }
        } catch (e: Exception) {
            Result(false, "Sin conexión: ${e.message}")
        }
    }

    private fun senasaUrl(env: String): String {
        val base = when (env) {
            "produccion_gov" -> "https://aps2.senasa.gov.ar/sigsa/seam/resource/rest"
            "produccion_gob" -> "https://aps2.senasa.gob.ar/sigsa/seam/resource/rest"
            else             -> "https://rep.senasa.gov.ar/sigsa/seam/resource/rest"
        }
        return "$base/lotes-microchips/guardar"
    }

    fun enviarCierre(
        baseUrl: String,
        token: String,
        wsUsername: String,
        wsToken: String,
        dte: String,
        caravanas: List<String>,
        lat: Double?,
        lon: Double?,
        senasaEnv: String = "replica"
    ): Result {
        return try {
            // 1. Llamar a SENASA directamente (igual que lo hace el navegador web)
            val senasaBody = buildSenasaBody(wsUsername, wsToken, dte, caravanas, lat, lon)
            val senasaReq = Request.Builder()
                .url(senasaUrl(senasaEnv))
                .addHeader("Content-Type", "application/json")
                .post(senasaBody.toString().toRequestBody(JSON))
                .build()
            val senasaResp = client.newCall(senasaReq).execute()
            val senasaRespBody = senasaResp.body?.string() ?: ""

            val exitoso = senasaResp.isSuccessful.let {
                // SENASA a veces devuelve 200 con ok:false
                val j = runCatching { org.json.JSONObject(senasaRespBody) }.getOrNull()
                it && (j == null || j.optBoolean("ok", true))
            }

            // 2. Notificar al servidor web para que aparezca en Recibidas
            runCatching {
                val notifBody = JSONObject().apply {
                    put("dte", dte)
                    put("caravanas", JSONArray(caravanas))
                    put("tipo", "cierre")
                }
                val notifReq = Request.Builder()
                    .url("$baseUrl/api/android/enviar")
                    .addHeader("Authorization", "Bearer $token")
                    .post(notifBody.toString().toRequestBody(JSON))
                    .build()
                client.newCall(notifReq).execute()
            }

            if (exitoso) {
                Result(true, "Cierre enviado correctamente a SENASA")
            } else {
                val j = runCatching { org.json.JSONObject(senasaRespBody) }.getOrNull()
                val msg = j?.optString("error") ?: j?.optString("message") ?: "Error ${senasaResp.code}"
                Result(false, "SENASA: $msg")
            }
        } catch (e: Exception) {
            Result(false, "Error de conexión: ${e.message}")
        }
    }

    private fun buildSenasaBody(
        wsUsername: String,
        wsToken: String,
        dte: String,
        caravanas: List<String>,
        lat: Double?,
        lon: Double?
    ): JSONObject {
        // El DTe para SENASA va sin el dígito verificador (ej: "032030063-0" → "032030063")
        val dteNumero = dte.split("-")[0].trim()
        val latVal = lat ?: 0.0
        val lonVal = lon ?: 0.0

        val identificaciones = JSONArray()
        caravanas.forEach { c ->
            identificaciones.put(JSONObject().apply {
                put("identificacion", c.replace(" ", "").replace("-", ""))
                put("latitud", latVal)
                put("longitud", lonVal)
                put("genero", "")
            })
        }
        return JSONObject().apply {
            put("wsUsername",       wsUsername)
            put("wsToken",          wsToken)
            put("especie",          "01")
            put("servicio",         "CIERRE")
            put("numero",           dte)        // lote = DTe completo con dígito
            put("dte",              dteNumero)  // DTe sin dígito verificador
            put("identificaciones", identificaciones)
        }
    }
}
