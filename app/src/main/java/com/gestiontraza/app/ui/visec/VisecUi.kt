package com.gestiontraza.app.ui.visec

import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.CaravanaSexo
import com.gestiontraza.app.data.FormatoCaravana
import com.gestiontraza.app.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Si el módulo Verificar en VISEC está habilitado para el usuario, según la última respuesta de la web (null = todavía no se sabe). */
object VisecEstado {
    @Volatile var habilitado: Boolean? = null
    @Volatile var maxNombre: Int = 70
}

/** Le pregunta a la web si el usuario tiene el módulo Verificar en VISEC. false también si no hay conexión. */
suspend fun consultarVisecHabilitado(session: SessionManager): Boolean {
    val base = session.baseUrl()
    val token = session.token
    val r = withContext(Dispatchers.IO) { ApiClient.estadoVisec(base, token) }
    if (!r.ok) return VisecEstado.habilitado ?: false
    VisecEstado.habilitado = r.data?.optBoolean("habilitado", false) ?: false
    VisecEstado.maxNombre = r.data?.optInt("max_nombre", 70) ?: 70
    return VisecEstado.habilitado == true
}

/** Muestra [vista] solo si el usuario tiene habilitado el módulo Verificar en VISEC. Mientras no se sabe, queda oculta. */
fun Fragment.mostrarSiVisecHabilitado(session: SessionManager, vista: View) {
    vista.visibility = View.GONE
    viewLifecycleOwner.lifecycleScope.launch {
        val habilitado = consultarVisecHabilitado(session)
        if (isAdded && habilitado) vista.visibility = View.VISIBLE
    }
}

/**
 * Pide un nombre y deja en cola de la web una verificación en VISEC con esas caravanas. La web la ejecuta en segundo plano:
 * conecta a VISEC si hace falta, pone el nombre (y el DTe, si lo hay) en la Descripción, carga las caravanas y verifica.
 * [alTerminar] recibe true si la web la recibió; false si se canceló o falló.
 */
fun Fragment.enviarAVisec(
    session: SessionManager,
    caravanas: List<String>,
    dte: String,
    nombreSugerido: String,
    alTerminar: (Boolean) -> Unit = {}
) {
    val ctx = requireContext()
    val validas = caravanas.map { CaravanaSexo.codigo(it) }.filter { FormatoCaravana.esValida(it) }.distinct()
    if (validas.isEmpty()) {
        Toast.makeText(ctx, "No hay caravanas con formato válido para verificar en VISEC", Toast.LENGTH_LONG).show()
        alTerminar(false)
        return
    }
    val dp = ctx.resources.displayMetrics.density
    val max = VisecEstado.maxNombre
    val etNombre = EditText(ctx).apply {
        hint = "Nombre del envío"
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(max))
        setText(nombreSugerido.take(max))
        setSelection(text.length)
    }
    val ayuda = TextView(ctx).apply {
        textSize = 12f
        text = "Ese nombre va a la Descripción del informe en VISEC; podés cambiarlo antes de enviar. " +
            "Se envían ${validas.size} caravana${if (validas.size != 1) "s" else ""}."
        setPadding(0, (8 * dp).toInt(), 0, 0)
    }
    val contenido = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((22 * dp).toInt(), (8 * dp).toInt(), (22 * dp).toInt(), 0)
        addView(etNombre); addView(ayuda)
    }
    val dialogo = AlertDialog.Builder(ctx).setTitle("Verificar en VISEC").setView(contenido)
        .setPositiveButton("Enviar", null)
        .setNegativeButton("Cancelar") { _, _ -> alTerminar(false) }
        .setCancelable(false).create()
    dialogo.setOnShowListener {
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { boton ->
            val nombre = etNombre.text.toString().trim()
            if (nombre.isEmpty()) { etNombre.error = "Escribí un nombre"; return@setOnClickListener }
            boton.isEnabled = false
            val base = session.baseUrl()
            val token = session.token
            viewLifecycleOwner.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { ApiClient.solicitarVisec(base, token, nombre, dte, validas) }
                if (!isAdded) { alTerminar(false); return@launch }
                dialogo.dismiss()
                // Queda anotada para avisar cuando el informe termine (necesita permiso de notificaciones en Android 13 o más)
                if (r.ok) {
                    VisecAviso.registrar(requireContext().applicationContext, r.data?.optLong("id") ?: 0L)
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !VisecAviso.puedeNotificar(requireContext())) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7301)
                    }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(if (r.ok) "Enviado a VISEC" else "No se pudo enviar a VISEC")
                    .setMessage(r.message.ifBlank { if (r.ok) "La solicitud quedó en cola." else "Error desconocido." } +
                        if (r.ok) "\n\nCuando el informe termine te vamos a avisar con una notificación. Lo ves en Inicio, en Informes VISEC." else "")
                    .setCancelable(false)
                    .setPositiveButton("Aceptar") { _, _ -> alTerminar(r.ok) }
                    .show()
            }
        }
    }
    dialogo.show()
}
