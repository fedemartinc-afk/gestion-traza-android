package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.CaravanaSexo
import com.gestiontraza.app.data.CaravanaSexo.Coincidencia
import com.gestiontraza.app.data.FormatoCaravana
import com.gestiontraza.app.ui.reading.LecturasLista
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SexoCaravanaTest {

    private val a = "032010010451307"
    private val b = "032010010451308"

    // ---- formato de línea "código,sexo"

    @Test fun lineaSeArmaYSeSepara() {
        assertEquals("$a,M", CaravanaSexo.linea(a, "M"))
        assertEquals("$a,H", CaravanaSexo.linea(a, "hembra"))
        assertEquals(a, CaravanaSexo.linea(a, ""))
        assertEquals(a, CaravanaSexo.codigo("$a,M"))
        assertEquals("M", CaravanaSexo.sexo("$a,M"))
        assertEquals("", CaravanaSexo.sexo(a))
        assertEquals("", CaravanaSexo.sexo("$a,X"))
    }

    @Test fun mapaYCodigosDeUnaLista() {
        val lineas = listOf("$a,M", b, "032010010451309,H")
        assertEquals(listOf(a, b, "032010010451309"), CaravanaSexo.codigos(lineas))
        assertEquals(mapOf(a to "M", "032010010451309" to "H"), CaravanaSexo.mapa(lineas))
    }

    @Test fun normalizarImportadaConservaElSexo() {
        assertEquals("$a,M", FormatoCaravana.normalizarImportada("032 010010451307,M"))
        // 14 dígitos sin el 0 inicial: se corrige el código y queda el sexo
        assertEquals("$a,H", FormatoCaravana.normalizarImportada("32010010451307,H"))
        assertEquals(a, FormatoCaravana.normalizarImportada(" 032010010451307 "))
    }

    // ---- comparación con SIGSA

    @Test fun compararConSigsa() {
        assertEquals(Coincidencia.OK, CaravanaSexo.comparar("M", "MACHO"))
        assertEquals(Coincidencia.OK, CaravanaSexo.comparar("H", "H"))
        assertEquals(Coincidencia.NO_COINCIDE, CaravanaSexo.comparar("M", "HEMBRA"))
        assertEquals(Coincidencia.SIN_SEXO, CaravanaSexo.comparar("M", ""))
        assertEquals(Coincidencia.SIN_SEXO, CaravanaSexo.comparar("H", "sin dato"))
        // sin sexo leído no hay nada que comparar
        assertNull(CaravanaSexo.comparar(null, "M"))
        assertNull(CaravanaSexo.comparar("", "M"))
    }

    @Test fun etiquetas() {
        assertEquals("OK", CaravanaSexo.etiqueta(Coincidencia.OK))
        assertEquals("No coincide", CaravanaSexo.etiqueta(Coincidencia.NO_COINCIDE))
        assertEquals("Sin sexo", CaravanaSexo.etiqueta(Coincidencia.SIN_SEXO))
        assertEquals("", CaravanaSexo.etiqueta(null))
    }

    // ---- lista de lecturas

    @Test fun duplicadaMismoSexoNoEsConflicto() {
        val l = LecturasLista()
        l.agregar(a, "M"); l.agregar(a, "M")
        assertTrue(l.conflictos().isEmpty())
        val items = l.items()
        assertEquals(2, items.size)
        assertEquals(1, items.count { it.esDuplicado })
        assertEquals(1, l.validas().size)
    }

    @Test fun duplicadaConSexoDistintoMarcaAmbasLecturas() {
        val l = LecturasLista()
        l.agregar(a, "M"); l.agregar(b, "H"); l.agregar(a, "H")
        assertEquals(listOf(a), l.conflictos())
        val deA = l.items().filter { it.codigo == a }
        assertEquals(2, deA.size)
        assertTrue(deA.all { it.sexoConflicto })
        assertFalse(l.items().first { it.codigo == b }.sexoConflicto)
    }

    @Test fun eliminarUnaLecturaResuelveElConflicto() {
        val l = LecturasLista()
        l.agregar(a, "M"); l.agregar(a, "H")
        val segunda = l.items().first { it.esDuplicado }
        l.eliminar(segunda.id)
        assertTrue(l.conflictos().isEmpty())
        val resto = l.items()
        assertEquals(1, resto.size)
        assertFalse(resto[0].esDuplicado)
        assertEquals("M", resto[0].sexo)
    }

    @Test fun eliminarLaPrimeraDejaLaSegundaComoValida() {
        val l = LecturasLista()
        l.agregar(a, "M"); l.agregar(a, "H")
        val primera = l.items().first { !it.esDuplicado }
        l.eliminar(primera.id)
        val resto = l.items()
        assertEquals(1, resto.size)
        assertFalse(resto[0].esDuplicado)
        assertEquals("H", resto[0].sexo)
        assertTrue(l.conflictos().isEmpty())
    }

    @Test fun sinSexoRegistradoSeComportaComoAntes() {
        val l = LecturasLista()
        l.agregar(a); l.agregar(a); l.agregar(b)
        assertTrue(l.conflictos().isEmpty())
        assertEquals(2, l.validas().size)
        assertEquals(1, l.items().count { it.esDuplicado })
        assertTrue(l.items().all { it.sexo.isEmpty() })
    }

    @Test fun unaLecturaSinSexoYOtraConSexoNoEsConflicto() {
        val l = LecturasLista()
        l.agregar(a); l.agregar(a, "M")
        assertTrue(l.conflictos().isEmpty())
    }

    @Test fun masRecientePrimero() {
        val l = LecturasLista()
        l.agregar(a, "M"); l.agregar(b, "H")
        assertEquals(listOf(b, a), l.items().map { it.codigo })
    }
}
