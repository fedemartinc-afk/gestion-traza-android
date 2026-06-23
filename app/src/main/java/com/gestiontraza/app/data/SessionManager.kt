package com.gestiontraza.app.data

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gestion_traza", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var sesionNombre: String
        get() = prefs.getString("sesion_nombre", "") ?: ""
        set(value) = prefs.edit().putString("sesion_nombre", value).apply()

    var usuarioNombre: String
        get() = prefs.getString("usuario_nombre", "") ?: ""
        set(value) = prefs.edit().putString("usuario_nombre", value).apply()

    var wsUsername: String
        get() = prefs.getString("ws_username", "") ?: ""
        set(value) = prefs.edit().putString("ws_username", value).apply()

    var wsToken: String
        get() = prefs.getString("ws_token", "") ?: ""
        set(value) = prefs.edit().putString("ws_token", value).apply()

    var isAdmin: Boolean
        get() = prefs.getBoolean("is_admin", false)
        set(value) = prefs.edit().putBoolean("is_admin", value).apply()

    // "replica" o "produccion" — solo modificable por admin
    var senasaEnv: String
        get() = prefs.getString("senasa_env", "replica") ?: "replica"
        set(value) = prefs.edit().putString("senasa_env", value).apply()

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && token.isNotBlank()

    fun clearSession() = prefs.edit().clear().apply()

    fun baseUrl(): String {
        val s = serverUrl.trim().trimEnd('/')
        return if (s.startsWith("http")) s else "https://$s"
    }
}
