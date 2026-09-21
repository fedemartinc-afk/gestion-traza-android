package com.gestiontraza.app.ui.importar

import android.text.InputFilter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.gestiontraza.app.data.SessionFileStore

/**
 * Último paso de cualquier importación de sesión: avisa si ya hay una guardada
 * con las mismas caravanas, deja elegir el nombre y recién ahí guarda.
 *
 * [nombreSugerido] llena el campo de nombre (ej. el que tiene la sesión en el
 * lector); vacío deja el campo en blanco y, si queda así, se usa uno automático.
 * [unidad] es cómo se cuentan los elementos en el título ("caravanas" o "líneas").
 * [onGuardada] recibe el nombre con el que se guardó.
 */
fun Fragment.guardarSesionImportada(
    store: SessionFileStore,
    nombreSugerido: String,
    origen: String,
    lineas: List<String>,
    unidad: String = "caravanas",
    onGuardada: (String) -> Unit
) {
    val duplicada = store.buscarDuplicada(lineas)
    if (duplicada == null) {
        pedirNombreYGuardar(store, nombreSugerido, origen, lineas, unidad, onGuardada)
        return
    }
    AlertDialog.Builder(requireContext())
        .setTitle("Sesión ya importada")
        .setMessage(
            "Estas mismas caravanas ya están guardadas en la sesión \"${duplicada.nombre}\" " +
                "(${SessionFileStore.FORMATO_FECHA.format(duplicada.fecha)}).\n\n" +
                "¿Querés guardarla igual como una sesión nueva?"
        )
        .setPositiveButton("Guardar igual") { _, _ ->
            pedirNombreYGuardar(store, nombreSugerido, origen, lineas, unidad, onGuardada)
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun Fragment.pedirNombreYGuardar(
    store: SessionFileStore,
    nombreSugerido: String,
    origen: String,
    lineas: List<String>,
    unidad: String,
    onGuardada: (String) -> Unit
) {
    val et = EditText(requireContext()).apply {
        hint = "Nombre de la sesión"
        setText(nombreSugerido)
        setSelectAllOnFocus(true)
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(40))
        setPadding(48, 32, 48, 16)
    }
    AlertDialog.Builder(requireContext())
        .setTitle("Guardar sesión (${lineas.size} $unidad)")
        .setView(et)
        .setPositiveButton("Guardar") { _, _ ->
            val nombre = et.text?.toString()?.trim().orEmpty()
                .ifEmpty { nombreSugerido.trim() }
                .ifEmpty { "sesion_${System.currentTimeMillis()}" }
            store.guardar(nombre, origen, lineas)
            onGuardada(nombre)
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
