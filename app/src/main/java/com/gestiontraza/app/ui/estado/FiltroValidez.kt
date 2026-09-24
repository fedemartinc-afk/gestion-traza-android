package com.gestiontraza.app.ui.estado

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** Selector de filtro válidas/inválidas: null = todas, true = solo válidas, false = solo inválidas. */
fun Fragment.mostrarSelectorValidez(
    actual: Boolean?,
    validas: Int,
    invalidas: Int,
    onElegir: (Boolean?) -> Unit
) {
    val opciones = arrayOf(
        "Todas (${validas + invalidas})",
        "Solo válidas ($validas)",
        "Solo inválidas / sin datos ($invalidas)"
    )
    val valores = arrayOf<Boolean?>(null, true, false)
    AlertDialog.Builder(requireContext())
        .setTitle("Filtrar por validez")
        .setSingleChoiceItems(opciones, valores.indexOf(actual)) { d, which ->
            onElegir(valores[which])
            d.dismiss()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

fun textoBotonFiltroValidez(filtro: Boolean?) = when (filtro) {
    null -> "🔎  Filtrar válidas / inválidas"
    true -> "🔎  Filtro: solo válidas ▾"
    false -> "🔎  Filtro: solo inválidas ▾"
}
