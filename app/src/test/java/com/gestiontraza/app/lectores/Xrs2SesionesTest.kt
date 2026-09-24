package com.gestiontraza.app.lectores

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Lector Tru-Test XRS2 simulado: responde el protocolo SCP por un par de pipes,
 * igual que la conexión Bluetooth real. Solo entiende los comandos que usa
 * Xrs2Sesiones.descargarSesion (no la lista de sesiones).
 */
private class LectorScpFalso(private val headers: List<String>, private val filas: List<String>, private val total: Int) {
    private val haciaLector = PipedOutputStream()
    private val delLector = PipedOutputStream()
    val entradaApp: InputStream = PipedInputStream(delLector, 1 shl 16)
    val salidaApp: OutputStream = haciaLector
    private val entradaLector = PipedInputStream(haciaLector, 1 shl 16)

    private var idxHeader = 0
    private var filasEnviadas = false

    init {
        thread(isDaemon = true) {
            val sb = StringBuilder()
            try {
                while (true) {
                    val b = entradaLector.read()
                    if (b < 0) break
                    sb.append(b.toChar())
                    if (sb.endsWith("}")) {
                        val cmd = sb.toString().substringAfter('{').substringBefore('`')
                        sb.setLength(0)
                        responder(cmd)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun escribir(texto: String) {
        val bytes = texto.toByteArray(Charsets.ISO_8859_1)
        delLector.write(bytes); delLector.flush()
    }

    private fun marco(contenido: String): String {
        val cuerpo = "[$contenido"
        return "$cuerpo`${ScpClient.crcHex(cuerpo)}]"
    }

    private fun responder(cmd: String) {
        when {
            cmd == "FD" -> escribir("^")
            cmd == "FE" -> escribir(marco(total.toString()))
            cmd == "FH" -> {
                if (idxHeader < headers.size) escribir(marco(headers[idxHeader++]))
                else escribir(marco(""))
            }
            cmd == "SLFI1" || cmd == "SLFI0" -> escribir(marco("1"))
            cmd.startsWith("FI") -> escribir("^")
            cmd.startsWith("FF") -> escribir("^")
            cmd == "FN5" -> {
                if (!filasEnviadas) { escribir(marco(filas.joinToString(";"))); filasEnviadas = true }
                else escribir(marco(""))
            }
            else -> escribir("^")
        }
    }
}

class Xrs2SesionesTest {

    /** Conecta un ScpClient a un lector simulado sin pasar por Bluetooth real. */
    private fun clienteContra(falso: LectorScpFalso): ScpClient {
        val scp = ScpClient()
        for (nombre in listOf("entrada" to falso.entradaApp, "salida" to falso.salidaApp)) {
            val campo = ScpClient::class.java.getDeclaredField(nombre.first)
            campo.isAccessible = true
            campo.set(scp, nombre.second)
        }
        return scp
    }

    @Test fun corrigeEspacioYCeroPerdidoYMantieneElEidAunqueNoSeaCaravanaSenasa() = runBlocking {
        val headers = listOf("F1AEID", "F0AVID")
        val filas = listOf(
            "0,032010004255112,",              // caravana normal, sin VID
            "1,982000451188617,T515",          // EID de otro estándar (no 0320): se conserva igual, NO el VID
            "2,32010004255112,",                // 14 dígitos, sin el primer 0: se le agrega
            "3,032 010004255112,",              // espacio suelto en medio del EID (bug reportado)
            "4,,4866"                           // sin EID: recién ahí se usa el VID
        )
        val scp = clienteContra(LectorScpFalso(headers, filas, filas.size))
        val driver = Xrs2Sesiones(scp, "Tru-Test XRS2")

        val codigos = driver.descargarSesion(SesionLector("158", "Sesion158", "18/11/2025", filas.size))

        assertEquals(
            listOf("032010004255112", "982000451188617", "032010004255112", "032010004255112", "4866"),
            codigos
        )
    }
}
