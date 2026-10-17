package com.gestiontraza.app.data

import android.util.Log
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

    /**
     * Avisa al servidor qué caravanas se consultaron correctamente en SENASA,
     * para el contador de caravanas únicas por usuario (web + móvil). Es
     * "mejor esfuerzo": si falla, no debe afectar la consulta real — se llama
     * sin bloquear la pantalla y se ignora cualquier error.
     */
    fun registrarConsultas(baseUrl: String, token: String, caravanas: List<String>) {
        if (caravanas.isEmpty()) return
        try {
            val body = JSONObject().apply { put("caravanas", JSONArray(caravanas)) }
            val req = Request.Builder()
                .url("$baseUrl/api/android/registrar-consulta")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                Log.i("ApiClient", "registrarConsultas: OK (${caravanas.size} caravana(s)) en $baseUrl")
            } else {
                Log.w("ApiClient", "registrarConsultas: HTTP ${resp.code} en $baseUrl/api/android/registrar-consulta — ${resp.body?.string()}")
            }
            resp.close()
        } catch (e: Exception) {
            // No debe interrumpir el flujo real de consulta — pero sí queda logueado para poder diagnosticar.
            Log.w("ApiClient", "registrarConsultas: no se pudo notificar a $baseUrl (${e.message})")
        }
    }

    /** Sexo leído por caravana (código → "M"/"H"): solo se manda si se registró. */
    private fun JSONObject.putSexos(sexos: Map<String, String>) {
        if (sexos.isNotEmpty()) put("sexos", JSONObject(sexos))
    }

    fun enviarNuevaTRI(
        baseUrl: String,
        token: String,
        nota: String,
        caravanas: List<String>,
        sexos: Map<String, String> = emptyMap()
    ): Result {
        return try {
            val body = JSONObject().apply {
                put("tipo", "tri")
                put("nota", nota)
                put("caravanas", JSONArray(caravanas))
                putSexos(sexos)
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
        caravanas: List<String>,
        sexos: Map<String, String> = emptyMap()
    ): Result {
        return try {
            val body = JSONObject().apply {
                put("dte", dte)
                put("caravanas", JSONArray(caravanas))
                putSexos(sexos)
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

    /**
     * Envío genérico al usuario web, con tipo de operación.
     * Lo usan los módulos del perfil productor para que la web registre de dónde viene el dato.
     */
    fun enviarAWeb(
        baseUrl: String,
        token: String,
        dte: String,
        caravanas: List<String>,
        tipo: String,
        extra: Map<String, String> = emptyMap(),
        sexos: Map<String, String> = emptyMap()
    ): Result {
        return try {
            val body = JSONObject().apply {
                put("dte", dte)
                put("caravanas", JSONArray(caravanas))
                putSexos(sexos)
                put("tipo", tipo)
                extra.forEach { (k, v) -> put(k, v) }
            }
            val req = Request.Builder()
                .url("$baseUrl/api/android/enviar")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val rb = resp.body?.string() ?: ""
            if (resp.isSuccessful) Result(true, "Enviado al usuario web")
            else {
                val j = runCatching { JSONObject(rb) }.getOrNull()
                Result(false, j?.optString("error") ?: "Error ${resp.code}")
            }
        } catch (e: Exception) {
            Result(false, "Sin conexión: ${e.message}")
        }
    }

    private fun senasaUrl(env: String): String {
        val base = when (env) {
            "produccion_gov" -> "https://aps2.senasa.gov.ar/sigsa/seam/resource/rest"
            "local"          -> "http://localhost:8080/sigsa/seam/resource/rest"
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
        senasaEnv: String = "replica",
        sexos: Map<String, String> = emptyMap()
    ): Result {
        FormatoCaravana.invalidas(caravanas).takeIf { it.isNotEmpty() }?.let {
            return Result(false, FormatoCaravana.mensaje(it))
        }
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


            // SENASA responde HTTP 200 aunque rechace el pedido: el error viene en el cuerpo ("errorCode" o
            // ok:false). Antes se daba por bueno todo lo que no dijera ok:false, y un rechazo con errorCode
            // aparecía como "enviado con éxito" sin que las caravanas llegaran al DTe.
            val jSenasa = runCatching { org.json.JSONObject(senasaRespBody) }.getOrNull()
            val (exitoso, errorSenasa) = SenasaClient.parsearRespuestaSenasa(jSenasa, senasaResp.code)
            val lote = if (exitoso) SenasaClient.extraerNumeroLote(jSenasa) else null

            // 2. Notificar al servidor web para que aparezca en Recibidas (solo si SENASA lo aceptó)
            if (exitoso) runCatching {
                val notifBody = JSONObject().apply {
                    put("dte", NumeroDte.nueve(dte))
                    put("caravanas", JSONArray(caravanas))
                    putSexos(sexos)
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
                val entorno = if (senasaEnv == "produccion_gov") "producción" else "RÉPLICA (pruebas, no llega al SIGSA real)"
                val detalleLote = lote?.let { "lote N° $it" } ?: "SENASA no informó número de lote"
                Result(true, "Cierre aceptado por SENASA ($detalleLote, entorno $entorno)")
            } else {
                Result(false, "SENASA rechazó el cierre: $errorSenasa")
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
        val dteNumero = NumeroDte.nueve(dte)
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
            put("numero",           dteNumero)  // lote = DTe (9 dígitos)
            put("dte",              dteNumero)  // DTe sin dígito verificador
            put("identificaciones", identificaciones)
        }
    }
}
