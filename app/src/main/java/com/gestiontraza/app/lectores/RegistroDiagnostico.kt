package com.gestiontraza.app.lectores

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que se habló con el lector (pedidos y respuestas), para poder
 * mandarlo y revisar qué pasó cuando un modelo no se pudo probar en persona.
 * Solo se guarda en memoria hasta que el usuario elige compartirlo.
 */
class RegistroDiagnostico {
    private val sb = StringBuilder()
    private val reloj = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val hayDatos: Boolean get() = sb.isNotEmpty()

    @Synchronized
    fun agregar(linea: String) {
        if (sb.length > MAX) return
        sb.append(reloj.format(Date())).append("  ").append(linea).append('\n')
    }

    @Synchronized
    fun limpiar() = sb.setLength(0)

    @Synchronized
    fun texto(): String = sb.toString()

    fun compartir(context: Context, encabezado: String) {
        val dir = File(context.cacheDir, "diagnostico").apply { mkdirs() }
        val archivo = File(dir, "diagnostico_lector.txt")
        val cabecera = "GestionTraza - registro de diagnostico del lector\n" +
            "Telefono: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n" +
            "$encabezado\n\n"
        archivo.writeText(cabecera + texto(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Registro de diagnóstico del lector")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir registro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val MAX = 3_000_000
    }
}
