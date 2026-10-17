package com.gestiontraza.app.data

/**
 * Sexo leído junto a la caravana. Las listas de caravanas que viajan por la app (argumentos
 * de navegación, sesiones guardadas) son líneas de texto: "032010010451307" o, cuando se
 * registró el sexo, "032010010451307,M" (M = macho, H = hembra). Esto concentra cómo se
 * arma, se separa y se compara esa línea.
 */
object CaravanaSexo {

    /** Resultado de comparar el sexo leído contra el registrado en SIGSA. */
    enum class Coincidencia { OK, NO_COINCIDE, SIN_SEXO }

    /** Normaliza lo que informa SIGSA ("M", "MACHO", "H", "HEMBRA") a "M"/"H"; "" si no hay dato. */
    fun normalizar(sexo: String): String = when (sexo.trim().uppercase()) {
        "M", "MACHO" -> "M"
        "H", "HEMBRA" -> "H"
        else -> ""
    }

    fun linea(codigo: String, sexo: String): String {
        val s = normalizar(sexo)
        return if (s.isEmpty()) codigo else "$codigo,$s"
    }

    fun codigo(linea: String): String = linea.substringBefore(',').trim()

    fun sexo(linea: String): String =
        if (linea.contains(',')) normalizar(linea.substringAfter(',')) else ""

    fun codigos(lineas: List<String>): List<String> = lineas.map { codigo(it) }

    /** Código → sexo leído, solo para las líneas que lo traen. */
    fun mapa(lineas: List<String>): Map<String, String> =
        lineas.mapNotNull { l -> sexo(l).takeIf { it.isNotEmpty() }?.let { codigo(l) to it } }.toMap()

    /**
     * Compara el sexo leído con el de SIGSA. Devuelve null si no se registró sexo para la
     * caravana (no hay nada que comparar).
     */
    fun comparar(leido: String?, sigsa: String): Coincidencia? {
        val l = normalizar(leido.orEmpty())
        if (l.isEmpty()) return null
        val s = normalizar(sigsa)
        return when {
            s.isEmpty() -> Coincidencia.SIN_SEXO
            s == l -> Coincidencia.OK
            else -> Coincidencia.NO_COINCIDE
        }
    }

    /** Texto corto para mostrar al lado del sexo de SIGSA; vacío si no hay comparación. */
    fun etiqueta(c: Coincidencia?): String = when (c) {
        Coincidencia.OK -> "OK"
        Coincidencia.NO_COINCIDE -> "No coincide"
        Coincidencia.SIN_SEXO -> "Sin sexo"
        null -> ""
    }
}
