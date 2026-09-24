package com.gestiontraza.app.ui.estado

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/** Máscara 00.000.0.00000/00: se tipean solo los 13 dígitos y los separadores se ponen solos. */
fun EditText.aplicarMascaraRenspa() {
    addTextChangedListener(object : TextWatcher {
        private var editando = false
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (editando || s == null) return
            editando = true
            val digitos = s.filter { it.isDigit() }.take(13)
            val sb = StringBuilder()
            digitos.forEachIndexed { i, ch ->
                when (i) { 2, 5 -> sb.append('.'); 6 -> sb.append('.'); 11 -> sb.append('/') }
                sb.append(ch)
            }
            val nuevo = sb.toString()
            if (nuevo != s.toString()) {
                s.replace(0, s.length, nuevo)
            }
            editando = false
        }
    })
}
