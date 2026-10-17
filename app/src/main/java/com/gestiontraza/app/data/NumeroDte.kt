package com.gestiontraza.app.data

/**
 * Número de DTe que se consulta y se envía a SENASA: los primeros 9 dígitos. El guion y el dígito
 * verificador que le sigue ("031905469-3") no se tienen en cuenta.
 */
object NumeroDte {
    fun nueve(dte: String): String {
        val base = dte.trim().substringBefore('-').trim()
        val digitos = base.filter { it.isDigit() }
        return if (digitos.length >= 9) digitos.take(9) else base
    }
}
