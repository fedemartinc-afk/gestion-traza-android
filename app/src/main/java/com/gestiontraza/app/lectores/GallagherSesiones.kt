package com.gestiontraza.app.lectores

import kotlinx.coroutines.delay

/**
 * Lectores Gallagher HR (HR4, HR5, "GGL HR…") por el protocolo ADI (HTTP sobre el
 * enlace serie Bluetooth). SIN PROBAR contra un lector real: todo sale de descompilar
 * la app oficial. Por eso cada pedido y respuesta queda en el registro de diagnóstico
 * y las lecturas del XML son tolerantes.
 */
class GallagherSesiones(private val http: AdiHttp, override val familia: String) : LectorSesiones {

    private var transferenciaActivada = false

    override suspend fun listarSesiones(onProgreso: (Int, Int) -> Unit): List<SesionLector> {
        // La app oficial activa el modo de transferencia antes de pedir datos. Si el
        // lector no lo soporta o ya está activo, se sigue igual.
        runCatching { transferenciaActivada = http.post("/config/datatransfer?value=on").exitosa }
        runCatching { http.get("/config/info") }

        val r = http.get("/sessions", inactividadMs = 30000)
        if (!r.exitosa) throw AdiException("El lector respondió ${r.codigo} ${r.estado} a la lista de sesiones")
        onProgreso(1, 1)
        val sesiones = AdiXml.sesiones(r.cuerpo).map {
            SesionLector(
                id = it.guid.ifEmpty { it.nombre },
                nombre = it.nombre,
                fecha = AdiXml.fechaLegible(it.fecha),
                registros = it.animales
            )
        }
        // La más nueva primero (las fechas vienen en ISO, se ordenan como texto).
        return sesiones.sortedByDescending { s -> s.fecha.split('/').reversed().joinToString("") }
    }

    override suspend fun descargarSesion(sesion: SesionLector): List<String> {
        var cuerpo: String? = null
        for (ruta in listOf("/sessions/${sesion.id}", "/animals?sessionid=${sesion.id}")) {
            val r = runCatching { http.get(ruta, inactividadMs = 60000) }.getOrNull()
            if (r != null && r.exitosa && r.cuerpo.isNotBlank()) { cuerpo = r.cuerpo; break }
        }
        if (cuerpo == null) throw AdiException("El lector no entregó la sesión")

        var animales = AdiXml.animales(cuerpo)
        // La sesión solo trae referencias (guid) a los animales; pedirlos de a uno
        // tarda ~0,35 s cada uno. Se intenta primero traerlos todos juntos.
        if (animales.isNotEmpty() && animales.none { it.codigo() != null } && animales.all { it.guid != null }) {
            val masivo = runCatching { http.get("/animals?sessionid=${sesion.id}", inactividadMs = 15000) }.getOrNull()
            if (masivo == null) delay(1500)   // deja pasar una respuesta tardía antes de seguir
            else if (masivo.exitosa) {
                // Se cruza por guid: si el lector ignorara el filtro y devolviera animales de
                // otras sesiones, no se cuelan. Solo se usa si aparecen TODOS los de la sesión.
                val porGuid = runCatching { AdiXml.animales(masivo.cuerpo) }.getOrNull().orEmpty()
                    .filter { it.guid != null && it.codigo() != null }.associateBy { it.guid }
                val ordenados = animales.map { porGuid[it.guid] }
                if (ordenados.all { it != null }) animales = ordenados.filterNotNull()
            }
        }
        val codigos = ArrayList<String>()
        for (a in animales) {
            var codigo = a.codigo()
            // Si la sesión solo trae referencias a los animales, se pide cada uno.
            if (codigo == null && a.guid != null) {
                val r = runCatching { http.get("/animals?guid=${a.guid}") }.getOrNull()
                if (r != null && r.exitosa) {
                    codigo = runCatching { AdiXml.animales(r.cuerpo).firstNotNullOfOrNull { it.codigo() } }.getOrNull()
                }
            }
            if (codigo != null) codigos.add(codigo)
        }
        // Si la estructura no era la esperada, último recurso: números de 15 dígitos.
        return if (codigos.isNotEmpty()) codigos else AdiXml.caravanasPorPatron(cuerpo)
    }

    override suspend fun finalizar() {
        if (transferenciaActivada) runCatching { http.post("/config/datatransfer?value=off", inactividadMs = 5000) }
        transferenciaActivada = false
    }

    companion object {
        /** Nombre Bluetooth de los Gallagher HR: "GGL HR5 …", "HR4 …". */
        fun esGallagher(nombreBluetooth: String?): Boolean {
            val n = nombreBluetooth?.uppercase() ?: return false
            return n.contains("GGL") || n.contains("GALLAGHER") || Regex("(^|[^A-Z])HR[0-9]").containsMatchIn(n)
        }
    }
}
