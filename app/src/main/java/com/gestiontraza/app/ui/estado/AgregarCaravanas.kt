package com.gestiontraza.app.ui.estado

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.FormatoCaravana

class ResultadoParseoCaravanas(val nuevas: List<String>, val invalidas: List<String>, val repetidas: Int)

/** Separa un texto en caravanas (por espacios, saltos de línea, comas o ';'), descartando
 *  las de formato inválido y las que ya estaban en [existentes] o repetidas en el mismo texto. */
fun parsearCaravanasNuevas(texto: String, existentes: Collection<String>): ResultadoParseoCaravanas {
    val vistas = existentes.toMutableSet()
    val nuevas = mutableListOf<String>()
    val invalidas = mutableListOf<String>()
    var repetidas = 0
    texto.split(Regex("[\\s,;]+")).forEach { crudo ->
        val token = crudo.replace("#", "").trim()
        if (token.isEmpty()) return@forEach
        val partes = if (token.length > 15 && token.length % 15 == 0 && token.all { it.isDigit() })
            token.chunked(15) else listOf(token)
        partes.forEach { p ->
            val cod = FormatoCaravana.normalizarImportada(p)
            when {
                !FormatoCaravana.esValida(cod) -> invalidas.add(p)
                !vistas.add(cod) -> repetidas++
                else -> nuevas.add(cod)
            }
        }
    }
    return ResultadoParseoCaravanas(nuevas, invalidas, repetidas)
}

/**
 * Diálogo para sumar caravanas a verificar: se escriben/pegan a mano, o llegan
 * solas del lector (Bluetooth SPP, o teclado HID sobre el mismo campo).
 */
fun Fragment.mostrarDialogoAgregarCaravanas(
    existentes: () -> Collection<String>,
    onAgregar: (List<String>) -> Unit
) {
    val btVm: BtConnectionViewModel by activityViewModels()
    val et = EditText(requireContext()).apply {
        hint = "Escribí, pegá o leé con el lector"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 3
        maxLines = 8
        setPadding(48, 24, 48, 24)
        requestFocus()
    }
    val observer = Observer<Unit> {
        val lineas = btVm.drenarLineas()
        if (lineas.isNotEmpty()) {
            val previo = et.text.toString()
            val sep = if (previo.isEmpty() || previo.endsWith("\n")) "" else "\n"
            et.setText(previo + sep + lineas.joinToString("\n") + "\n")
            et.setSelection(et.text.length)
        }
    }
    btVm.drenarLineas()
    btVm.hayLineas.observe(viewLifecycleOwner, observer)

    val dialogo = AlertDialog.Builder(requireContext())
        .setTitle("Agregar caravanas")
        .setMessage(
            if (btVm.isConnected()) "Lector conectado: las lecturas aparecen acá solas."
            else "Podés escribir o pegar una o varias, una por línea."
        )
        .setView(et)
        .setPositiveButton("Agregar y verificar", null)
        .setNegativeButton("Cancelar", null)
        .create()
    dialogo.setOnDismissListener { btVm.hayLineas.removeObserver(observer) }
    dialogo.setOnShowListener {
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r = parsearCaravanasNuevas(et.text.toString(), existentes())
            val avisos = buildList {
                if (r.invalidas.isNotEmpty())
                    add("${r.invalidas.size} con formato inválido (deben ser 15 dígitos y empezar con 0320)")
                if (r.repetidas > 0) add("${r.repetidas} ya estaba(n) en la lista")
            }
            if (r.nuevas.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    if (avisos.isEmpty()) "No ingresaste ninguna caravana" else "No se agregó ninguna: " + avisos.joinToString(", "),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            dialogo.dismiss()
            if (avisos.isNotEmpty())
                Toast.makeText(requireContext(), "Se ignoraron: " + avisos.joinToString(", "), Toast.LENGTH_LONG).show()
            onAgregar(r.nuevas)
        }
    }
    dialogo.show()
}
