package com.gestiontraza.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Guarda una o varias cuentas (token + credenciales) en el mismo dispositivo.
 *
 * Las propiedades públicas (token, serverUrl, etc.) siguen funcionando igual
 * que antes: leen y escriben sobre la CUENTA ACTIVA, así que el resto de la
 * app no necesita cambios para seguir andando con una sola cuenta. Lo nuevo
 * es que por debajo hay una lista de cuentas en vez de un único bloque de
 * campos sueltos, y cada cuenta tiene un id estable ([cuentaActivaId]) que
 * sirve para asociar datos externos —como la cola de pendientes offline—
 * a la cuenta que los generó, sin depender de cuál esté activa después.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gestion_traza", Context.MODE_PRIVATE)

    init {
        asegurarCuentaActiva()
    }

    // ------------------------------------------------------------------
    // Propiedades — leen/escriben sobre la cuenta activa
    // ------------------------------------------------------------------

    var serverUrl: String
        get() = campo("server_url")
        set(value) = setCampo("server_url", value)

    var token: String
        get() = campo("token")
        set(value) = setCampo("token", value)

    var sesionNombre: String
        get() = campo("sesion_nombre")
        set(value) = setCampo("sesion_nombre", value)

    var usuarioNombre: String
        get() = campo("usuario_nombre")
        set(value) = setCampo("usuario_nombre", value)

    var wsUsername: String
        get() = campo("ws_username")
        set(value) = setCampo("ws_username", value)

    var wsToken: String
        get() = campo("ws_token")
        set(value) = setCampo("ws_token", value)

    var isAdmin: Boolean
        get() = campoBool("is_admin")
        set(value) = setCampoBool("is_admin", value)

    // "replica" o "produccion_gov" — solo modificable por admin
    var senasaEnv: String
        get() = campo("senasa_env", "replica")
        set(value) = setCampo("senasa_env", value)

    // Tipos de sesión habilitados para este usuario ("consignatario", "productor" o ambos)
    var tiposSesionPermitidos: List<String>
        get() = campo("tipos_sesion_permitidos", "consignatario").split(",").filter { it.isNotBlank() }
        set(value) = setCampo("tipos_sesion_permitidos", value.joinToString(","))

    // Tipo elegido por el usuario en esta sesión ("consignatario" o "productor")
    var tipoSesionActual: String
        get() = campo("tipo_sesion_actual")
        set(value) = setCampo("tipo_sesion_actual", value)

    // Ajuste de ingreso manual de caravanas — activado por defecto.
    var manualCaravanas: Boolean
        get() = campoBool("manual_caravanas", true)
        set(value) = setCampoBool("manual_caravanas", value)

    // Ajuste de ingreso manual de DT-e — activado por defecto.
    var manualDte: Boolean
        get() = campoBool("manual_dte", true)
        set(value) = setCampoBool("manual_dte", value)

    // Último RENSPA usado por el perfil productor (declaración de dispositivos / TRI)
    var ultimoRenspa: String
        get() = campo("ultimo_renspa")
        set(value) = setCampo("ultimo_renspa", value)

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && token.isNotBlank()

    fun baseUrl(): String {
        val s = serverUrl.trim().trimEnd('/')
        return if (s.startsWith("http")) s else "https://$s"
    }

    /** Borra todas las cuentas guardadas en este dispositivo. */
    fun clearSession() {
        prefs.edit().clear().apply()
        asegurarCuentaActiva()
    }

    // ------------------------------------------------------------------
    // Multi-cuenta
    // ------------------------------------------------------------------

    /**
     * Id estable de la cuenta activa. No cambia aunque se edite el token o
     * las credenciales de esa cuenta — sirve para asociar datos externos
     * (p. ej. la cola de pendientes offline) a la cuenta que los generó,
     * incluso si más tarde el dispositivo pasa a otra cuenta.
     */
    val cuentaActivaId: String
        get() = prefs.getString(KEY_CUENTA_ACTIVA, null) ?: ""

    data class CuentaCredenciales(
        val baseUrl: String,
        val token: String,
        val wsUsername: String,
        val wsToken: String,
        val senasaEnv: String
    )

    /**
     * Credenciales de una cuenta guardada por id, sea o no la activa.
     * Permite enviar la cola de pendientes con el token correcto aunque el
     * dispositivo haya cambiado de cuenta después de encolar ese ítem, en
     * vez de usar (por error) las credenciales de la sesión activa.
     * Devuelve null si esa cuenta ya no está guardada en el dispositivo.
     */
    fun credencialesDe(cuentaId: String): CuentaCredenciales? {
        val arr = cuentas()
        val idx = buscarIndice(arr, cuentaId)
        if (idx < 0) return null
        val c = arr.getJSONObject(idx)
        val serverUrl = c.optString("server_url", "")
        if (serverUrl.isBlank()) return null
        val s = serverUrl.trim().trimEnd('/')
        return CuentaCredenciales(
            baseUrl    = if (s.startsWith("http")) s else "https://$s",
            token      = c.optString("token", ""),
            wsUsername = c.optString("ws_username", ""),
            wsToken    = c.optString("ws_token", ""),
            senasaEnv  = c.optString("senasa_env", "replica")
        )
    }

    data class CuentaResumen(val id: String, val nombre: String, val activa: Boolean)

    /** Lista las cuentas guardadas en el dispositivo para mostrarlas y elegir entre ellas. */
    fun listarCuentas(): List<CuentaResumen> {
        val arr = cuentas()
        val activaId = cuentaActivaId
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val id = c.optString("id", "")
            // Nombre del usuario primero (quién es la persona), no el nombre
            // de la sesión/dispositivo — es lo que distingue una cuenta de otra.
            val nombre = c.optString("usuario_nombre", "").ifBlank { c.optString("sesion_nombre", "") }
                .ifBlank { "Cuenta sin conectar" }
            CuentaResumen(id = id, nombre = nombre, activa = id == activaId)
        }
    }

    /** Cambia la cuenta activa. Devuelve false si ese id ya no existe en el dispositivo. */
    fun activarCuenta(id: String): Boolean {
        val arr = cuentas()
        if (buscarIndice(arr, id) < 0) return false
        prefs.edit().putString(KEY_CUENTA_ACTIVA, id).apply()
        return true
    }

    /**
     * Crea una cuenta vacía y la deja activa, para que el flujo de conexión
     * (QR o token manual) la complete sin pisar los datos de la cuenta que
     * estaba activa hasta ahora.
     */
    fun crearCuentaYActivar(): String {
        val id = crearCuentaVacia()
        prefs.edit().putString(KEY_CUENTA_ACTIVA, id).apply()
        return id
    }

    /**
     * Elimina una cuenta guardada. Si era la activa, pasa a activar la
     * primera que quede (o crea una vacía si no queda ninguna).
     * Devuelve false si ese id no existía.
     */
    fun eliminarCuenta(id: String): Boolean {
        val arr = cuentas()
        val idx = buscarIndice(arr, id)
        if (idx < 0) return false
        val restantes = JSONArray()
        for (i in 0 until arr.length()) if (i != idx) restantes.put(arr.getJSONObject(i))
        guardarCuentas(restantes)
        if (id == cuentaActivaId) {
            val primerId = if (restantes.length() > 0) restantes.getJSONObject(0).optString("id") else crearCuentaVacia()
            prefs.edit().putString(KEY_CUENTA_ACTIVA, primerId).apply()
        }
        return true
    }

    // ------------------------------------------------------------------
    // Almacenamiento interno
    // ------------------------------------------------------------------

    private fun cuentas(): JSONArray {
        val s = prefs.getString(KEY_CUENTAS, "[]") ?: "[]"
        return runCatching { JSONArray(s) }.getOrElse { JSONArray() }
    }

    private fun guardarCuentas(arr: JSONArray) =
        prefs.edit().putString(KEY_CUENTAS, arr.toString()).apply()

    private fun buscarIndice(arr: JSONArray, id: String): Int {
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") == id) return i
        }
        return -1
    }

    private fun cuentaActiva(): JSONObject {
        val id = prefs.getString(KEY_CUENTA_ACTIVA, null) ?: return JSONObject()
        val arr = cuentas()
        val idx = buscarIndice(arr, id)
        return if (idx >= 0) arr.getJSONObject(idx) else JSONObject()
    }

    private fun campo(key: String, default: String = ""): String =
        cuentaActiva().optString(key, default)

    private fun campoBool(key: String, default: Boolean = false): Boolean =
        cuentaActiva().optBoolean(key, default)

    private fun setCampo(key: String, value: String) = editarCuentaActiva { it.put(key, value) }

    private fun setCampoBool(key: String, value: Boolean) = editarCuentaActiva { it.put(key, value) }

    private fun editarCuentaActiva(cambio: (JSONObject) -> Unit) {
        val id = prefs.getString(KEY_CUENTA_ACTIVA, null) ?: return
        val arr = cuentas()
        val idx = buscarIndice(arr, id)
        if (idx < 0) return
        cambio(arr.getJSONObject(idx))
        guardarCuentas(arr)
    }

    /**
     * Garantiza que exista al menos una cuenta y que quede marcada como
     * activa. Si el dispositivo traía una sesión de la versión anterior
     * (campos sueltos en vez de una lista de cuentas) la migra a la primera
     * cuenta, sin pedirle al usuario que vuelva a escanear el QR.
     */
    private fun asegurarCuentaActiva() {
        if (prefs.contains(KEY_CUENTAS)) {
            corregirIngresoManualPorDefectoUnaVez()
            val arr = cuentas()
            val id = prefs.getString(KEY_CUENTA_ACTIVA, null)
            if (id == null || buscarIndice(arr, id) < 0) {
                val primerId = if (arr.length() > 0) arr.getJSONObject(0).optString("id") else crearCuentaVacia()
                prefs.edit().putString(KEY_CUENTA_ACTIVA, primerId).apply()
            }
            return
        }

        // Primera vez con este formato: migrar los campos sueltos de la version anterior, si hay.
        val cuenta = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("server_url", prefs.getString("server_url", "") ?: "")
            put("token", prefs.getString("token", "") ?: "")
            put("sesion_nombre", prefs.getString("sesion_nombre", "") ?: "")
            put("usuario_nombre", prefs.getString("usuario_nombre", "") ?: "")
            put("ws_username", prefs.getString("ws_username", "") ?: "")
            put("ws_token", prefs.getString("ws_token", "") ?: "")
            put("is_admin", prefs.getBoolean("is_admin", false))
            put("senasa_env", prefs.getString("senasa_env", "replica") ?: "replica")
            put("tipos_sesion_permitidos", prefs.getString("tipos_sesion_permitidos", "consignatario") ?: "consignatario")
            put("tipo_sesion_actual", prefs.getString("tipo_sesion_actual", "") ?: "")
            // Activados por defecto (ver manualCaravanas/manualDte más arriba): una cuenta
            // de antes de que existiera este ajuste nunca guardó nada acá, así que sin el
            // "true" de respaldo esta migración los dejaba apagados para siempre.
            put("manual_caravanas", prefs.getBoolean("manual_caravanas", true))
            put("manual_dte", prefs.getBoolean("manual_dte", true))
            put("ultimo_renspa", prefs.getString("ultimo_renspa", "") ?: "")
        }
        guardarCuentas(JSONArray().put(cuenta))
        prefs.edit().putString(KEY_CUENTA_ACTIVA, cuenta.getString("id")).apply()
    }

    /**
     * Corrección de una sola vez: la migración vieja guardaba "false" explícito para
     * el ingreso manual de caravanas y de DTe en cuentas creadas antes de que
     * existiera ese ajuste, así que quedaba apagado para siempre en vez de prender
     * por defecto como corresponde. Se fuerza a "true" una única vez en todas las
     * cuentas ya guardadas; a partir de ahí, si el usuario lo apaga desde
     * Configuración, esa elección se respeta (no se vuelve a tocar).
     */
    private fun corregirIngresoManualPorDefectoUnaVez() {
        if (prefs.getBoolean(KEY_FIX_INGRESO_MANUAL, false)) return
        val arr = cuentas()
        for (i in 0 until arr.length()) {
            val cuenta = arr.getJSONObject(i)
            cuenta.put("manual_caravanas", true)
            cuenta.put("manual_dte", true)
        }
        guardarCuentas(arr)
        prefs.edit().putBoolean(KEY_FIX_INGRESO_MANUAL, true).apply()
    }

    private fun crearCuentaVacia(): String {
        val id = UUID.randomUUID().toString()
        val arr = cuentas()
        arr.put(JSONObject().put("id", id))
        guardarCuentas(arr)
        return id
    }

    private companion object {
        const val KEY_CUENTAS = "cuentas"
        const val KEY_CUENTA_ACTIVA = "cuenta_activa_id"
        const val KEY_FIX_INGRESO_MANUAL = "fix_ingreso_manual_default_aplicado"
    }
}
