package com.gestiontraza.app.ui.send

import android.content.Context
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Teclado numérico por defecto para el DTe (son números), con un botón para pasar a
 * QWERTY si hace falta cargar un código alfanumérico.
 */
fun configurarTecladoDte(et: TextInputEditText, btnToggle: MaterialButton) {
    var qwerty = false
    btnToggle.setOnClickListener {
        qwerty = !qwerty
        et.inputType = if (qwerty)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else
            InputType.TYPE_CLASS_NUMBER
        btnToggle.text = if (qwerty) "123" else "ABC"
        et.setSelection(et.text?.length ?: 0)
        et.post {
            et.requestFocus()
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

/** Del código de barras del DT-e se toman los primeros 9 dígitos (el resto es de control). */
fun dteDesdeCodigo(code: String): String {
    val soloDigitos = code.filter { it.isDigit() }.take(9)
    return if (soloDigitos.isNotEmpty()) soloDigitos else code
}
