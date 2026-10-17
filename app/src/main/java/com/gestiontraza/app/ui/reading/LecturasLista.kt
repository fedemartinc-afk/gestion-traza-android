package com.gestiontraza.app.ui.reading

import com.gestiontraza.app.data.CaravanaSexo

/** Una caravana tal como se la ve en la lista de lectura. */
data class CaravanaItem(
    val id: Long,
    val codigo: String,
    val esDuplicado: Boolean,
    /** Sexo leído ("M"/"H"), o vacío si no se registró. */
    val sexo: String = "",
    /** La misma caravana se leyó más de una vez con sexos distintos. */
    val sexoConflicto: Boolean = false
)

/**
 * Lecturas de una sesión de lectura, en orden cronológico. La primera lectura de un código es
 * la válida y las siguientes son duplicadas; esto se recalcula cada vez, así que al eliminar una
 * lectura la que quedaba como duplicada pasa a ser la válida.
 */
class LecturasLista {

    private class Lectura(val id: Long, val codigo: String, val sexo: String)

    private val lecturas = mutableListOf<Lectura>()
    private var siguienteId = 1L

    /** Devuelve el id de la lectura agregada. */
    fun agregar(codigo: String, sexo: String = ""): Long {
        val id = siguienteId++
        lecturas.add(Lectura(id, codigo, CaravanaSexo.normalizar(sexo)))
        return id
    }

    fun eliminar(id: Long) {
        lecturas.removeAll { it.id == id }
    }

    fun limpiar() {
        lecturas.clear()
    }

    fun estaVacia() = lecturas.isEmpty()

    /** Más reciente primero, como se muestra. */
    fun items(): List<CaravanaItem> {
        val vistos = mutableSetOf<String>()
        val sexosPorCodigo = lecturas.groupBy { it.codigo }
            .mapValues { (_, l) -> l.map { it.sexo }.filter { it.isNotEmpty() }.toSet() }
        return lecturas.map { l ->
            val esDup = !vistos.add(l.codigo)
            CaravanaItem(
                id = l.id,
                codigo = l.codigo,
                esDuplicado = esDup,
                sexo = l.sexo,
                sexoConflicto = (sexosPorCodigo[l.codigo]?.size ?: 0) > 1
            )
        }.asReversed()
    }

    /** Una por código (la primera leída), más reciente primero. */
    fun validas(): List<CaravanaItem> = items().filter { !it.esDuplicado }

    /** Caravanas con sexos distintos entre sus lecturas, sin repetir. */
    fun conflictos(): List<String> = items().filter { it.sexoConflicto }.map { it.codigo }.distinct()
}
