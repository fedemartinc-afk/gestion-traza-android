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

    fun addRaw(raw: String) {
        val codigo = normalize(raw) ?: return
        val esDup = seen.contains(codigo)
        if (!esDup) seen.add(codigo)
        val current = _caravanas.value?.toMutableList() ?: mutableListOf()
        current.add(0, CaravanaItem(codigo, esDup))
        _caravanas.value = current
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

        if (s.matches(Regex("\\d{15}"))) return s
        if (s.matches(Regex("[A-Z0-9]{9}"))) return s
        val noSpace = s.replace(" ", "")
        if (noSpace.matches(Regex("\\d{15}"))) return noSpace
        return null
    }
}
