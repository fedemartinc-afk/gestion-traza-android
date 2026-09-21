package com.gestiontraza.app.lectores

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lectura del XML "ADI" que devuelven los lectores Gallagher HR (etiquetas con
 * prefijo "ads:", ej. <ads:session>, <ads:name>, <ads:eid>). Los nombres de
 * etiqueta salen del enum ADIXMLElement de la app oficial; la estructura exacta
 * NO está confirmada contra un lector real, por eso se lee con tolerancia:
 * se ignora el prefijo y las mayúsculas y se busca por nombre en todo el árbol.
 */
object AdiXml {

    data class SesionAdi(val guid: String, val nombre: String, val fecha: String, val animales: Int)

    data class AnimalAdi(val fullRfid: String?, val eid: String?, val tag: String?, val guid: String?) {
        /** Caravana a usar: el RFID de 15 dígitos si hay, si no el EID, si no el número visual. */
        fun codigo(): String? {
            for (c in listOf(fullRfid, eid)) {
                val d = normalizarRfid(c)
                if (d != null) return d
            }
            return tag?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parsear(xml: String): Document {
        val limpio = xml.trimStart('﻿', ' ', '\r', '\n', '\t')
        if (!limpio.startsWith("<")) throw AdiException("El lector no devolvió XML: ${limpio.take(60)}")
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        // Sin DTD ni entidades externas (el XML viene de un aparato, pero no cuesta nada).
        for ((k, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        )) runCatching { f.setFeature(k, v) }
        return try {
            f.newDocumentBuilder().parse(InputSource(StringReader(limpio)))
        } catch (e: Exception) {
            throw AdiException("XML inválido del lector: ${e.message?.take(80)}")
        }
    }

    private fun nombre(n: Node): String = n.nodeName.substringAfter(':').lowercase()

    private fun elementos(doc: Document): List<Element> {
        val lista = doc.getElementsByTagName("*")
        return (0 until lista.length).mapNotNull { lista.item(it) as? Element }
    }

    private fun descendientes(e: Element, nom: String): List<Element> {
        val res = ArrayList<Element>()
        fun rec(n: Node) {
            var h = n.firstChild
            while (h != null) {
                if (h is Element) {
                    if (nombre(h) == nom) res.add(h)
                    rec(h)
                }
                h = h.nextSibling
            }
        }
        rec(e)
        return res
    }

    private fun texto(e: Element, nom: String): String? =
        descendientes(e, nom).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun atributo(e: Element, nom: String): String? {
        val a = e.attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            if (n.nodeName.substringAfter(':').equals(nom, ignoreCase = true)) return n.nodeValue
        }
        return null
    }

    /** Sesiones de la respuesta de GET /sessions. */
    fun sesiones(xml: String): List<SesionAdi> {
        val doc = parsear(xml)
        val vistos = LinkedHashMap<String, SesionAdi>()
        for (e in elementos(doc)) {
            if (nombre(e) != "session") continue
            val nom = texto(e, "name") ?: continue
            val guid = texto(e, "internalidentifier") ?: atributo(e, "internalIdentifier") ?: atributo(e, "guid") ?: ""
            val fecha = texto(e, "startdate") ?: ""
            val cont = descendientes(e, "animals").firstOrNull()?.let { atributo(it, "count")?.trim()?.toIntOrNull() }
                ?: (descendientes(e, "sessionanimal").size.takeIf { it > 0 } ?: descendientes(e, "animal").size)
            val clave = guid.ifEmpty { "$nom|$fecha" }
            vistos.putIfAbsent(clave, SesionAdi(guid, nom, fecha, cont))
        }
        return vistos.values.toList()
    }

    /** Animales de GET /sessions/{guid} o de GET /animals?...: uno por elemento, en orden. */
    fun animales(xml: String): List<AnimalAdi> {
        val doc = parsear(xml)
        val todos = elementos(doc)
        // Elementos "de animal" que no contienen otro igual adentro (los de más abajo).
        val unidades = todos.filter { e ->
            val n = nombre(e)
            (n == "sessionanimal" || n == "animal") && descendientes(e, n).isEmpty()
        }
        val fuente = if (unidades.isNotEmpty()) unidades else todos.filter { nombre(it) == "animalid" }
        return fuente.map { e ->
            AnimalAdi(
                fullRfid = texto(e, "fullrfid"),
                eid = texto(e, "eid"),
                tag = texto(e, "tag"),
                guid = texto(e, "internalidentifier")
            )
        }
    }

    /** Último recurso si el XML no tiene la forma esperada: buscar números de 15 dígitos. */
    fun caravanasPorPatron(xml: String): List<String> =
        Regex("(?<![0-9])[0-9]{15}(?![0-9])").findAll(xml).map { it.value }.distinct().toList()

    /** (versión de software, versión de hardware) de GET /config/info. */
    fun versiones(xml: String): Pair<String?, String?> {
        val doc = parsear(xml)
        val todos = elementos(doc)
        fun t(n: String) = todos.firstOrNull { nombre(it) == n }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return t("softwareversion") to t("hardwareversion")
    }

    /**
     * Deja solo dígitos y devuelve el número de 15 dígitos, o null si no lo es. Los
     * Gallagher viejos pierden los ceros iniciales (los de Argentina empiezan con
     * 032…): con 13 o 14 dígitos se completan con ceros a la izquierda.
     */
    fun normalizarRfid(crudo: String?): String? {
        // El fullRfid de Gallagher viene en hexadecimal ("8000080254AB5D6B"): si tiene
        // letras no es un número de caravana y se descarta.
        if (crudo == null || crudo.any { it.isLetter() }) return null
        val d = crudo.filter { it.isDigit() }
        return when {
            d.length == 15 -> d
            d.length in 13..14 -> d.padStart(15, '0')
            else -> null
        }
    }

    fun fechaLegible(iso: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso.trim()) ?: return iso
        return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
    }
}
