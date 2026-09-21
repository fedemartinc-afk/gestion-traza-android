package com.gestiontraza.app.ui.importar.conectar

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.FragmentImportarConectarBinding

/**
 * Conexión genérica por Bluetooth SPP — no depende de la marca/modelo del
 * lector, sirve para el AS420 y cualquier otro lector que soporte ese modo.
 * Reutiliza el mismo BtConnectionViewModel (alcance Activity) que ya usa
 * Inicio/Lectura, así la conexión sobrevive a la navegación entre pantallas.
 */
class ImportarConectarFragment : Fragment() {

    private var _binding: FragmentImportarConectarBinding? = null
    private val binding get() = _binding!!
    private val btVm: BtConnectionViewModel by activityViewModels()

    private val lineasCapturadas = mutableListOf<String>()
    // Claves de las caravanas ya leídas (para detectar repetidas) y de las que se
    // repitieron alguna vez (se remarcan en rojo en la lista).
    private val clavesLeidas = HashSet<String>()
    private val clavesRepetidas = HashSet<String>()
    private var repetidasDescartadas = 0
    private var textoAviso: String? = null
    private var dispositivoConectado: String = "Bluetooth"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarConectarBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.tvNombreLector.text = "Crear sesiones"
        binding.tvNota.text = "Sirve para cualquier lector con modo Bluetooth SPP habilitado — no hace falta elegir marca."

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnElegirDispositivo.setOnClickListener { mostrarSelectorDispositivos() }

        btVm.estado.observe(viewLifecycleOwner) { estado ->
            when (estado) {
                is BtConnectionViewModel.Estado.Conectado -> {
                    dispositivoConectado = estado.deviceName
                    binding.tvEstado.text = "Conectado: ${estado.deviceName}"
                    binding.dotEstado.setBackgroundResource(R.drawable.circle_green)
                }
                is BtConnectionViewModel.Estado.Conectando -> {
                    binding.tvEstado.text = "Conectando con ${estado.deviceName}…"
                    binding.dotEstado.setBackgroundResource(R.drawable.circle_gray)
                }
                is BtConnectionViewModel.Estado.Error -> {
                    binding.tvEstado.text = estado.msg
                    binding.dotEstado.setBackgroundResource(R.drawable.circle_red)
                }
                BtConnectionViewModel.Estado.Desconectado -> {
                    binding.tvEstado.text = "Sin conectar"
                    binding.dotEstado.setBackgroundResource(R.drawable.circle_gray)
                }
            }
        }

        btVm.hayLineas.observe(viewLifecycleOwner) {
            val nuevas = btVm.drenarLineas()
            if (nuevas.isNotEmpty()) procesarLineas(nuevas)
        }

        // La pantalla se recrea al ir y volver: se vuelve a dibujar lo ya capturado.
        mostrarContenido()
        mostrarAviso(textoAviso)

        binding.btnDescartar.setOnClickListener {
            limpiarCaptura()
            mostrarContenido()
            mostrarAviso(null)
        }

        binding.btnGuardarSesion.setOnClickListener {
            if (lineasCapturadas.isEmpty()) {
                showToast("Todavía no llegó ningún dato del lector")
                return@setOnClickListener
            }
            pedirNombreYGuardar(dispositivoConectado)
        }
    }

    /**
     * Una caravana ya leída no se agrega otra vez a la sesión: se avisa (mensaje rojo,
     * vibración) y la línea original se remarca en la lista.
     */
    private fun procesarLineas(nuevas: List<String>) {
        var ultimaRepetida: String? = null
        for (crudo in nuevas) {
            val linea = crudo.trim()
            if (linea.isEmpty()) continue
            val clave = SessionFileStore.clave(linea)
            if (clavesLeidas.add(clave)) {
                lineasCapturadas.add(linea)
            } else {
                clavesRepetidas.add(clave)
                repetidasDescartadas++
                ultimaRepetida = linea
            }
        }
        mostrarContenido()
        if (ultimaRepetida != null) {
            val texto = "⚠ Caravana repetida: $ultimaRepetida\nYa estaba leída, no se agregó a la sesión" +
                (if (repetidasDescartadas > 1) " ($repetidasDescartadas repetidas en total)" else "")
            mostrarAviso(texto)
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showToast("Caravana repetida: $ultimaRepetida")
        }
    }

    private fun limpiarCaptura() {
        lineasCapturadas.clear()
        clavesLeidas.clear()
        clavesRepetidas.clear()
        repetidasDescartadas = 0
        textoAviso = null
    }

    private fun mostrarAviso(texto: String?) {
        textoAviso = texto
        val v = _binding?.tvAviso ?: return
        v.text = texto.orEmpty()
        v.visibility = if (texto.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun mostrarContenido() {
        val b = _binding ?: return
        val sb = SpannableStringBuilder()
        for (linea in lineasCapturadas) {
            val ini = sb.length
            sb.append(linea).append('\n')
            if (SessionFileStore.clave(linea) in clavesRepetidas) {
                val fin = sb.length - 1
                sb.setSpan(BackgroundColorSpan(Color.parseColor("#FFCDD2")), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#B71C1C")), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        b.tvContenido.text = sb
    }

    @SuppressLint("MissingPermission")
    private fun mostrarSelectorDispositivos() {
        val dispositivos = btVm.pairedDevices()
        if (dispositivos.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin dispositivos vinculados")
                .setMessage("Vinculá el lector desde Bluetooth del sistema (Ajustes → Bluetooth) y volvé a intentar.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val nombres = dispositivos.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Elegir lector")
            .setItems(nombres) { _, idx -> btVm.conectar(dispositivos[idx]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirNombreYGuardar(origen: String) {
        val et = android.widget.EditText(requireContext()).apply {
            hint = "Nombre de la sesión"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Guardar sesión (${lineasCapturadas.size} caravanas)")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = et.text?.toString()?.trim()?.ifEmpty { null }
                    ?: "sesion_${System.currentTimeMillis()}"
                SessionFileStore(requireContext()).guardar(nombre, origen, lineasCapturadas.toList())
                showToast("Sesión guardada")
                limpiarCaptura()
                findNavController().navigate(
                    ImportarConectarFragmentDirections.actionImportarConectarToSesiones()
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        // No desconectar acá: btVm es de alcance Activity y la conexión debe
        // sobrevivir la navegación (ej. al guardar sesión y pasar a Sesiones).
        _binding = null
    }
}
