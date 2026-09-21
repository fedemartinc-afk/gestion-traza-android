package com.gestiontraza.app.data

/**
 * Formato válido de una caravana para enviarla a un DTe: 15 dígitos numéricos que
 * empiezan con 0320. Se ignoran espacios y guiones ("032 010010451307").
 */
object FormatoCaravana {
    private val VALIDA = Regex("^0320[0-9]{11}$")

    fun normalizar(c: String) = c.replace(" ", "").replace("-", "")

    fun esValida(c: String) = VALIDA.matches(normalizar(c))

    fun invalidas(caravanas: List<String>): List<String> = caravanas.filterNot { esValida(it) }

    fun mensaje(invalidas: List<String>): String {
        val n = invalidas.size
        val ejemplos = invalidas.take(8).joinToString(", ")
        val mas = if (n > 8) " y ${n - 8} más" else ""
        return "Hay $n caravana${if (n != 1) "s" else ""} con formato inválido " +
            "(deben ser numéricas de 15 dígitos y comenzar con 0320): $ejemplos$mas. " +
            "Corregilas o quitalas antes de enviar."
    }
}
