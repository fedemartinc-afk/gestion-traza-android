package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.NumeroDte
import org.junit.Assert.assertEquals
import org.junit.Test

class NumeroDteTest {
    @Test fun tomaLosPrimerosNueveDigitos() {
        assertEquals("031905469", NumeroDte.nueve("031905469-3"))   // con guion y dígito verificador
        assertEquals("031905469", NumeroDte.nueve("0319054693"))    // dígito verificador sin guion
        assertEquals("031905469", NumeroDte.nueve("  031905469  "))
        assertEquals("031905469", NumeroDte.nueve("031905469"))
        assertEquals("032764051", NumeroDte.nueve("032764051-7"))
    }

    @Test fun siTieneMenosDeNueveDigitosQuedaComoEstaSinElGuion() {
        assertEquals("12345", NumeroDte.nueve("12345-6"))
        assertEquals("", NumeroDte.nueve(""))
    }
}
