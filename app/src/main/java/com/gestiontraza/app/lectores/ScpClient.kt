package com.gestiontraza.app.lectores

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ScpException(msg: String) : Exception(msg)

/**
 * Cliente del protocolo SCP de Tru-Test sobre Bluetooth clásico (RFCOMM/SPP).
 *
 * Formato, sacado de la app oficial "Data Link" y confirmado contra tráfico real
 * de un XRS2:
 *   comando   -> {COMANDO`CRC}          (CRC = 4 hex mayúscula)
 *   respuesta -> [CONTENIDO`CRC]        contenido (puede venir vacío)
 *             |  ^                      confirmación (ACK)
 *             |  (xx)                   error
 * El CRC es CRC-16/ARC (poly 0xA001 reflejado, valor inicial 0) sobre los bytes
 * ISO-8859-1 del texto "{COMANDO" (o "[CONTENIDO" en las respuestas).
 */
@SuppressLint("MissingPermission")
class ScpClient {

    private var socket: BluetoothSocket? = null
    private var entrada: InputStream? = null
    private var salida: OutputStream? = null
    private val buffer = StringBuilder()

    val estaConectado: Boolean get() = socket?.isConnected == true

    /** Si se le pasa una función, recibe cada comando enviado y su respuesta (para diagnóstico). */
    var registrar: ((String) -> Unit)? = null

    suspend fun conectar(adapter: BluetoothAdapter?, device: BluetoothDevice) {
        cerrar()
        val sock = try {
            abrirRfcomm(adapter, device)
        } catch (e: Exception) {
            throw ScpException(e.message ?: "No se pudo conectar con el lector")
        }
        socket = sock
        entrada = sock.inputStream
        salida = sock.outputStream
    }

    fun cerrar() {
        runCatching { socket?.close() }
        socket = null
        entrada = null
        salida = null
        buffer.clear()
    }

    /**
     * Manda un comando y devuelve su contenido: "" para un ACK o una respuesta
     * vacía, o el texto de la respuesta. Lanza ScpException ante error del lector,
     * CRC inválido o si no responde a tiempo.
     */
    suspend fun comando(cmd: String, timeoutMs: Long = 4000): String = withContext(Dispatchers.IO) {
        val out = salida ?: throw ScpException("Sin conexión con el lector")
        buffer.clear()
        // Se descarta cualquier resto viejo que haya quedado sin leer.
        val ent = entrada!!
        while (ent.available() > 0) ent.read()

        val cuerpo = "{$cmd"
        out.write("$cuerpo`${crcHex(cuerpo)}}".toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val msg = leerMensaje(ent, timeoutMs)
            ?: throw ScpException("El lector no respondió a '$cmd'")
        Log.d("ScpClient", "$cmd -> ${msg.take(48)}")
        registrar?.invoke("$cmd -> ${msg.take(200)}")
        interpretar(cmd, msg)
    }

    // Una lectura bloqueante del socket no se puede cancelar con un timeout de
    // corrutina, así que se consulta available() y se espera de a poco.
    private suspend fun leerMensaje(ent: InputStream, timeoutMs: Long): String? {
        val limite = System.currentTimeMillis() + timeoutMs
        val tmp = ByteArray(512)
        while (true) {
            completo()?.let { return it }
            if (ent.available() > 0) {
                val n = ent.read(tmp)
                if (n < 0) throw ScpException("Se cerró la conexión con el lector")
                buffer.append(String(tmp, 0, n, Charsets.ISO_8859_1))
            } else {
                if (System.currentTimeMillis() > limite) return null
                delay(15)
            }
        }
    }

    /** Devuelve y consume un mensaje completo del buffer, o null si falta parte. */
    private fun completo(): String? {
        while (buffer.isNotEmpty() && buffer[0] != '^' && buffer[0] != '[' && buffer[0] != '(') {
            buffer.deleteCharAt(0)
        }
        if (buffer.isEmpty()) return null
        val cierre = when (buffer[0]) {
            '^' -> return buffer.substring(0, 1).also { buffer.delete(0, 1) }
            '[' -> ']'
            else -> ')'
        }
        val fin = buffer.indexOf(cierre.toString())
        if (fin < 0) return null
        return buffer.substring(0, fin + 1).also { buffer.delete(0, fin + 1) }
    }

    private fun interpretar(cmd: String, msg: String): String {
        if (msg == "^") return ""
        if (msg.startsWith("(")) throw ScpException("El lector devolvió error a '$cmd': $msg")
        // "[contenido`CRC]"
        val inicio = msg.substring(0, msg.length - 1)      // sin el "]"
        val sep = inicio.lastIndexOf('`')
        if (sep < 0) throw ScpException("Respuesta sin CRC a '$cmd'")
        val cuerpo = inicio.substring(0, sep)              // "[contenido"
        val crc = inicio.substring(sep + 1)
        if (!crc.equals(crcHex(cuerpo), ignoreCase = true)) {
            throw ScpException("CRC inválido en la respuesta a '$cmd'")
        }
        return cuerpo.substring(1)
    }

    companion object {
        fun crc16(texto: String): Int {
            var crc = 0
            for (b in texto.toByteArray(Charsets.ISO_8859_1)) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return crc and 0xFFFF
        }

        fun crcHex(texto: String): String = "%04X".format(crc16(texto))
    }
}
