package com.gestiontraza.app.lectores

import com.gestiontraza.app.data.FormatoCaravana

data class SesionLector(
    val id: String,
    val nombre: String,
    val fecha: String,
    val registros: Int
)

/**
 * Sesiones guardadas dentro de un lector, para listarlas y bajarlas desde la
 * app (los lectores Tru-Test no las exportan por su cuenta: solo las pasan a
 * su app oficial por Bluetooth).
 */
interface LectorSesiones {
    /** Nombre de la familia, para mostrar y para el origen de la sesión guardada. */
    val familia: String

    suspend fun listarSesiones(onProgreso: (Int, Int) -> Unit = { _, _ -> }): List<SesionLector>

    /** Devuelve una caravana por línea (EID; si falta, el número visual). */
    suspend fun descargarSesion(sesion: SesionLector): List<String>

    /** Se llama al terminar (o al cerrar la pantalla) para dejar el lector como estaba. */
    suspend fun finalizar() {}
}

/**
 * XRS2 / SRS2 — secuencia confirmada contra tráfico Bluetooth real de un XRS2
 * (versión 1.4.8.2) importando sesiones con la app oficial Data Link.
 */
class Xrs2Sesiones(private val scp: ScpClient, override val familia: String) : LectorSesiones {

    override suspend fun listarSesiones(onProgreso: (Int, Int) -> Unit): List<SesionLector> {
        scp.comando("ZA1")
        scp.comando("ZE1")
        scp.comando("SSDF")
        scp.comando("FGDD")

        // "FL" devuelve el número de la sesión siguiente, una por llamada, y una
        // respuesta vacía al terminar. "FLN" dice cuántas hay.
        val total = scp.comando("FLN").trim().toIntOrNull() ?: 0
        val ids = mutableListOf<String>()
        while (true) {
            val id = scp.comando("FL").trim()
            if (id.isEmpty()) break
            ids.add(id)
            if (ids.size > total + 5) break   // corta si el lector no termina nunca
        }

        val sesiones = mutableListOf<SesionLector>()
        ids.forEachIndexed { i, id ->
            onProgreso(i + 1, ids.size)
            val fecha = scp.comando("FPDA$id").trim()
            val registros = scp.comando("FPNR$id").trim().toIntOrNull() ?: 0
            val nombre = scp.comando("FPNA$id").trim().ifEmpty { "Sesion$id" }
            sesiones.add(SesionLector(id, nombre, fecha, registros))
        }
        // La más nueva primero.
        return sesiones.sortedByDescending { it.id.toIntOrNull() ?: 0 }
    }

    override suspend fun descargarSesion(sesion: SesionLector): List<String> {
        scp.comando("FF${sesion.id}")

        // Encabezados de columnas, uno por llamada, hasta una respuesta vacía.
        // Cada uno viene como CODIGO(2) + TIPO(1) + NOMBRE, ej. "F1AEID", "F0AVID".
        val encabezados = mutableListOf<String>()
        while (encabezados.size < 60) {
            val h = scp.comando("FH").trim()
            if (h.isEmpty()) break
            encabezados.add(h)
        }
        if (encabezados.isEmpty()) throw ScpException("La sesión no tiene columnas")

        scp.comando("FD")
        val filas = scp.comando("FE").trim().toIntOrNull() ?: sesion.registros
        scp.comando("SLFI1")
        scp.comando("SLFI0")
        // "FI" + lista de códigos de columna, ej. "FIF1,F0,RD,RT,C1".
        scp.comando("FI" + encabezados.joinToString(",") { it.take(2) })

        val iEid = encabezados.indexOfFirst { it.drop(3).equals("EID", ignoreCase = true) }
        val iVid = encabezados.indexOfFirst { it.drop(3).equals("VID", ignoreCase = true) }
        if (iEid < 0 && iVid < 0) throw ScpException("La sesión no tiene columna EID ni VID")

        // "FN5" devuelve las próximas 5 filas: "n,campo,campo,...;n,campo,..." y
        // sigue con las siguientes en cada llamada. La primera columna NO es un
        // número de fila único: es un contador que da la vuelta cada 10 (0-9) y
        // sirve para detectar filas perdidas ("first column % 10" en la app
        // oficial). Los campos de cada columna del encabezado van corridos +1.
        val codigos = ArrayList<String>()
        var recibidas = 0
        var llamadas = 0
        while (recibidas < filas && llamadas < filas + 20) {
            llamadas++
            val bloque = scp.comando("FN5", timeoutMs = 8000).trim()
            if (bloque.isEmpty()) break
            for (fila in bloque.split(';')) {
                if (fila.isBlank()) continue
                val c = fila.split(',')
                val contador = c.firstOrNull()?.trim()?.toIntOrNull() ?: continue
                if (contador != recibidas % 10) {
                    throw ScpException("Fila fuera de secuencia (esperaba ${recibidas % 10}, llegó $contador): se perdieron datos")
                }
                recibidas++
                val eidCrudo = if (iEid >= 0) c.getOrNull(iEid + 1)?.trim().orEmpty() else ""
                val vid = if (iVid >= 0) c.getOrNull(iVid + 1)?.trim().orEmpty() else ""
                // El EID es el identificador electrónico que trae el lector: se usa siempre
                // que venga informado, sea una caravana SENASA (0320…) u otro estándar (ej.
                // 982…, de otro tipo de caravaneo electrónico) — no se reemplaza por el VID
                // (número visual, de 3 a 5 caracteres) solo porque no empiece con 0320. Se le
                // corrigen los espacios sueltos y, si le falta, el primer cero. El VID se usa
                // únicamente cuando no hay EID.
                val codigo = if (eidCrudo.isNotEmpty()) FormatoCaravana.normalizarImportada(eidCrudo) else vid
                if (codigo.isNotEmpty()) codigos.add(codigo)
            }
        }
        if (recibidas < filas) {
            throw ScpException("Se recibieron $recibidas de $filas caravanas")
        }

        runCatching { scp.comando("FF${sesion.id}") }
        return codigos
    }

    companion object {
        /**
         * Identifica el modelo con "ZI" y devuelve el driver si esta familia está
         * soportada, o null si no. Familias vistas en el código de la app oficial
         * pero todavía sin probar contra hardware real: XR3000/ID3000/EziWeigh,
         * XRP/XRS antiguos (comandos DN/DS/DL) y XR5000/ID5000 (WiFi, no Bluetooth).
         */
        suspend fun detectar(scp: ScpClient): Pair<String, LectorSesiones?> {
            val modelo = scp.comando("ZI").trim()
            val u = modelo.uppercase()
            val driver = if (u.contains("XRS2") || u.contains("SRS2")) Xrs2Sesiones(scp, "Tru-Test $modelo") else null
            return modelo to driver
        }
    }
}
