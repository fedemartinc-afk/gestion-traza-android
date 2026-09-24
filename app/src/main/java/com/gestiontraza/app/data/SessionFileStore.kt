package com.gestiontraza.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SesionGuardada(
    val archivo: File,
    val nombre: String,
    val origen: String,
    val fecha: Date,
    val lineas: Int
)

/**
 * Guarda cada sesión importada (por Bluetooth o por archivo) como un .txt
 * simple en el almacenamiento privado de la app. Sin base de datos: el
 * nombre del archivo codifica fecha/origen/nombre para poder listarlas sin
 * un índice aparte.
 */
class SessionFileStore(context: Context) {

    private val dir: File = File(context.filesDir, "sesiones_lector").apply { mkdirs() }

    fun guardar(nombre: String, origen: String, contenido: List<String>): File {
        // Se conservan letras con acento y eñes (\p{L}); el resto de los símbolos
        // se descarta porque el nombre va dentro del nombre del archivo.
        val nombreLimpio = nombre.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40)
            .ifEmpty { "sesion" }
        val origenLimpio = origen.replace("__", "_")
        val archivo = File(dir, "${System.currentTimeMillis()}__${origenLimpio}__$nombreLimpio.txt")
        // Corrige espacios sueltos y el "0" inicial perdido en cualquier sesión que se
        // guarde, sea cual sea el lector o el medio de importación (ver FormatoCaravana).
        archivo.writeText(contenido.map { FormatoCaravana.normalizarImportada(it) }.joinToString("\n"))
        return archivo
    }

    /**
     * Devuelve la sesión ya guardada que tiene exactamente las mismas caravanas
     * (sin importar el orden, ni el nombre que se le haya puesto), o null si no hay.
     * La más reciente primero.
     */
    fun buscarDuplicada(contenido: List<String>): SesionGuardada? {
        val objetivo = contenido.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (objetivo.isEmpty()) return null
        return listar().firstOrNull { s ->
            leer(s.archivo).map { it.trim() }.filter { it.isNotEmpty() }.toSet() == objetivo
        }
    }

    fun listar(): List<SesionGuardada> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.mapNotNull(::parseArchivo)
            ?.sortedByDescending { it.fecha }
            ?: emptyList()

    /** Reemplaza las caravanas de una sesión conservando su nombre, origen y fecha. */
    fun actualizar(archivo: File, contenido: List<String>) {
        archivo.writeText(contenido.map { FormatoCaravana.normalizarImportada(it) }.joinToString("\n"))
    }

    /**
     * Le pone otro nombre a la sesión (conserva fecha y origen). Devuelve la sesión con
     * el archivo nuevo, o null si no se pudo renombrar.
     */
    fun renombrar(sesion: SesionGuardada, nuevoNombre: String): SesionGuardada? {
        val limpio = nuevoNombre.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40)
        if (limpio.isEmpty()) return null
        val partes = sesion.archivo.nameWithoutExtension.split("__", limit = 3)
        val prefijo = "${partes.getOrNull(0) ?: sesion.fecha.time}__${partes.getOrNull(1) ?: sesion.origen}__"
        val destino = File(dir, "$prefijo$limpio.txt")
        if (destino == sesion.archivo) return sesion
        if (destino.exists() || !sesion.archivo.renameTo(destino)) return null
        return parseArchivo(destino)
    }

    fun eliminar(archivo: File) {
        archivo.delete()
    }

    fun porRuta(ruta: String): SesionGuardada? {
        val f = File(ruta)
        return if (f.exists()) parseArchivo(f) else null
    }

    fun leer(archivo: File): List<String> =
        if (archivo.exists()) archivo.readLines() else emptyList()

    private fun parseArchivo(f: File): SesionGuardada {
        val partes = f.nameWithoutExtension.split("__", limit = 3)
        val epoch = partes.getOrNull(0)?.toLongOrNull() ?: f.lastModified()
        val origen = partes.getOrNull(1) ?: "Desconocido"
        val nombre = partes.getOrNull(2)?.ifEmpty { null } ?: f.nameWithoutExtension
        val lineas = runCatching { f.readLines().size }.getOrDefault(0)
        return SesionGuardada(f, nombre, origen, Date(epoch), lineas)
    }

    companion object {
        /**
         * Identifica una caravana aunque venga escrita distinto ("032 010010451307",
         * "032010010451307"): con 10 dígitos o más se compara por los dígitos; si no,
         * por el texto en mayúsculas.
         */
        fun clave(linea: String): String {
            val digitos = linea.filter { it.isDigit() }
            return if (digitos.length >= 10) digitos else linea.trim().uppercase()
        }

        val FORMATO_FECHA = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR"))
    }
}
