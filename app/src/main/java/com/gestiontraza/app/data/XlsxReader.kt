package com.gestiontraza.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lector mínimo de archivos .xlsx (Office Open XML = ZIP + XML), sin
 * depender de Apache POI — es muy pesado para Android y usa APIs
 * (java.awt, javax.xml.stream) que no están disponibles ahí.
 *
 * Solo extrae el texto de todas las celdas no vacías de la primera hoja,
 * en el orden en que aparecen (fila por fila, columna por columna), sin
 * importar en qué columna esté la caravana — alcanza para reusar el mismo
 * reconocedor de códigos (ReadingViewModel.addTexto) que ya se usa para
 * archivos de texto y pegado manual.
 */
object XlsxReader {

    fun leerCeldas(input: InputStream): List<String> {
        val entradas = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    zip.copyTo(out)
                    entradas[entry.name] = out.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val sharedStrings = entradas["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        val hojaNombre = entradas.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .minByOrNull { it }
            ?: return emptyList()
        val hoja = entradas[hojaNombre] ?: return emptyList()

        return parseCeldas(hoja, sharedStrings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val lista = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        var actual = StringBuilder()
        var dentroDeT = false
        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            when (evento) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> actual = StringBuilder()
                    "t"  -> dentroDeT = true
                }
                XmlPullParser.TEXT -> if (dentroDeT) actual.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t"  -> dentroDeT = false
                    "si" -> lista.add(actual.toString())
                }
            }
            evento = parser.next()
        }
        return lista
    }

    private fun parseCeldas(bytes: ByteArray, sharedStrings: List<String>): List<String> {
        val celdas = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        var tipoCelda: String? = null
        var dentroDeV = false
        var dentroDeInlineT = false
        var valorCelda = StringBuilder()
        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            when (evento) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "c" -> { tipoCelda = parser.getAttributeValue(null, "t"); valorCelda = StringBuilder() }
                    "v" -> dentroDeV = true
                    "t" -> if (tipoCelda == "inlineStr") dentroDeInlineT = true
                }
                XmlPullParser.TEXT -> if (dentroDeV || dentroDeInlineT) valorCelda.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> dentroDeV = false
                    "t" -> dentroDeInlineT = false
                    "c" -> {
                        val raw = valorCelda.toString()
                        val texto = if (tipoCelda == "s") {
                            raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) }
                        } else raw
                        if (!texto.isNullOrBlank()) celdas.add(texto.trim())
                        tipoCelda = null
                    }
                }
            }
            evento = parser.next()
        }
        return celdas
    }
}
