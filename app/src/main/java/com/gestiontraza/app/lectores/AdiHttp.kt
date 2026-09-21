package com.gestiontraza.app.lectores

import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class AdiException(msg: String) : Exception(msg)

class AdiRespuesta(val codigo: Int, val estado: String, val cuerpo: String) {
    val exitosa: Boolean get() = codigo in 200..299
}

/**
 * Cliente del protocolo "ADI" de Gallagher (lectores HR, "GGL HR…"): un HTTP/1.1
 * mínimo sobre el mismo enlace serie (Bluetooth SPP o TCP). Sale de descompilar la
 * app oficial "Animal Performance" (clases IADIService / ADIService).
 *
 * Lo que la app oficial manda para un pedido sin cuerpo es exactamente:
 *     GET /sessions HTTP/1.1\r\n
 *     \r\n
 * (dos escrituras seguidas, sin cabeceras). Después lee hasta el primer "\r\n\r\n",
 * toma el código de "HTTP/1.1 200 OK" y de "Content-Length: N" (o, si viene
 * "Transfer-Encoding: chunked", los trozos con tamaño en hexadecimal) y lee el cuerpo.
 *
 * No depende de Android para poder probarse en la PC con streams comunes.
 * [registrar] recibe cada pedido/respuesta para el registro de diagnóstico.
 */
class AdiHttp(
    private val entrada: InputStream,
    private val salida: OutputStream,
    private val registrar: (String) -> Unit = {}
) {
    private val eol = "\r\n"

    suspend fun get(ruta: String, inactividadMs: Long = 20000): AdiRespuesta = pedir("GET", ruta, inactividadMs)

    suspend fun post(ruta: String, inactividadMs: Long = 20000): AdiRespuesta = pedir("POST", ruta, inactividadMs)

    /** [inactividadMs]: cuánto se espera sin que llegue ningún byte antes de darse por vencido. */
    suspend fun pedir(metodo: String, ruta: String, inactividadMs: Long = 20000): AdiRespuesta {
        // Se descarta lo que haya quedado sin leer de un pedido anterior.
        while (entrada.available() > 0) entrada.read()

        registrar(">> $metodo $ruta")
        salida.write("$metodo $ruta HTTP/1.1$eol".toByteArray(Charsets.ISO_8859_1))
        salida.flush()
        salida.write(eol.toByteArray(Charsets.ISO_8859_1))
        salida.flush()

        val cabecera = String(leerHasta("$eol$eol".toByteArray(Charsets.ISO_8859_1), inactividadMs), Charsets.ISO_8859_1)
        val estado = Regex("^HTTP/1\\.[01] (\\d{3}) ?(.*?)\r?\n").find(cabecera)
            ?: throw AdiException("Respuesta inesperada del lector: ${cabecera.take(80).replace("\r", "").replace("\n", " ")}")
        val codigo = estado.groupValues[1].toInt()
        val texto = estado.groupValues[2].trim()

        val largo = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(cabecera)?.groupValues?.get(1)?.toInt()
        val cuerpoBytes: ByteArray = when {
            largo != null -> leerExacto(largo, inactividadMs)
            cabecera.contains("Transfer-Encoding: chunked", ignoreCase = true) -> leerPorTrozos(inactividadMs)
            else -> ByteArray(0)
        }
        val cuerpo = String(cuerpoBytes, Charsets.UTF_8)
        registrar("<< $codigo $texto (${cuerpoBytes.size} bytes)\n${cuerpo.take(MAX_LOG)}${if (cuerpo.length > MAX_LOG) "\n…[recortado, ${cuerpo.length} caracteres en total]" else ""}")
        return AdiRespuesta(codigo, texto, cuerpo)
    }

    private suspend fun leerPorTrozos(inactividadMs: Long): ByteArray {
        val total = ByteArrayOutputStream()
        while (true) {
            val linea = String(leerHasta(eol.toByteArray(Charsets.ISO_8859_1), inactividadMs), Charsets.ISO_8859_1)
            val tam = linea.substringBefore(';').trim().toIntOrNull(16)
                ?: throw AdiException("Tamaño de trozo inválido: ${linea.trim().take(20)}")
            // Cada trozo viene seguido de "\r\n"; el trozo final (tamaño 0) es solo ese "\r\n".
            val trozo = leerExacto(tam + 2, inactividadMs)
            if (tam == 0) break
            total.write(trozo, 0, tam)
        }
        return total.toByteArray()
    }

    /** Lee hasta que lo último recibido sea [fin] (incluido en lo devuelto). */
    private suspend fun leerHasta(fin: ByteArray, inactividadMs: Long): ByteArray {
        val acum = ByteArrayOutputStream()
        var ultimoByte = System.currentTimeMillis()
        val ventana = ByteArray(fin.size)
        var llenos = 0
        while (true) {
            if (entrada.available() > 0) {
                val b = entrada.read()
                if (b < 0) throw AdiException("Se cerró la conexión con el lector")
                acum.write(b)
                ultimoByte = System.currentTimeMillis()
                if (llenos < fin.size) ventana[llenos++] = b.toByte()
                else { System.arraycopy(ventana, 1, ventana, 0, fin.size - 1); ventana[fin.size - 1] = b.toByte() }
                if (llenos == fin.size && ventana.contentEquals(fin)) return acum.toByteArray()
            } else {
                if (System.currentTimeMillis() - ultimoByte > inactividadMs) {
                    throw AdiException("El lector no respondió (esperé ${inactividadMs / 1000} s)")
                }
                delay(10)
            }
        }
    }

    private suspend fun leerExacto(n: Int, inactividadMs: Long): ByteArray {
        val out = ByteArray(n)
        var leidos = 0
        var ultimoByte = System.currentTimeMillis()
        while (leidos < n) {
            val disp = entrada.available()
            if (disp > 0) {
                val r = entrada.read(out, leidos, minOf(disp, n - leidos))
                if (r < 0) throw AdiException("Se cerró la conexión con el lector")
                leidos += r
                ultimoByte = System.currentTimeMillis()
            } else {
                if (System.currentTimeMillis() - ultimoByte > inactividadMs) {
                    throw AdiException("El lector dejó de responder ($leidos de $n bytes)")
                }
                delay(10)
            }
        }
        return out
    }

    companion object {
        private const val MAX_LOG = 20000
    }
}
