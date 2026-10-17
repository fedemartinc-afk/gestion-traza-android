package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.UnirSesiones
import org.junit.Assert.assertEquals
import org.junit.Test

class UnirSesionesTest {

    private val a = "032010010451307"
    private val b = "032010010451308"
    private val c = "032010010451309"

    @Test fun unePreservandoElOrden() {
        val r = UnirSesiones.unir(listOf(listOf(a, b), listOf(c)))
        assertEquals(listOf(a, b, c), r.lineas)
        assertEquals(0, r.repetidas)
        assertEquals(0, r.sexosDistintos)
    }

    @Test fun quitaRepetidasEntreSesiones() {
        val r = UnirSesiones.unir(listOf(listOf(a, b), listOf(b, c)))
        assertEquals(listOf(a, b, c), r.lineas)
        assertEquals(1, r.repetidas)
    }

    @Test fun reconoceLaMismaCaravanaEscritaDistinto() {
        val r = UnirSesiones.unir(listOf(listOf("032 010010451307"), listOf(a)))
        assertEquals(1, r.lineas.size)
        assertEquals(1, r.repetidas)
    }

    @Test fun conservaElSexoSiLaRepetidaLoTrae() {
        val r = UnirSesiones.unir(listOf(listOf(a, b), listOf("$a,M")))
        assertEquals(listOf("$a,M", b), r.lineas)
        assertEquals(1, r.repetidas)
        assertEquals(0, r.sexosDistintos)
    }

    @Test fun sexosDistintosQuedaElPrimeroYSeCuenta() {
        val r = UnirSesiones.unir(listOf(listOf("$a,M"), listOf("$a,H")))
        assertEquals(listOf("$a,M"), r.lineas)
        assertEquals(1, r.sexosDistintos)
    }

    @Test fun mismoSexoNoEsConflicto() {
        val r = UnirSesiones.unir(listOf(listOf("$a,M"), listOf("$a,M")))
        assertEquals(listOf("$a,M"), r.lineas)
        assertEquals(0, r.sexosDistintos)
    }

    @Test fun ignoraLineasVacias() {
        val r = UnirSesiones.unir(listOf(listOf(a, "", "  "), emptyList()))
        assertEquals(listOf(a), r.lineas)
    }
}
