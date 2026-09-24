package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.ConsultaConReintentos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsultaConReintentosTest {

    @Test fun recorreTodaLaListaSinDetenerseYLuegoReintentaLasFallidas() = runBlocking {
        // "B" falla las primeras 2 veces y recién anda a la 3ra pasada; el resto anda siempre.
        val intentosPorCodigo = mutableMapOf<String, Int>()
        val ordenDeLlamadas = mutableListOf<String>()

        val resultado = ConsultaConReintentos.consultar(
            listOf("A", "B", "C"),
            esValido = { it }
        ) { cod ->
            ordenDeLlamadas.add(cod)
            val n = (intentosPorCodigo[cod] ?: 0) + 1
            intentosPorCodigo[cod] = n
            if (cod == "B") n >= 3 else true
        }

        assertEquals(mapOf("A" to true, "B" to true, "C" to true), resultado)
        // Primera pasada: A, B, C en orden — sin detenerse en la falla de B.
        assertEquals(listOf("A", "B", "C"), ordenDeLlamadas.take(3))
        // B se reintenta como grupo aparte, no en el medio de la primera pasada.
        assertEquals(3, intentosPorCodigo.getValue("B"))
        assertEquals(1, intentosPorCodigo.getValue("A"))
        assertEquals(1, intentosPorCodigo.getValue("C"))
    }

    @Test fun sePlantaEnDiezReintentosSiNuncaAnda() = runBlocking {
        var llamadas = 0
        val resultado = ConsultaConReintentos.consultar(
            listOf("X"),
            esValido = { it }
        ) { llamadas++; false }

        assertEquals(mapOf("X" to false), resultado)
        // 1 pasada inicial + 10 reintentos = 11 llamadas como máximo.
        assertEquals(11, llamadas)
    }

    @Test fun devuelveUnResultadoPorCadaCodigoSinDuplicarLosRepetidos() = runBlocking {
        val resultado = ConsultaConReintentos.consultar(
            listOf("A", "A", "B"),
            esValido = { true }
        ) { it }
        assertEquals(setOf("A", "B"), resultado.keys)
    }
}
