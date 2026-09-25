package com.gestiontraza.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SenasaClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    /** Base de SENASA segun el entorno configurado (replica / produccion_gov / local) */
    fun senasaBase(env: String): String = when (env) {
        "produccion_gov" -> "https://aps2.senasa.gov.ar/sigsa/seam/resource/rest"
        "local"          -> "http://localhost:8080/sigsa/seam/resource/rest"
        else             -> "https://rep.senasa.gov.ar/sigsa/seam/resource/rest"
    }

    data class EstadoCaravana(
        val codigo: String,
        val ok: Boolean,
        val renspaActual: String,
        val bloqueada: Boolean,
        val deBaja: Boolean,
        val reemplazada: Boolean,
        val excluidaUE: Boolean,
        val fechaIngreso: String = "",
        val error: String = "",
        /** Nombre del establecimiento donde está hoy la caravana (entidadActual.nombre) */
        val establecimiento: String = "",
        /** Nombre del titular de esa entidad (entidadActual.titular) — puede diferir del establecimiento */
        val titular: String = "",
        val sexo: String = "",
        val raza: String = ""
    )

    data class DteInfo(
        val origenCodigo: String,
        val origenNombre: String,
        val destinoCodigo: String,
        val caravanas: List<String>,
        val tieneTRI: Boolean,
        val estado: String,
        /** Número de TRI del movimiento; vacío si SENASA no lo informa. */
        val nroTri: String = ""
    )

    data class Result(val ok: Boolean, val message: String)

    /**
     * Parsea una respuesta JSON de SENASA.
     * SENASA usa HTTP 200 para todo — el error viene dentro del body con "errorCode".
     * Devuelve Pair(exitoso, mensajeError). Si exitoso=true, mensajeError está vacío.
     */
    private fun parsearRespuestaSenasa(j: JSONObject?, httpCode: Int): Pair<Boolean, String> {
        if (j == null) return Pair(false, "Respuesta vacía (HTTP $httpCode)")
        // Error explícito de SENASA: campo errorCode presente
        if (j.has("errorCode")) {
            val msg = j.optString("errorMessage").ifBlank { "Error SENASA ${j.optInt("errorCode")}" }
            return Pair(false, msg)
        }
        val ok = j.optBoolean("ok", false) || j.optBoolean("success", false)
        if (!ok) {
            // SENASA devuelve el mensaje de error en "data" cuando ok:false
            val dataStr = j.opt("data")?.let { if (it is String) it else null }
            val msg = dataStr?.ifBlank { null }
                ?: j.optString("errorMessage").ifBlank { null }
                ?: j.optString("error").ifBlank { null }
                ?: j.optString("message").ifBlank { null }
                ?: j.optString("descripcion").ifBlank { null }
                ?: "HTTP $httpCode"
            return Pair(false, msg)
        }
        return Pair(true, "")
    }

    /** Extrae número de lote de distintos campos posibles en la respuesta */
    private fun extraerNumeroLote(j: JSONObject?): String? {
        if (j == null) return null
        // SENASA devuelve el N° de lote directamente en "data" (número entero)
        val dataRaw = j.opt("data")
        if (dataRaw != null && dataRaw !is JSONObject && dataRaw !is org.json.JSONArray) {
            val s = dataRaw.toString().trim()
            if (s.isNotBlank() && s != "null") return s
        }
        val data = j.optJSONObject("data")
        val lote = data?.opt("numeroLote") ?: data?.opt("lote") ?: data?.opt("id")
            ?: data?.opt("numero") ?: j.opt("numeroLote") ?: j.opt("lote") ?: j.opt("id")
        return lote?.toString()?.ifBlank { null }
    }

    /** Consulta individual de caravana en SENASA */
    fun consultarCaravana(senasaBase: String, wsUsername: String, wsToken: String, caravana: String): EstadoCaravana {
        return try {
            val payload = JSONObject().apply {
                put("wsUsername", wsUsername)
                put("wsToken", wsToken)
                put("caravana", caravana)
            }
            val req = Request.Builder()
                .url(senasaBase.trimEnd('/') + "/trazabilidad/buscar")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MT))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: return EstadoCaravana(codigo = caravana, ok = false, renspaActual = "", bloqueada = false, deBaja = false, reemplazada = false, excluidaUE = false, error = "Sin respuesta")

            val (exitoso, errorMsg) = parsearRespuestaSenasa(j, resp.code)
            if (!exitoso) return EstadoCaravana(codigo = caravana, ok = false, renspaActual = "", bloqueada = false, deBaja = false, reemplazada = false, excluidaUE = false, error = errorMsg)

            val data = j.optJSONObject("data")
                ?: return EstadoCaravana(codigo = caravana, ok = false, renspaActual = "", bloqueada = false, deBaja = false, reemplazada = false, excluidaUE = false, error = "Sin datos")
            val entidad = data.optJSONObject("entidadActual")
            // optString devuelve "" (no null) si falta la clave, y "null" si viene null: se
            // prueba cada campo y se toma el primero con contenido (igual que la web).
            fun texto(o: JSONObject?, k: String) =
                if (o == null || o.isNull(k)) "" else o.optString(k, "").trim()
            val renspa = texto(entidad, "codigo").ifBlank { texto(entidad, "renspa") }
            val fechaIngreso = entidad?.optString("fechaIngreso")?.ifBlank { null }
                ?: entidad?.optString("fechaEntrada")?.ifBlank { null }
                ?: data.optString("fechaIngreso")?.ifBlank { null }
                ?: data.optString("fechaEntrada")?.ifBlank { null }
                ?: ""

            // Establecimiento: nombre de la entidad donde está la caravana hoy
            val establecimiento = entidad?.optString("nombre")?.ifBlank { null }
                ?: entidad?.optString("razonSocial")?.ifBlank { null }
                ?: ""

            // Titular: puede venir como objeto {nombre: ...} o como texto plano
            val titularRaw = entidad?.opt("titular")
            val titular = when (titularRaw) {
                is JSONObject -> titularRaw.optString("nombre", "")
                is String     -> titularRaw
                else          -> ""
            }

            val sexo = data.optString("sexo", "")
            val raza = data.optJSONObject("razaAnimal")?.optString("nombre", "") ?: ""

            EstadoCaravana(
                codigo = caravana,
                ok = true,
                renspaActual = renspa,
                bloqueada = data.optBoolean("bloqueada", false),
                deBaja = data.optBoolean("deBaja", false),
                reemplazada = data.optBoolean("reemplazada", false),
                excluidaUE = data.optBoolean("excluidaUE", false),
                fechaIngreso = fechaIngreso,
                establecimiento = establecimiento,
                titular = titular,
                sexo = sexo,
                raza = raza
            )
        } catch (e: Exception) {
            EstadoCaravana(codigo = caravana, ok = false, renspaActual = "", bloqueada = false, deBaja = false, reemplazada = false, excluidaUE = false, error = e.message ?: "Error")
        }
    }

    /** Consulta detalle de DT-e para obtener origen, destino y caravanas del movimiento */
    fun consultarDte(senasaBase: String, wsUsername: String, wsToken: String, dte: String): Pair<Boolean, DteInfo?> {
        return try {
            val payload = JSONObject().apply {
                put("wsUsername", wsUsername)
                put("wsToken", wsToken)
                put("numero", dte)
            }
            val req = Request.Builder()
                .url(senasaBase.trimEnd('/') + "/movimientos/detalle")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MT))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: return Pair(false, null)

            val exitoso = j.optBoolean("ok", false) || j.optBoolean("success", false)
            if (!exitoso) return Pair(false, null)

            val data = j.optJSONObject("data") ?: return Pair(false, null)
            val origen = data.optJSONObject("entidadOrigen")
            val destino = data.optJSONObject("entidadDestino")

            // Caravanas TRI: buscar en los mismos campos que usa la web
            val caravanas = mutableListOf<String>()
            val arrCar = data.optJSONArray("caravanasTRI")
                ?: data.optJSONArray("caravanasTri")
                ?: data.optJSONArray("caravanasRecibidas")
                ?: data.optJSONArray("identificaciones")
                ?: data.optJSONArray("caravanas")
            if (arrCar != null) {
                for (i in 0 until arrCar.length()) {
                    val elem = arrCar.optJSONObject(i)
                    val cod = if (elem != null)
                        elem.optString("identificacion").ifBlank { null }
                            ?: elem.optString("codigo").ifBlank { null }
                            ?: ""
                    else arrCar.optString(i) ?: ""
                    if (cod.length >= 6) caravanas.add(cod.trim().uppercase().replace(" ", ""))
                }
            }
            // También intentar como string separado por comas/espacios
            if (caravanas.isEmpty()) {
                val strTRI = data.optString("caravanasTRI").ifBlank { null }
                    ?: data.optString("caravanasTri").ifBlank { null }
                    ?: data.optString("caravanasRecibidas").ifBlank { null }
                strTRI?.split(Regex("[\\s,;]+"))?.filter { it.length >= 6 }
                    ?.forEach { caravanas.add(it.trim().uppercase()) }
            }

            // tieneTRI: verificar número de TRI o flags del movimiento
            val nroTRI = data.optString("tri").ifBlank { null }
                ?: data.optString("nroTri").ifBlank { null }
                ?: data.optString("nroTRI").ifBlank { null }
                ?: ""
            val tieneTRI = nroTRI.isNotBlank()
                || data.optBoolean("tieneTriOriginal", false)
                || data.optString("tipoMovimiento", "").contains("TRI", ignoreCase = true)

            Pair(true, DteInfo(
                origenCodigo = origen?.optString("codigo") ?: data.optString("origen", ""),
                origenNombre = origen?.optString("nombre") ?: "",
                destinoCodigo = destino?.optString("codigo") ?: data.optString("destino", ""),
                caravanas = caravanas,
                tieneTRI = tieneTRI,
                estado = data.optString("estado", ""),
                nroTri = nroTRI
            ))
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    /** Envía cierre a SENASA directamente */
    fun enviarCierre(
        senasaBase: String,
        wsUsername: String,
        wsToken: String,
        dte: String,
        caravanas: List<String>,
        lat: Double?,
        lon: Double?
    ): Result {
        FormatoCaravana.invalidas(caravanas).takeIf { it.isNotEmpty() }?.let {
            return Result(false, FormatoCaravana.mensaje(it))
        }
        return try {
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
            val body = JSONObject().apply {
                put("wsUsername", wsUsername)
                put("wsToken", wsToken)
                put("especie", "01")
                put("servicio", "CIERRE")
                put("numero", dte)
                put("dte", dteNumero)
                put("identificaciones", identificaciones)
            }
            val req = Request.Builder()
                .url(senasaBase.trimEnd('/') + "/lotes-microchips/guardar")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MT))
                .build()
            val resp = client.newCall(req).execute()
            val rb = resp.body?.string() ?: ""
            val j = runCatching { JSONObject(rb) }.getOrNull()
            val (ok, errorMsg) = parsearRespuestaSenasa(j, resp.code)
            if (ok) {
                val lote = extraerNumeroLote(j)
                Result(true, if (lote != null) "Lote SENASA: $lote" else "Enviado a DT-e correctamente")
            } else {
                Result(false, errorMsg)
            }
        } catch (e: Exception) {
            Result(false, "Error de conexión: ${e.message}")
        }
    }

    /**
     * Convierte nacimiento de "MM/AAAA" al formato requerido por SENASA "AAAA-MM".
     */
    private fun convertirNacimiento(mmaaaa: String): String {
        if (mmaaaa.isBlank()) return ""
        val parts = mmaaaa.split("/")
        return if (parts.size == 2) "${parts[1]}-${parts[0]}" else mmaaaa
    }

    /**
     * Declara nuevos dispositivos en SENASA.
     * Endpoint: lotes-microchips/guardar con servicio DECLARACION.
     */
    fun declararDispositivos(
        senasaBase: String,
        wsUsername: String,
        wsToken: String,
        renspa: String,
        especie: String,
        numero: String,
        dispositivos: List<DispositivoDeclaracion>,
        lat: Double?,
        lon: Double?
    ): Result {
        return try {
            val identificaciones = JSONArray()
            dispositivos.forEach { d ->
                identificaciones.put(JSONObject().apply {
                    put("identificacion", d.codigo.replace(" ", "").replace("-", ""))
                    put("latitud", lat ?: 0.0)
                    put("longitud", lon ?: 0.0)
                    put("genero", d.sexo)
                    put("raza", d.razaCodigo)
                    put("nacimiento", convertirNacimiento(d.nacimiento))
                })
            }
            val body = JSONObject().apply {
                put("wsUsername", wsUsername)
                put("wsToken", wsToken)
                put("renspa", renspa)
                put("especie", especie)
                put("servicio", "DECLARACION")
                put("numero", numero)
                put("identificaciones", identificaciones)
            }
            val req = Request.Builder()
                .url(senasaBase.trimEnd('/') + "/lotes-microchips/guardar")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MT))
                .build()
            val resp = client.newCall(req).execute()
            val rb = resp.body?.string() ?: ""
            val j = runCatching { JSONObject(rb) }.getOrNull()
            val (ok, errorMsg) = parsearRespuestaSenasa(j, resp.code)
            if (ok) {
                val lote = extraerNumeroLote(j)
                Result(true, if (lote != null) "Lote SENASA: $lote" else "Declaración enviada — sin número de lote en respuesta")
            } else {
                Result(false, errorMsg)
            }
        } catch (e: Exception) {
            Result(false, "Error de conexión: ${e.message}")
        }
    }

    /**
     * Envía caravanas a SENASA con servicio TRI.
     * Endpoint: lotes-microchips/guardar con servicio TRI.
     */
    fun enviarTRI(
        senasaBase: String,
        wsUsername: String,
        wsToken: String,
        renspa: String,
        especie: String,
        numero: String,
        caravanas: List<String>,
        lat: Double?,
        lon: Double?
    ): Result {
        return try {
            val identificaciones = JSONArray()
            caravanas.forEach { cod ->
                identificaciones.put(JSONObject().apply {
                    put("identificacion", cod.replace(" ", "").replace("-", ""))
                    put("latitud", lat ?: 0.0)
                    put("longitud", lon ?: 0.0)
                    put("genero", "")
                })
            }
            val body = JSONObject().apply {
                put("wsUsername", wsUsername)
                put("wsToken", wsToken)
                put("renspa", renspa)
                put("especie", especie)
                put("servicio", "TRI")
                put("numero", numero)
                put("identificaciones", identificaciones)
            }
            val req = Request.Builder()
                .url(senasaBase.trimEnd('/') + "/lotes-microchips/guardar")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MT))
                .build()
            val resp = client.newCall(req).execute()
            val rb = resp.body?.string() ?: ""
            val j = runCatching { JSONObject(rb) }.getOrNull()
            val (ok, errorMsg) = parsearRespuestaSenasa(j, resp.code)
            if (ok) {
                val lote = extraerNumeroLote(j)
                Result(true, if (lote != null) "Lote SENASA: $lote" else "TRI enviado correctamente")
            } else {
                Result(false, errorMsg)
            }
        } catch (e: Exception) {
            Result(false, "Error de conexión: ${e.message}")
        }
    }

    data class DispositivoDeclaracion(
        val codigo: String,
        val sexo: String,
        val razaCodigo: String,
        val nacimiento: String
    )
}
