package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.XlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class XlsxWriterTest {

    private fun parteComoTexto(zip: ZipFile, nombre: String): String {
        val entrada = zip.getEntry(nombre) ?: throw AssertionError("Falta la parte $nombre en el .xlsx")
        return zip.getInputStream(entrada).bufferedReader(Charsets.UTF_8).readText()
    }

    @Test fun generaUnXlsxValidoQueConservaElCeroInicial() {
        val archivo = File.createTempFile("resultado", ".xlsx")
        try {
            XlsxWriter.escribir(
                archivo,
                "Resultado",
                listOf("Caravana", "Estado"),
                listOf(
                    listOf("032010010451307", "VALIDA"),
                    listOf("032010010451308", "INVALIDA: Bloqueada, De baja"),
                    listOf("T515", "VALIDA") // texto con letras, no debería romper el XML
                )
            )

            ZipFile(archivo).use { zip ->
                // Las partes mínimas que exige el formato Office Open XML.
                for (parte in listOf(
                    "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"
                )) {
                    assertTrue("falta $parte", zip.getEntry(parte) != null)
                }

                val hoja = parteComoTexto(zip, "xl/worksheets/sheet1.xml")

                // Encabezados
                assertTrue(hoja.contains("<t xml:space=\"preserve\">Caravana</t>"))
                assertTrue(hoja.contains("<t xml:space=\"preserve\">Estado</t>"))

                // La caravana queda como texto explícito (inlineStr): ni Excel ni Sheets
                // pueden reinterpretarla como número y borrarle el 0 inicial.
                assertTrue(hoja.contains("t=\"inlineStr\"><is><t xml:space=\"preserve\">032010010451307</t></is>"))
                assertTrue(hoja.contains("032010010451308"))
                assertTrue(hoja.contains("INVALIDA: Bloqueada, De baja"))
                assertTrue(hoja.contains("T515"))

                // Las filas de datos arrancan en la 2 (la 1 es el encabezado).
                assertTrue(hoja.contains("<row r=\"2\">"))
                assertTrue(hoja.contains("<row r=\"4\">"))

                val contentTypes = parteComoTexto(zip, "[Content_Types].xml")
                assertTrue(contentTypes.contains("spreadsheetml.sheet.main"))
            }
        } finally {
            archivo.delete()
        }
    }

    @Test fun escapaCaracteresEspecialesEnElTexto() {
        val archivo = File.createTempFile("resultado", ".xlsx")
        try {
            XlsxWriter.escribir(
                archivo, "Resultado", listOf("Caravana", "Estado"),
                listOf(listOf("032010010451307", "INVALIDA: RENSPA no coincide (es 01.001 & \"otro\" <val>)"))
            )
            ZipFile(archivo).use { zip ->
                val hoja = parteComoTexto(zip, "xl/worksheets/sheet1.xml")
                assertTrue(hoja.contains("&amp;"))
                assertTrue(hoja.contains("&lt;val&gt;"))
                assertEquals(0, Regex("&(?!amp;|lt;|gt;)").findAll(hoja).count())
            }
        } finally {
            archivo.delete()
        }
    }
}
