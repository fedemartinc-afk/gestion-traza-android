package com.gestiontraza.app.data

/**
 * Formato válido de una caravana para enviarla a un DTe: 15 dígitos numéricos que
 * empiezan con 0320. Se ignoran espacios y guiones ("032 010010451307").
 */
object FormatoCaravana {
    private val VALIDA = Regex("^0320[0-9]{11}$")
    private val CATORCE_SIN_CERO = Regex("^32[0-9]{12}$")

    fun normalizar(c: String) = c.replace(Regex("\\s"), "").replace("-", "")

    fun esValida(c: String) = VALIDA.matches(normalizar(c))

    fun invalidas(caravanas: List<String>): List<String> = caravanas.filterNot { esValida(it) }

    /**
     * Corrige dos errores típicos de los lectores al importar una sesión: espacios
     * sueltos en medio del número, y el primer "0" perdido (queda en 14 dígitos,
     * arrancando en "32" en vez de "0320"). Si no se puede corregir así, devuelve el
     * texto tal como llegó (sin espacios ni guiones) para que el error quede a la
     * vista en vez de adivinar el resto del número.
     */
    fun normalizarImportada(raw: String): String {
        val limpio = normalizar(raw)
        if (esValida(limpio)) return limpio
        if (CATORCE_SIN_CERO.matches(limpio)) {
            val conCero = "0$limpio"
            if (esValida(conCero)) return conCero
        }
        return limpio
    }

    fun mensaje(invalidas: List<String>): String {
        val n = invalidas.size
        val ejemplos = invalidas.take(8).joinToString(", ")
        val mas = if (n > 8) " y ${n - 8} más" else ""
        return "Hay $n caravana${if (n != 1) "s" else ""} con formato inválido " +
            "(deben ser numéricas de 15 dígitos y comenzar con 0320): $ejemplos$mas. " +
            "Corregilas o quitalas antes de enviar."
    }
}
