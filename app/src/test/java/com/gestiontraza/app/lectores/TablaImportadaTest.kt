package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.CaravanaSexo
import com.gestiontraza.app.data.FormatoCaravana
import com.gestiontraza.app.data.TablaImportada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TablaImportadaTest {

    @Test fun csvConComasYColumnaSex() {
        val l = TablaImportada.aLineas(listOf(
            "VID,EID,Date,Time,Draft,Notes,Live Weight (Kg),Average Daily Gain (Kg/d),Overall Daily Gain (Kg/d),Sex,Status",
            "MYT5.356,032 010033168453,2026-09-25,11:50:13,,,,,,HEMBRA,",
            "MYT5,032 010033168475,2026-09-24,09:54:13,,,,,,MACHO,",
            "MYT5.7,032 010033168476,2026-09-24,10:06:37,,,,,,,"
        ))!!
        assertEquals(listOf("032 010033168453,H", "032 010033168475,M", "032 010033168476"), l)
    }

    @Test fun csvConPuntoYComaYSexEnOtraPosicion() {
        val l = TablaImportada.aLineas(listOf(
            "VID;;EID;Date;Sex;PESO",
            "MYT3;44;032 010033702917;1/10/2026;HEMBRA;",
            "AGT11SD;8;982 000458494016;1/10/2026;Male;",
            "AGT19SD;142;032 010023483328;1/10/2026;Female;",
            ";;032 010032185311;1/10/2026;;",          // sin VID ni sexo: igual se importa la caravana
            ";;;24/9/2026;MACHO;"                      // sin caravana: se descarta
        ))!!
        assertEquals(
            listOf("032 010033702917,H", "982 000458494016,M", "032 010023483328,H", "032 010032185311"), l
        )
    }

    @Test fun csvSinColumnaSexSoloTraeLosCodigos() {
        val l = TablaImportada.aLineas(listOf(
            "VID;EID;Date;Time;Draft;Notes;Live Weight (Kg);Status",
            "MYT5.356;032 010033168453;25/9/2026;11:54:03;;Nuevo Arete:MYT5.356;196.50;",
            "MYT5.28;;24/9/2026;10:52:30;;;109.00;"
        ))!!
        assertEquals(listOf("032 010033168453"), l)
    }

    @Test fun sinEncabezadoNoEsTabla() {
        // Formato viejo del XRS2: el código va primero y no hay encabezado
        assertNull(TablaImportada.aLineas(listOf("032 010030479031,,2026-09-23,17:08:19,,,", "032 010030479032,,2026-09-23,17:09:00,,,")))
        assertNull(TablaImportada.aLineas(emptyList()))
    }

    @Test fun sexoDeVariantes() {
        assertEquals("M", TablaImportada.sexoDe("MACHO"))
        assertEquals("M", TablaImportada.sexoDe("Male"))
        assertEquals("M", TablaImportada.sexoDe("m"))
        assertEquals("M", TablaImportada.sexoDe("Macho entero"))
        assertEquals("H", TablaImportada.sexoDe("HEMBRA"))
        assertEquals("H", TablaImportada.sexoDe("Female"))
        assertEquals("H", TablaImportada.sexoDe("F"))
        assertEquals("", TablaImportada.sexoDe(""))
        assertEquals("", TablaImportada.sexoDe("otro"))
    }

    @Test fun celdasEntreComillas() {
        val l = TablaImportada.aLineas(listOf(
            "\"VID\",\"EID\",\"Sexo\"",
            "\"A,1\",\"032 010033168453\",\"Hembra\""
        ))!!
        assertEquals(listOf("032 010033168453,H"), l)
    }

    @Test fun lasLineasSeNormalizanAlGuardar() {
        // Al guardar la sesión se corrigen espacios del código y se conserva el sexo
        assertEquals("032010033168453,H", FormatoCaravana.normalizarImportada("032 010033168453,H"))
    }

    // ---- Archivos reales de ejemplo (solo corre si están en Descargas) ----

    private fun real(nombre: String): List<String>? {
        val f = File(System.getProperty("user.home"), "Downloads/$nombre")
        return if (f.exists()) f.readLines() else null
    }

    @Test fun archivosRealesDeEjemplo() {
        val esperados = mapOf(
            "Santo Domingo - Ensenada 2 (1).csv" to true,
            "Santo Domingo - Ensenada 1 (1).csv" to true,
            "TRABAJO 24_09 (1).csv" to true,
            "Trabajo 24_09 MYT5 (2).csv" to false   // este lector no registró la columna Sex
        )
        var alguno = false
        for ((nombre, conSexo) in esperados) {
            val crudas = real(nombre) ?: continue
            alguno = true
            val l = TablaImportada.aLineas(crudas)
            assertNotNull("No se reconoció la tabla de $nombre", l)
            val codigos = CaravanaSexo.codigos(l!!)
            val conSexoN = l.count { CaravanaSexo.sexo(it).isNotEmpty() }
            println("$nombre -> ${l.size} caravanas, con sexo: $conSexoN (M=${l.count { CaravanaSexo.sexo(it) == "M" }}, H=${l.count { CaravanaSexo.sexo(it) == "H" }})")
            assertEquals("Caravanas con espacios sueltos en $nombre", 0, codigos.count { it.isBlank() })
            if (conSexo) assertEquals(true, conSexoN > 0) else assertEquals(0, conSexoN)
        }
        assumeTrue("No están los archivos de ejemplo en Descargas", alguno)
    }
}
