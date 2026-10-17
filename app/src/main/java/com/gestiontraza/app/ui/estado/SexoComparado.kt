package com.gestiontraza.app.ui.estado

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.TextView
import com.gestiontraza.app.R
import com.gestiontraza.app.data.CaravanaSexo
import com.gestiontraza.app.data.CaravanaSexo.Coincidencia

/** Color con el que se muestra cada resultado de la comparación de sexo. */
private fun colorDe(c: Coincidencia) = when (c) {
    Coincidencia.OK -> R.color.verde_ok
    Coincidencia.NO_COINCIDE -> R.color.rojo_error
    Coincidencia.SIN_SEXO -> R.color.amarillo
}

/**
 * Muestra el sexo de SIGSA y, si se registró sexo al leer la caravana, al lado una leyenda:
 * OK si coincide, "No coincide" si es distinto o "Sin sexo" si SIGSA no tiene el dato.
 * Sin sexo leído (o sin datos de SIGSA para la caravana) queda solo el sexo de SIGSA.
 */
fun TextView.mostrarSexoComparado(sexoSigsa: String, leido: String?, hayDatos: Boolean = true) {
    val base = if (hayDatos) sexoSigsa.ifBlank { "-" } else "-"
    val c = if (hayDatos) CaravanaSexo.comparar(leido, sexoSigsa) else null
    if (c == null) {
        text = base
        return
    }
    val etiqueta = CaravanaSexo.etiqueta(c)
    val sp = SpannableString("$base  $etiqueta")
    val ini = base.length + 2
    sp.setSpan(ForegroundColorSpan(context.getColor(colorDe(c))), ini, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sp.setSpan(StyleSpan(Typeface.BOLD), ini, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text = sp
}

/**
 * Resumen de la comparación para el panel de contadores, p. ej. "Sexo leído:  OK 8 · No coincide 2".
 * Devuelve null si ninguna caravana tiene sexo leído (no hay nada que resumir).
 */
fun resumenComparacionSexo(ctx: android.content.Context, comparaciones: List<Coincidencia?>): CharSequence? {
    val hechas = comparaciones.filterNotNull()
    if (hechas.isEmpty()) return null
    val sb = SpannableStringBuilder("Sexo leído:  ")
    fun parte(c: Coincidencia) {
        val n = hechas.count { it == c }
        if (n == 0 && c != Coincidencia.NO_COINCIDE) return
        if (sb.isNotEmpty() && !sb.endsWith("  ")) sb.append(" · ")
        val ini = sb.length
        sb.append("${CaravanaSexo.etiqueta(c)} $n")
        sb.setSpan(ForegroundColorSpan(ctx.getColor(colorDe(c))), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (c == Coincidencia.NO_COINCIDE) sb.setSpan(StyleSpan(Typeface.BOLD), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    parte(Coincidencia.OK)
    parte(Coincidencia.NO_COINCIDE)
    parte(Coincidencia.SIN_SEXO)
    return sb
}

/**
 * Línea extra de una fila de resultados ("Sexo: M  OK"): se muestra solo si se registró sexo al
 * leer la caravana y SIGSA devolvió datos; si no, queda oculta.
 */
fun TextView.mostrarFilaSexo(sexoSigsa: String, leido: String?, hayDatos: Boolean) {
    if (!hayDatos || CaravanaSexo.comparar(leido, sexoSigsa) == null) {
        visibility = android.view.View.GONE
        return
    }
    visibility = android.view.View.VISIBLE
    mostrarSexoComparado(sexoSigsa, leido, hayDatos)
    text = android.text.SpannableStringBuilder("Sexo SIGSA: ").append(text)
}

/** Agrega una línea al final del texto del TextView (si hay algo que agregar). */
fun TextView.agregarLinea(extra: CharSequence?) {
    if (extra != null) text = android.text.TextUtils.concat(text, "\n", extra)
}

/** Razón de una fila compartida (.csv) con el resultado de la comparación de sexo, si lo hay. */
fun razonConSexo(razon: String, leido: String?, sexoSigsa: String, hayDatos: Boolean): String {
    val comp = textoComparacionSexo(leido, sexoSigsa, hayDatos)
    return listOf(razon, if (comp.isEmpty()) "" else "Sexo: $comp").filter { it.isNotBlank() }.joinToString(" · ")
}

/** Un código por línea, con la leyenda de comparación de sexo al lado cuando la hay. */
fun listadoConSexo(ctx: android.content.Context, items: List<Pair<String, Coincidencia?>>): CharSequence {
    val sb = SpannableStringBuilder()
    items.forEachIndexed { i, (codigo, c) ->
        if (i > 0) sb.append("\n")
        sb.append(codigo)
        if (c != null) {
            sb.append("   ")
            val ini = sb.length
            sb.append(CaravanaSexo.etiqueta(c))
            sb.setSpan(ForegroundColorSpan(ctx.getColor(colorDe(c))), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return sb
}

/** Para ordenar: las que no coinciden suben, justo después de las que no tienen datos. */
fun Coincidencia?.sube(): Boolean = this == Coincidencia.NO_COINCIDE

/** Texto para el .xlsx/.csv compartido: "OK", "No coincide", "Sin sexo" o vacío si no hay sexo leído. */
fun textoComparacionSexo(leido: String?, sexoSigsa: String, hayDatos: Boolean = true): String =
    if (hayDatos) CaravanaSexo.etiqueta(CaravanaSexo.comparar(leido, sexoSigsa)) else ""
