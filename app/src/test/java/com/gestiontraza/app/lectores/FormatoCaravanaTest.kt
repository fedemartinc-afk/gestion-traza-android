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
}
