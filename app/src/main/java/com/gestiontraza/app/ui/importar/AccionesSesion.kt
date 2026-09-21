package com.gestiontraza.app.ui.importar

import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.gestiontraza.app.data.SesionGuardada
import com.gestiontraza.app.data.SessionFileStore
import java.io.File

/**
 * Comparte la sesión como archivo .txt con el nombre de la sesión: el sistema ofrece
 * WhatsApp, correo, Drive, Bluetooth y todo lo que tenga instalado el teléfono.
 */
fun Fragment.compartirSesion(sesion: SesionGuardada, store: SessionFileStore) {
    val ctx = requireContext()
    try {
        val dir = File(ctx.cacheDir, "compartir").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val nombreArchivo = sesion.nombre.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "sesion" }
        val copia = File(dir, "$nombreArchivo.txt")
        copia.writeText(store.leer(sesion.archivo).joinToString("\n"))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", copia)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, sesion.nombre)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir sesión"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Pide un nombre nuevo (con el actual escrito) y renombra la sesión. */
fun Fragment.renombrarSesion(sesion: SesionGuardada, store: SessionFileStore, onListo: (SesionGuardada) -> Unit) {
    val et = EditText(requireContext()).apply {
        setText(sesion.nombre)
        setSelection(text.length)
        hint = "Nombre de la sesión"
        setPadding(48, 32, 48, 16)
        filters = arrayOf(android.text.InputFilter.LengthFilter(40))
    }
    AlertDialog.Builder(requireContext())
        .setTitle("Cambiar nombre")
        .setView(et)
        .setPositiveButton("Guardar") { _, _ ->
            val nuevo = et.text?.toString()?.trim().orEmpty()
            val resultado = if (nuevo.isEmpty()) null else store.renombrar(sesion, nuevo)
            if (resultado == null) {
                Toast.makeText(requireContext(), "No se pudo cambiar el nombre (vacío o ya existe otra sesión con ese nombre)", Toast.LENGTH_LONG).show()
            } else {
                onListo(resultado)
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
