package com.gestiontraza.app.lectores

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/** Lector Gallagher simulado: habla HTTP por un par de pipes, igual que el enlace Bluetooth. */
private class LectorFalso(val rutas: Map<String, (String) -> ByteArray>) {
    val pedidos = mutableListOf<String>()
    private val haciaLector = PipedOutputStream()
    private val delLector = PipedOutputStream()
    val entradaApp = PipedInputStream(delLector, 1 shl 20)
    private val entradaLector = PipedInputStream(haciaLector, 1 shl 16)
    val salidaApp: PipedOutputStream get() = haciaLector

    init {
        thread(isDaemon = true) {
            val sb = StringBuilder()
            try {
                while (true) {
                    val b = entradaLector.read()
                    if (b < 0) break
                    sb.append(b.toChar())
                    if (sb.endsWith("\r\n\r\n")) {
                        val linea = sb.lines().first()
                        sb.setLength(0)
                        val ruta = linea.split(' ')[1]
                        pedidos.add(linea)
                        val resp = (rutas[ruta] ?: rutas[ruta.substringBefore('?')])?.invoke(ruta)
                            ?: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()
                        // Se manda en dos tandas para probar la lectura fragmentada.
                        val mitad = resp.size / 2
                        delLector.write(resp, 0, mitad); delLector.flush(); Thread.sleep(30)
                        delLector.write(resp, mitad, resp.size - mitad); delLector.flush()
                    }
                }
            } catch (_: Exception) { }
        }
    }
}

private fun conLargo(xml: String) =
    ("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: ${xml.toByteArray().size}\r\n\r\n$xml").toByteArray()

private fun enTrozos(xml: String): ByteArray {
    val b = xml.toByteArray()
    val sb = java.io.ByteArrayOutputStream()
    sb.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
    var i = 0
    while (i < b.size) {
        val n = minOf(37, b.size - i)
        sb.write("${n.toString(16)}\r\n".toByteArray()); sb.write(b, i, n); sb.write("\r\n".toByteArray())
        i += n
    }
    sb.write("0\r\n\r\n".toByteArray())
    return sb.toByteArray()
}

private const val SESIONES = """<?xml version="1.0" encoding="utf-8"?>
<ads:sessions xmlns:ads="urn:x">
 <ads:session><ads:name>Vacunacion mayo</ads:name><ads:internalidentifier>g-1</ads:internalidentifier>
  <ads:startdate>2026-05-20T10:00:00</ads:startdate><ads:animals count="3"/></ads:session>
 <ads:session><ads:name>Pesaje</ads:name><ads:internalidentifier>g-2</ads:internalidentifier>
  <ads:startdate>2026-06-02T09:00:00</ads:startdate></ads:session>
</ads:sessions>"""

private const val ANIMALES = """<ads:session xmlns:ads="urn:x"><ads:name>Vacunacion mayo</ads:name><ads:animals>
 <ads:sessionanimal><ads:animalid><ads:fullrfid>032000123456789</ads:fullrfid><ads:tag>0001</ads:tag></ads:animalid></ads:sessionanimal>
 <ads:sessionanimal><ads:animalid><ads:eid>32000123456790</ads:eid></ads:animalid></ads:sessionanimal>
 <ads:sessionanimal><ads:animalid><ads:tag>ABC12</ads:tag></ads:animalid></ads:sessionanimal>
</ads:animals></ads:session>"""

class GallagherAdiTest {

    private fun driver(l: LectorFalso, log: MutableList<String> = mutableListOf()) =
        GallagherSesiones(AdiHttp(l.entradaApp, l.salidaApp) { log.add(it) }, "Gallagher")

    @Test fun listaSesionesConContentLength() = runBlocking {
        val l = LectorFalso(mapOf(
            "/config/datatransfer" to { "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray() },
            "/config/info" to { conLargo("<ads:info xmlns:ads=\"u\"><ads:softwareversion>1.2</ads:softwareversion></ads:info>") },
            "/sessions" to { conLargo(SESIONES) }
        ))
        val s = driver(l).listarSesiones()
        assertEquals(2, s.size)
        assertEquals("Pesaje", s[0].nombre)            // la más nueva primero
        assertEquals("Vacunacion mayo", s[1].nombre)
        assertEquals("20/05/2026", s[1].fecha)
        assertEquals(3, s[1].registros)
        assertEquals("g-1", s[1].id)
        assertEquals("POST /config/datatransfer?value=on HTTP/1.1", l.pedidos[0])
    }

    @Test fun descargaConTrozosYNormalizaCaravanas() = runBlocking {
        val l = LectorFalso(mapOf("/sessions/g-1" to { enTrozos(ANIMALES) }))
        val c = driver(l).descargarSesion(SesionLector("g-1", "x", "", 3))
        assertEquals(listOf("032000123456789", "032000123456790", "ABC12"), c)
    }

    @Test fun siFallaSesionUsaAnimalesPorSesion() = runBlocking {
        val l = LectorFalso(mapOf("/animals" to { conLargo(ANIMALES) }))
        val c = driver(l).descargarSesion(SesionLector("g-1", "x", "", 3))
        assertEquals(3, c.size)
        assertTrue(l.pedidos.any { it.startsWith("GET /animals?sessionid=g-1") })
    }

    @Test fun sesionConSoloGuidsPideAnimalesUnoAUno() = runBlocking {
        val sesion = "<ads:session xmlns:ads=\"u\"><ads:animals><ads:animal><ads:animalId><ads:internalIdentifier>a1</ads:internalIdentifier></ads:animalId></ads:animal></ads:animals></ads:session>"
        val uno = "<ads:animals xmlns:ads=\"u\"><ads:animal><ads:internalIdentifier>a1</ads:internalIdentifier><ads:tag></ads:tag><ads:eid>032 010010451307</ads:eid><ads:fullRfid>8000080254AB5D6B</ads:fullRfid></ads:animal></ads:animals>"
        val l = LectorFalso(mapOf("/sessions/g-1" to { conLargo(sesion) }, "/animals" to { r -> if (r.contains("guid=")) conLargo(uno) else conLargo("<ads:animals xmlns:ads=\"u\"/>") }))
        assertEquals(listOf("032010010451307"), driver(l).descargarSesion(SesionLector("g-1", "x", "", 1)))
    }

    @Test fun pedidoMasivoConAnimalesDeOtrasSesionesNoSeMezcla() = runBlocking {
        fun an(g: String, eid: String) = "<ads:animal><ads:internalIdentifier>$g</ads:internalIdentifier><ads:eid>$eid</ads:eid></ads:animal>"
        val sesion = "<ads:session xmlns:ads=\"u\"><ads:animals><ads:animal><ads:animalId><ads:internalIdentifier>a1</ads:internalIdentifier></ads:animalId></ads:animal><ads:animal><ads:animalId><ads:internalIdentifier>a2</ads:internalIdentifier></ads:animalId></ads:animal></ads:animals></ads:session>"
        // El lector ignora sessionid y devuelve TODA su base (3 animales, uno ajeno).
        val todos = "<ads:animals xmlns:ads=\"u\">${an("zz", "032000000000999")}${an("a2", "032000000000002")}${an("a1", "032000000000001")}</ads:animals>"
        val l = LectorFalso(mapOf("/sessions/g-1" to { conLargo(sesion) }, "/animals" to { conLargo(todos) }))
        // Salen solo los de la sesión y en el orden de la sesión.
        assertEquals(listOf("032000000000001", "032000000000002"), driver(l).descargarSesion(SesionLector("g-1", "x", "", 2)))
    }

    @Test fun pedidoMasivoIncompletoCaeAlMetodoUnoAUno() = runBlocking {
        fun an(g: String, eid: String) = "<ads:animal><ads:internalIdentifier>$g</ads:internalIdentifier><ads:eid>$eid</ads:eid></ads:animal>"
        val sesion = "<ads:session xmlns:ads=\"u\"><ads:animals><ads:animal><ads:animalId><ads:internalIdentifier>a1</ads:internalIdentifier></ads:animalId></ads:animal><ads:animal><ads:animalId><ads:internalIdentifier>a2</ads:internalIdentifier></ads:animalId></ads:animal></ads:animals></ads:session>"
        val l = LectorFalso(mapOf("/sessions/g-1" to { conLargo(sesion) }, "/animals" to { r ->
            when {
                r.contains("sessionid") -> conLargo("<ads:animals xmlns:ads=\"u\">${an("a1", "032000000000001")}</ads:animals>") // falta a2
                r.contains("guid=a1") -> conLargo("<ads:animals xmlns:ads=\"u\">${an("a1", "032000000000001")}</ads:animals>")
                else -> conLargo("<ads:animals xmlns:ads=\"u\">${an("a2", "032000000000002")}</ads:animals>")
            }
        }))
        assertEquals(listOf("032000000000001", "032000000000002"), driver(l).descargarSesion(SesionLector("g-1", "x", "", 2)))
    }

    @Test fun xmlConOtraFormaUsaPatronDe15Digitos() = runBlocking {
        val raro = "<datos><x>032000111111111</x><y>ruido 12345</y><z>032000222222222</z></datos>"
        val l = LectorFalso(mapOf("/sessions/g-9" to { conLargo(raro) }))
        assertEquals(listOf("032000111111111", "032000222222222"), driver(l).descargarSesion(SesionLector("g-9", "x", "", 0)))
    }

    @Test fun finalizarApagaTransferencia() = runBlocking {
        val l = LectorFalso(mapOf(
            "/config/datatransfer" to { "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray() },
            "/sessions" to { conLargo("<ads:sessions xmlns:ads=\"u\"/>") }
        ))
        val d = driver(l)
        assertEquals(0, d.listarSesiones().size)
        d.finalizar()
        assertTrue(l.pedidos.last().contains("value=off"))
    }

    @Test fun errorDelLectorSeInforma() {
        val l = LectorFalso(mapOf("/sessions" to { "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n".toByteArray() }))
        val e = runCatching { runBlocking { driver(l).listarSesiones() } }.exceptionOrNull()
        assertTrue(e is AdiException && e.message!!.contains("500"))
    }

    @Test fun normalizaRfid() {
        assertEquals("032000123456789", AdiXml.normalizarRfid("032 000-123456789"))
        assertEquals("032000123456789", AdiXml.normalizarRfid("32000123456789"))
        assertEquals(null, AdiXml.normalizarRfid("1234"))
        assertEquals("032010010451307", AdiXml.normalizarRfid("032 010010451307"))
        assertEquals(null, AdiXml.normalizarRfid("8000080254AB5D6B"))   // fullRfid hexadecimal real
    }

    @Test fun detectaMarcaPorNombre() {
        assertTrue(GallagherSesiones.esGallagher("GGL HR5 0123"))
        assertTrue(GallagherSesiones.esGallagher("HR4-8899"))
        assertTrue(!GallagherSesiones.esGallagher("XRS2 12345"))
        assertTrue(!GallagherSesiones.esGallagher("RS420"))
    }
}
