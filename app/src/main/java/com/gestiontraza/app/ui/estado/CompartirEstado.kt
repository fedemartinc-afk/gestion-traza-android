package com.gestiontraza.app.ui.estado

import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.gestiontraza.app.R
import com.gestiontraza.app.data.XlsxWriter
import java.io.File

/** Una fila del resultado de verificar el estado de una caravana (TRI o Predespacho). */
data class FilaEstadoCaravana(val codigo: String, val ok: Boolean, val razon: String)

/** "Válidas: N   Inválidas: M", con cada número en su color — para el panel de
 *  contadores (debajo de Sexo) en Estado TRI, Predespacho y Verificar Origen. */
fun textoValidezColoreado(ctx: Context, validas: Int, invalidas: Int): CharSequence {
    val sb = SpannableStringBuilder()
    fun agregar(etiqueta: String, valor: Int, color: Int) {
        val inicio = sb.length
        sb.append("$etiqueta: $valor")
        sb.setSpan(ForegroundColorSpan(ctx.getColor(color)), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    agregar("Válidas", validas, R.color.verde_ok)
    sb.append("   ")
    agregar("Inválidas", invalidas, R.color.rojo_error)
    return sb
}

/**
 * Comparte un archivo .csv de dos columnas (Caravana, Estado) con el resultado de la
 * verificación. Si [soloValidas] viene fijado (al tocar directamente el contador de
 * válidas o inválidas en la barra superior) comparte directamente ese grupo; si no,
 * antes pregunta cuáles incluir: todas, solo válidas o solo inválidas.
 */
fun Fragment.compartirEstadoCaravanas(titulo: String, filas: List<FilaEstadoCaravana>, soloValidas: Boolean? = null) {
    if (filas.isEmpty()) {
        Toast.makeText(requireContext(), "No hay resultados para compartir", Toast.LENGTH_SHORT).show()
        return
    }
    if (soloValidas != null) {
        generarYCompartirCsv(titulo, filas.filter { it.ok == soloValidas })
        return
    }
    val validas = filas.count { it.ok }
    val invalidas = filas.size - validas
    AlertDialog.Builder(requireContext())
        .setTitle("¿Qué querés compartir?")
        .setItems(arrayOf("Todas (${filas.size})", "Solo válidas ($validas)", "Solo inválidas ($invalidas)")) { _, which ->
            val elegidas = when (which) {
                1 -> filas.filter { it.ok }
                2 -> filas.filter { !it.ok }
                else -> filas
            }
            generarYCompartirCsv(titulo, elegidas)
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun Fragment.generarYCompartirCsv(titulo: String, filas: List<FilaEstadoCaravana>) {
    if (filas.isEmpty()) {
        Toast.makeText(requireContext(), "No hay caravanas en ese grupo", Toast.LENGTH_SHORT).show()
        return
    }
    val datos = filas.map { f ->
        val estado = if (f.ok) "VALIDA" else "INVALIDA" + if (f.razon.isNotBlank()) ": ${f.razon}" else ""
        listOf(f.codigo, estado)
    }
    compartirXlsx(titulo, listOf("Caravana", "Estado"), datos)
}

/**
 * Comparte cualquier tabla como .xlsx real (no .csv): un .csv abierto en Excel o
 * Sheets interpreta "0320…" como un número y le borra el 0 inicial; acá cada celda
 * se guarda como texto explícito, así el 0 se conserva siempre. Sirve para
 * cualquier pantalla, no solo para el resultado de válidas/inválidas.
 */
fun Fragment.compartirXlsx(titulo: String, encabezados: List<String>, filas: List<List<String>>) {
    if (filas.isEmpty()) {
        Toast.makeText(requireContext(), "No hay datos para compartir", Toast.LENGTH_SHORT).show()
        return
    }
    val ctx = requireContext()
    try {
        val dir = File(ctx.cacheDir, "compartir").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val nombreArchivo = titulo.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().ifEmpty { "resultado" }
        val archivo = File(dir, "$nombreArchivo.xlsx")
        XlsxWriter.escribir(archivo, "Resultado", encabezados, filas)

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", archivo)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, titulo)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir resultado"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
