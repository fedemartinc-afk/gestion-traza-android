package com.gestiontraza.app.ui.importar.editar

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.FragmentImportarEditarSesionBinding

/**
 * Edita las caravanas de una sesión guardada: una por línea, se pueden borrar,
 * corregir o agregar. Al guardar se quitan las repetidas (queda la primera).
 */
class ImportarEditarSesionFragment : Fragment() {

    private var _binding: FragmentImportarEditarSesionBinding? = null
    private val binding get() = _binding!!
    private val args: ImportarEditarSesionFragmentArgs by navArgs()
    private lateinit var store: SessionFileStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarEditarSesionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        store = SessionFileStore(requireContext())
        binding.btnBack.setOnClickListener { salir() }
        binding.btnCancelar.setOnClickListener { salir() }

        val sesion = store.porRuta(args.rutaArchivo)
        if (sesion == null) {
            binding.tvNombre.text = "Sesión no encontrada"
            binding.etContenido.isEnabled = false
            binding.btnGuardar.isEnabled = false
            return
        }
        binding.tvNombre.text = sesion.nombre
        val original = store.leer(sesion.archivo).filter { it.isNotBlank() }
        // Si la pantalla se recrea (giro, etc.) el EditText conserva lo escrito por sí solo.
        if (saved == null) binding.etContenido.setText(original.joinToString("\n"))
        resumir()

        binding.etContenido.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = resumir()
        })

        binding.btnGuardar.setOnClickListener {
            val lineas = lineasEscritas()
            val unicas = quitarRepetidas(lineas)
            if (unicas.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Sesión vacía")
                    .setMessage("No quedó ninguna caravana. Para borrar la sesión usá el cesto en la lista.")
                    .setPositiveButton("Entendido", null)
                    .show()
                return@setOnClickListener
            }
            store.actualizar(sesion.archivo, unicas)
            val quitadas = lineas.size - unicas.size
            Toast.makeText(
                requireContext(),
                "Sesión guardada (${unicas.size} caravanas" +
                    (if (quitadas > 0) ", se quitaron $quitadas repetidas" else "") + ")",
                Toast.LENGTH_LONG
            ).show()
            findNavController().navigateUp()
        }
    }

    private fun lineasEscritas(): List<String> =
        binding.etContenido.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun quitarRepetidas(lineas: List<String>): List<String> {
        val vistas = HashSet<String>()
        return lineas.filter { vistas.add(SessionFileStore.clave(it)) }
    }

    private fun resumir() {
        val lineas = lineasEscritas()
        val repetidas = lineas.size - quitarRepetidas(lineas).size
        binding.tvResumen.text = buildString {
            append("${lineas.size} línea${if (lineas.size != 1) "s" else ""}")
            if (repetidas > 0) append("  ·  $repetidas repetida${if (repetidas != 1) "s" else ""} (se quitarán al guardar)")
        }
    }

    private fun salir() {
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
