package com.gestiontraza.app.ui.send

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/**
 * Antes de enviar un cierre a un DTe: avisa que las caravanas no están (todas) verificadas
 * contra el origen y pregunta si se envían igual. [onSi] se llama solo si el usuario acepta.
 */
fun Fragment.confirmarEnvioSinVerificar(mensaje: String, onSi: () -> Unit) {
    AlertDialog.Builder(requireContext())
        .setTitle("Caravanas sin verificar")
        .setMessage("$mensaje\n\n¿Querés enviarlas igual, sin verificar?")
        .setPositiveButton("Sí, enviar igual") { _, _ -> onSi() }
        .setNegativeButton("No, volver", null)
        .show()
}
