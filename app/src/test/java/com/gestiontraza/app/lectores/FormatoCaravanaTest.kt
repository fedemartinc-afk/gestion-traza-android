package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.FormatoCaravana
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatoCaravanaTest {
    @Test fun aceptaQuince_digitos_con_0320() {
        assertTrue(FormatoCaravana.esValida("032010010451307"))
        assertTrue(FormatoCaravana.esValida("032 010010451307"))   // con espacio
    }

    @Test fun rechazaLoDemas() {
        assertFalse(FormatoCaravana.esValida("032110010451307"))   // no empieza con 0320
        assertFalse(FormatoCaravana.esValida("03201001045130"))    // 14 dígitos
        assertFalse(FormatoCaravana.esValida("0320100104513071"))  // 16 dígitos
        assertFalse(FormatoCaravana.esValida("0320100104513AB"))   // letras
        assertFalse(FormatoCaravana.esValida(""))
    }

    @Test fun listaYMensaje() {
        val inv = FormatoCaravana.invalidas(listOf("032010010451307", "ABC123", "1234"))
        assertEquals(listOf("ABC123", "1234"), inv)
        assertTrue(FormatoCaravana.mensaje(inv).contains("2 caravanas con formato inválido"))
    }

    @Test fun normalizarImportada_saca_espacios_sueltos() {
        // El bug de Tru-Test: el EID llega con un espacio en medio.
        assertEquals("032010010451307", FormatoCaravana.normalizarImportada("032 010010451307"))
        assertEquals("032010010451307", FormatoCaravana.normalizarImportada("032  0100 10451307"))
    }

    @Test fun normalizarImportada_agrega_el_cero_perdido() {
        // El bug de Gallagher: el número llega en 14 dígitos, sin el primer 0.
        assertEquals("032010010451307", FormatoCaravana.normalizarImportada("32010010451307"))
        // Con espacio Y sin el cero, a la vez.
        assertEquals("032010010451307", FormatoCaravana.normalizarImportada("32 010010451307"))
    }

    @Test fun normalizarImportada_no_adivina_lo_que_no_puede_corregir() {
        // 13 dígitos, o un prefijo que no es 0320/32: se deja tal cual (sin espacios),
        // para que se note el error en vez de guardar un número inventado.
        assertEquals("0123456789012", FormatoCaravana.normalizarImportada("0123456789012"))
        assertEquals("111000123456789", FormatoCaravana.normalizarImportada("111000123456789"))
        assertFalse(FormatoCaravana.esValida(FormatoCaravana.normalizarImportada("0123456789012")))
    }
}
