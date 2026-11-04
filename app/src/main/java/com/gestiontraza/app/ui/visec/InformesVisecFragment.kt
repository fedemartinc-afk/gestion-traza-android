package com.gestiontraza.app.ui.visec

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentInformesVisecBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * "Informes VISEC": las verificaciones que el usuario mandó desde la app, con el número y el nombre del informe, el resultado
 * (caravanas habilitadas e inhabilitadas) y el Excel para compartir. Solo se llega acá si el usuario tiene el módulo VISEC.
 */
class InformesVisecFragment : Fragment() {

    private var _binding: FragmentInformesVisecBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private var hayEnProceso = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentInformesVisecBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnActualizar.setOnClickListener { cargar() }
        // Mientras haya informes en proceso, la lista se actualiza sola cada 15 segundos.
        cargar()
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(15_000)
                if (hayEnProceso) cargar()
            }
        }
    }

    private fun cargar() {
        val base = session.baseUrl()
        val token = session.token
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ApiClient.informesVisec(base, token) }
            if (_binding == null) return@launch
            binding.progreso.visibility = View.GONE
            if (!r.ok) {
                binding.tvVacio.text = r.message.ifBlank { "No se pudo cargar la lista." }
                binding.tvVacio.visibility = View.VISIBLE
                return@launch
            }
            val arr = r.data?.optJSONArray("informes")
            binding.contenedor.removeAllViews()
            val terminados = mutableListOf<Long>()
            hayEnProceso = false
            for (i in 0 until (arr?.length() ?: 0)) {
                val x = arr!!.getJSONObject(i)
                if (x.optBoolean("terminado")) terminados += x.optLong("id") else hayEnProceso = true
                binding.contenedor.addView(tarjeta(x))
            }
            // Los que ya terminaron y se están viendo no necesitan aviso.
            VisecAviso.quitar(ctx, terminados)
            VisecAviso.marcarVistos(ctx, terminados)
            binding.tvVacio.text = "Todavía no enviaste verificaciones a VISEC desde la app."
            binding.tvVacio.visibility = if ((arr?.length() ?: 0) == 0) View.VISIBLE else View.GONE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texto(t: String, tam: Float, color: Int = Color.parseColor("#1e293b"), negrita: Boolean = false) =
        TextView(requireContext()).apply {
            text = t; textSize = tam; setTextColor(color)
            if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun tarjeta(x: JSONObject): View {
        val ctx = requireContext()
        val estado = x.optString("estado")
        val caja = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_cuenta_normal)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(12), dp(8), dp(12), 0) }
        }
        caja.addView(texto(x.optString("nombre"), 15f, negrita = true))
        val nro = x.optString("nro_informe")
        caja.addView(texto(if (nro.isNotBlank()) "Informe VISEC N° $nro" else "Informe VISEC: todavía sin número", 12f, Color.parseColor("#64748b")))
        val verde = Color.parseColor("#2e7d32"); val rojo = Color.parseColor("#c62828"); val gris = Color.parseColor("#64748b")
        when (estado) {
            "finalizado" -> {
                caja.addView(texto("Finalizado ${x.optString("finalizado")}", 12f, verde, true).apply { setPadding(0, dp(6), 0, 0) })
                caja.addView(texto(resumenResultadoVisec(x), 14f, if (x.optBoolean("todas_validas")) verde else if (x.optInt("inhabilitadas") > 0 || x.optString("lote_texto").contains("inválid", true)) rojo else Color.parseColor("#1e293b"), negrita = true)
                    .apply { setPadding(0, dp(4), 0, 0) })
            }
            "error" -> {
                caja.addView(texto("Con error", 12f, rojo, true).apply { setPadding(0, dp(6), 0, 0) })
                caja.addView(texto(x.optString("error").ifBlank { "No se pudo completar." }, 12f, rojo))
            }
            "cancelada" -> caja.addView(texto("Cancelada", 12f, gris, true).apply { setPadding(0, dp(6), 0, 0) })
            else -> {
                val paso = x.optString("paso").ifBlank { "En cola" }
                caja.addView(texto("En proceso: $paso", 12f, Color.parseColor("#a85a00"), true).apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        caja.addView(texto("${x.optInt("caravanas")} caravanas · enviado ${x.optString("creado")}", 11f, gris).apply { setPadding(0, dp(4), 0, 0) })
        if (x.optBoolean("tiene_excel")) {
            val id = x.optLong("id")
            caja.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📤  Compartir Excel"
                isAllCaps = false
                setOnClickListener { compartirExcel(this, id, x.optString("nombre")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8); gravity = Gravity.START }
            })
        }
        return caja
    }

    /** Baja el Excel de la web y abre el menú de compartir del teléfono (WhatsApp, correo, Drive...). */
    private fun compartirExcel(boton: MaterialButton, id: Long, nombre: String) {
        val base = session.baseUrl()
        val token = session.token
        boton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ApiClient.excelVisec(base, token, id) }
            if (_binding == null) return@launch
            boton.isEnabled = true
            val ctx = requireContext()
            if (!r.ok || r.bytes == null) {
                Toast.makeText(ctx, r.mensaje.ifBlank { "No se pudo bajar el Excel." }, Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val dir = File(ctx.cacheDir, "compartir").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val limpio = r.nombre.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").ifBlank { "informe_visec.xlsx" }
                val archivo = File(dir, limpio).also { it.writeBytes(r.bytes) }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", archivo)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (limpio.endsWith(".xls", true)) "application/vnd.ms-excel"
                           else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, nombre)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Compartir Excel"))
            } catch (e: Exception) {
                Toast.makeText(ctx, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
