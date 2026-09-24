package com.gestiontraza.app.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe un archivo .xlsx real y mínimo: un .zip armado a mano con las pocas
 * partes XML que exige el formato (Office Open XML), usando solo java.util.zip
 * — sin ninguna librería externa. Cada celda se guarda como texto explícito
 * (inlineStr), así Excel o Sheets nunca la reinterpretan como número y le borran
 * ceros iniciales (el motivo por el que antes se generaba un .xls/SpreadsheetML
 * en vez de un .csv común).
 */
object XlsxWriter {

    fun escribir(archivo: File, hoja: String, encabezados: List<String>, filas: List<List<String>>) {
        ZipOutputStream(archivo.outputStream()).use { zip ->
            fun parte(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            parte("[Content_Types].xml", CONTENT_TYPES)
            parte("_rels/.rels", RELS_RAIZ)
            parte("xl/workbook.xml", workbookXml(hoja))
            parte("xl/_rels/workbook.xml.rels", RELS_WORKBOOK)
            parte("xl/styles.xml", STYLES)
            parte("xl/worksheets/sheet1.xml", hojaXml(encabezados, filas))
        }
    }

    private fun celda(col: Int, fila: Int, texto: String, estilo: Int? = null): String {
        val ref = "${columna(col)}$fila"
        val s = if (estilo != null) " s=\"$estilo\"" else ""
        return "<c r=\"$ref\" t=\"inlineStr\"$s><is><t xml:space=\"preserve\">${esc(texto)}</t></is></c>"
    }

    /** Letra de columna de Excel a partir de un índice 0-based (0->A, 1->B, 26->AA...). */
    private fun columna(n: Int): String {
        var num = n
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, 'A' + num % 26)
            if (num < 26) break
            num = num / 26 - 1
        }
        return sb.toString()
    }

    private fun esc(v: String): String = v
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hojaXml(encabezados: List<String>, filas: List<List<String>>): String {
        val anchos = encabezados.indices.joinToString("") { i ->
            "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"24\" customWidth=\"1\"/>"
        }
        val filaEncabezado = "<row r=\"1\">" +
            encabezados.mapIndexed { i, h -> celda(i, 1, h, estilo = 1) }.joinToString("") +
            "</row>"
        val filasDatos = filas.mapIndexed { idx, fila ->
            val r = idx + 2
            "<row r=\"$r\">" + fila.mapIndexed { i, v -> celda(i, r, v) }.joinToString("") + "</row>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<cols>$anchos</cols>
<sheetData>$filaEncabezado$filasDatos</sheetData>
</worksheet>"""
    }

    private fun workbookXml(hoja: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${esc(hoja.take(31))}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val RELS_RAIZ = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val RELS_WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1E293B"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
</styleSheet>"""
}
