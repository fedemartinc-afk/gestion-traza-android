package com.gestiontraza.app.data

/**
 * Lee los archivos de texto/CSV que vuelcan los lectores de caravanas (Tru-Test, etc.): tablas con
 * encabezado, separadas por coma, punto y coma o tabulación, donde el número de caravana está en la
 * columna "EID" y, si el lector lo registra, el sexo en una columna "Sex"/"Sexo".
 *
 * Cada fila se convierte en una línea de sesión: "código" o "código,M" / "código,H" (ver [CaravanaSexo]).
 */
object TablaImportada {

    private val NOMBRES_CARAVANA = setOf(
        "eid", "caravana", "caravana electronica", "caravana electrónica", "id electronico", "id electrónico",
        "electronic id", "rfid", "tag", "chip", "microchip"
    )
    private val NOMBRES_SEXO = setOf("sex", "sexo", "gender", "genero", "género")

    /**
     * Devuelve las líneas de sesión de la tabla, o null si la primera línea no es un encabezado con
     * una columna de caravana (en ese caso el archivo se trata como lista simple de códigos).
     */
    fun aLineas(lineasArchivo: List<String>): List<String>? {
        val lineas = lineasArchivo.map { it.removePrefix("﻿") }.filter { it.isNotBlank() }
        val encabezado = lineas.firstOrNull() ?: return null

        // Separador: el que más veces aparece en el encabezado y deja una columna de caravana.
        val candidatos = listOf(';', ',', '\t').sortedByDescending { d -> encabezado.count { it == d } }
        for (sep in candidatos) {
            if (encabezado.none { it == sep }) continue
            val nombres = dividir(encabezado, sep).map { it.trim().lowercase() }
            val iCaravana = nombres.indexOfFirst { it in NOMBRES_CARAVANA }
            if (iCaravana < 0) continue
            val iSexo = nombres.indexOfFirst { it in NOMBRES_SEXO }

            return lineas.drop(1).mapNotNull { linea ->
                val celdas = dividir(linea, sep)
                val codigo = celdas.getOrNull(iCaravana)?.trim().orEmpty()
                if (codigo.isEmpty()) null
                else CaravanaSexo.linea(codigo, if (iSexo >= 0) sexoDe(celdas.getOrNull(iSexo).orEmpty()) else "")
            }
        }
        return null
    }

    /** "MACHO"/"Male"/"M" → "M"; "HEMBRA"/"Female"/"H"/"F" → "H"; cualquier otra cosa o vacío → "". */
    fun sexoDe(valor: String): String {
        val v = valor.trim().uppercase()
        return when {
            v.isEmpty() -> ""
            v == "M" || v.startsWith("MACHO") || v.startsWith("MALE") -> "M"
            v == "H" || v == "F" || v.startsWith("HEMBRA") || v.startsWith("FEMALE") -> "H"
            else -> ""
        }
    }

    /** Parte una línea por el separador respetando los campos entre comillas. */
    internal fun dividir(linea: String, sep: Char): List<String> {
        val celdas = mutableListOf<String>()
        val actual = StringBuilder()
        var entreComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                c == '"' && entreComillas && linea.getOrNull(i + 1) == '"' -> { actual.append('"'); i++ }
                c == '"' -> entreComillas = !entreComillas
                c == sep && !entreComillas -> { celdas.add(actual.toString()); actual.clear() }
                else -> actual.append(c)
            }
            i++
        }
        celdas.add(actual.toString())
        return celdas
    }
}
