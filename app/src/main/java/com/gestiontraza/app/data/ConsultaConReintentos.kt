package com.gestiontraza.app.data

import kotlinx.coroutines.delay

/**
 * Mismo criterio que usa la web (Ordenar por Origen, Recibidas): se recorre toda la
 * lista una vez sin detenerse en las que no traen datos — se sigue directo con la
 * siguiente — y recién al terminar esa primera pasada se reintentan, como grupo,
 * las que quedaron sin resultado válido, hasta 10 veces más.
 *
 * Entre cada consulta hay una pausa corta, y antes de cada pasada de reintento una
 * espera fija — así no se le pega a SENASA en ráfaga, que es lo que provoca el
 * "Demasiadas solicitudes". Se probó con una espera creciente (hasta 10s por
 * reintento) pero en un lote grande donde casi todo falla eso suma varios minutos
 * de espera muerta, y esa duración termina jugando en contra (más chances de que
 * se corte algo en el medio) sin aportar nada extra frente a una espera corta y
 * fija.
 */
object ConsultaConReintentos {
    const val MAX_REINTENTOS = 10

    /** Pausa entre consultas dentro de una misma pasada. */
    private const val PAUSA_ENTRE_CONSULTAS_MS = 200L

    /** Espera fija antes de cada pasada de reintento (no aplica a la primera pasada). */
    private const val ESPERA_ANTES_DE_REINTENTO_MS = 1_000L

    private fun esperaAntesDePasadaPorDefecto(pasada: Int): Long =
        if (pasada <= 1) 0L else ESPERA_ANTES_DE_REINTENTO_MS

    /**
     * [consultarUno] hace la consulta de un código. [esValido] decide si ese
     * resultado cuenta como éxito (si no, el código entra en la próxima pasada).
     * [onProgreso] se llama antes de cada consulta con la posición dentro de la
     * pasada actual, el total de esa pasada y el número de pasada (1 = primera
     * vuelta; 2 en adelante = reintento N-1 de [MAX_REINTENTOS]).
     *
     * [pausaEntreConsultasMs] y [esperaAntesDePasada] son parametrizables solo para
     * que los tests no tengan que esperar en tiempo real — en la app se usan siempre
     * los valores por defecto.
     *
     * Devuelve un resultado por cada código de [codigos], en el mismo orden.
     */
    suspend fun <T> consultar(
        codigos: List<String>,
        onProgreso: (actual: Int, total: Int, pasada: Int) -> Unit = { _, _, _ -> },
        pausaEntreConsultasMs: Long = PAUSA_ENTRE_CONSULTAS_MS,
        esperaAntesDePasada: (pasada: Int) -> Long = ::esperaAntesDePasadaPorDefecto,
        esValido: (T) -> Boolean,
        consultarUno: suspend (String) -> T
    ): Map<String, T> {
        val resultado = LinkedHashMap<String, T>()
        var pendientes = codigos.distinct()
        var pasada = 1
        while (pendientes.isNotEmpty() && pasada <= MAX_REINTENTOS + 1) {
            val espera = esperaAntesDePasada(pasada)
            if (espera > 0) delay(espera)
            val siguientes = mutableListOf<String>()
            pendientes.forEachIndexed { idx, cod ->
                onProgreso(idx + 1, pendientes.size, pasada)
                if (idx > 0 && pausaEntreConsultasMs > 0) delay(pausaEntreConsultasMs)
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
