package com.gestiontraza.app.ui.reading

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class CaravanaItem(
    val codigo: String,
    val esDuplicado: Boolean
)

class ReadingViewModel : ViewModel() {

    private val _caravanas = MutableLiveData<List<CaravanaItem>>(emptyList())
    val caravanas: LiveData<List<CaravanaItem>> = _caravanas

    private val seen = mutableSetOf<String>()

    /** Devuelve true si el texto era un código válido y se agregó (o ya estaba, como duplicado). */
    fun addRaw(raw: String): Boolean {
        val codigo = normalize(raw) ?: return false
        val esDup = seen.contains(codigo)
        if (!esDup) seen.add(codigo)
        val current = _caravanas.value?.toMutableList() ?: mutableListOf()
        current.add(0, CaravanaItem(codigo, esDup))
        _caravanas.value = current
        return true
    }

    /**
     * Agrega un bloque de texto con uno o más códigos: soporta separados por
     * salto de línea, espacios, comas o punto y coma (p. ej. pegado, un
     * archivo importado o un CSV), y también varias caravanas de 15 dígitos
     * concatenadas sin separador en un mismo token (p. ej. copiado desde una
     * planilla en una sola celda), partiéndolas en bloques de 15. Devuelve
     * true si se agregó al menos un código válido.
     */
    fun addTexto(text: String): Boolean {
        var agregado = false
        text.split(Regex("[\\s,;]+")).forEach { token ->
            val clean = token.trim()
            if (clean.isEmpty()) return@forEach
            if (clean.length > 15 && clean.length % 15 == 0 && clean.all { it.isDigit() }) {
                clean.chunked(15).forEach { if (addRaw(it)) agregado = true }
            } else {
                if (addRaw(clean)) agregado = true
            }
        }
        return agregado
    }

    fun validas(): List<String> =
        _caravanas.value?.filter { !it.esDuplicado }?.map { it.codigo } ?: emptyList()

    fun duplicadas(): List<String> =
        _caravanas.value?.filter { it.esDuplicado }?.map { it.codigo }?.distinct() ?: emptyList()

    /** Solo cuenta las válidas (no duplicadas) */
    fun totalValidas(): Int = validas().size

    /** Total incluyendo duplicadas (para mostrar en lista) */
    fun totalLista(): Int = _caravanas.value?.size ?: 0

    fun reset() {
        seen.clear()
        _caravanas.value = emptyList()
    }

    private fun normalize(raw: String): String? {
        val s = raw.trim().uppercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("\n", "")
            .replace("\r", "")
            // Lectores SPP (AS420/RS420) anteponen "#" a cada código transmitido.
            .replace("#", "")

        // Algunos lectores en modo HID mandan texto extra antes o después del
        // código (ej. el Gallagher antepone "LA") — las letras no sirven acá,
        // así que se busca el patrón real de una caravana (032 + 12 dígitos)
        // en cualquier parte de lo que llegó y se descarta el resto.
        val real = Regex("032\\d{12}").find(s)
        if (real != null) return real.value

        if (s.matches(Regex("\\d{15}"))) return s
        // El código alternativo de 9 caracteres exige al menos una letra —
        // si no, un número de 9 dígitos tipeado a mitad de camino hacia los
        // 15 (con una pausa breve) se agregaría solo antes de terminar.
        if (s.matches(Regex("[A-Z0-9]{9}")) && s.any { it.isLetter() }) return s
        return null
    }
}
