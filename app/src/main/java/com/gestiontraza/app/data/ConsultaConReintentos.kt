package com.gestiontraza.app.data

/**
 * Mismo criterio que usa la web (Ordenar por Origen, Recibidas): se recorre toda la
 * lista una vez sin detenerse en las que no traen datos — se sigue directo con la
 * siguiente — y recién al terminar esa primera pasada se reintentan, como grupo,
 * las que quedaron sin resultado válido, hasta 10 veces más.
 */
object ConsultaConReintentos {
    const val MAX_REINTENTOS = 10

    /**
     * [consultarUno] hace la consulta de un código. [esValido] decide si ese
     * resultado cuenta como éxito (si no, el código entra en la próxima pasada).
     * [onProgreso] se llama antes de cada consulta con la posición dentro de la
     * pasada actual, el total de esa pasada y el número de pasada (1 = primera
     * vuelta; 2 en adelante = reintento N-1 de [MAX_REINTENTOS]).
     *
     * Devuelve un resultado por cada código de [codigos], en el mismo orden.
     */
    suspend fun <T> consultar(
        codigos: List<String>,
        onProgreso: (actual: Int, total: Int, pasada: Int) -> Unit = { _, _, _ -> },
        esValido: (T) -> Boolean,
        consultarUno: suspend (String) -> T
    ): Map<String, T> {
        val resultado = LinkedHashMap<String, T>()
        var pendientes = codigos.distinct()
        var pasada = 1
        while (pendientes.isNotEmpty() && pasada <= MAX_REINTENTOS + 1) {
            val siguientes = mutableListOf<String>()
            pendientes.forEachIndexed { idx, cod ->
                onProgreso(idx + 1, pendientes.size, pasada)
                val r = consultarUno(cod)
                resultado[cod] = r
                if (!esValido(r)) siguientes.add(cod)
            }
            pendientes = siguientes
            pasada++
        }
        return resultado
    }
}
