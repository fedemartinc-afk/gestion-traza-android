package com.gestiontraza.app.data

/**
 * Une varias sesiones guardadas en una sola lista de líneas ("código" o "código,M"/"código,H").
 *
 * Una misma caravana que está en más de una sesión queda una sola vez. Si una de las repeticiones
 * trae el sexo y la primera no, se conserva el sexo; si traen sexos distintos, queda el de la
 * primera sesión que lo tenía y se cuenta como conflicto para avisarlo.
 */
object UnirSesiones {

    data class Resultado(
        val lineas: List<String>,
        /** Líneas que sobraron por estar repetidas en otra sesión (o en la misma). */
        val repetidas: Int,
        /** Caravanas repetidas cuyo sexo difería entre sesiones. */
        val sexosDistintos: Int
    )

    /** [sesiones] en el orden en que se quieren unir (la primera tiene prioridad). */
    fun unir(sesiones: List<List<String>>): Resultado {
        val porClave = LinkedHashMap<String, String>()
        var repetidas = 0
        var sexosDistintos = 0

        sesiones.flatten().map { it.trim() }.filter { it.isNotEmpty() }.forEach { linea ->
            val clave = SessionFileStore.clave(CaravanaSexo.codigo(linea))
            val actual = porClave[clave]
            if (actual == null) {
                porClave[clave] = linea
                return@forEach
            }
            repetidas++
            val sexoActual = CaravanaSexo.sexo(actual)
            val sexoNuevo = CaravanaSexo.sexo(linea)
            when {
                sexoActual.isEmpty() && sexoNuevo.isNotEmpty() -> porClave[clave] = linea
                sexoActual.isNotEmpty() && sexoNuevo.isNotEmpty() && sexoActual != sexoNuevo -> sexosDistintos++
            }
        }
        return Resultado(porClave.values.toList(), repetidas, sexosDistintos)
    }
}
