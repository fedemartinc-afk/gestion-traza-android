package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.SenasaClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TriNuloTest {
    @Test fun senasaMandaTriNullEnLosMovimientosSinTri() {
        val o = JSONObject("{\"tri\": null, \"nroTri\": \"null\", \"nroTRI\": \"  005111328 \"}")
        assertEquals("", SenasaClient.textoSinNulo(o, "tri"))
        assertEquals("", SenasaClient.textoSinNulo(o, "nroTri"))
        assertEquals("", SenasaClient.textoSinNulo(o, "noExiste"))
        assertEquals("005111328", SenasaClient.textoSinNulo(o, "nroTRI"))
    }
}
