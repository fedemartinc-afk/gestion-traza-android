package com.gestiontraza.app.ui.importar.detalle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.FragmentImportarDetalleSesionBinding
import com.gestiontraza.app.ui.importar.compartirSesion
import com.gestiontraza.app.ui.reading.ReadingViewModel
import org.json.JSONArray

class ImportarDetalleSesionFragment : Fragment() {

    private var _binding: FragmentImportarDetalleSesionBinding? = null
    private val binding get() = _binding!!
    private val args: ImportarDetalleSesionFragmentArgs by navArgs()
    private lateinit var store: SessionFileStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarDetalleSesionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        store = SessionFileStore(requireContext())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        val sesion = store.porRuta(args.rutaArchivo)
        if (sesion == null) {
            binding.tvNombre.text = "Sesión no encontrada"
            binding.tvDetalle.text = ""
            binding.btnEliminar.isEnabled = false
            binding.btnCompartir.isEnabled = false
            binding.btnUsarEnApp.isEnabled = false
            return
        }

        binding.tvNombre.text = sesion.nombre
        binding.tvDetalle.text =
            "${sesion.origen} · ${SessionFileStore.FORMATO_FECHA.format(sesion.fecha)} · ${sesion.lineas} línea${if (sesion.lineas != 1) "s" else ""}"
        val contenido = store.leer(sesion.archivo)
        binding.tvContenido.text = contenido.joinToString("\n")

        // Las líneas de la sesión se normalizan igual que una lectura en vivo
        // (mismo normalize()/dedup de ReadingViewModel) y de ahí van al Hub,
        // donde se elige qué hacer con ellas según el perfil activo.
        binding.btnUsarEnApp.setOnClickListener {
            val vm = ReadingViewModel()
            vm.addTexto(contenido.joinToString("\n"))
            val validas = vm.validas()
            if (validas.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "La sesión no tiene caravanas válidas", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val json = JSONArray(validas).toString()
            findNavController().navigate(
                ImportarDetalleSesionFragmentDirections.actionImportarDetalleToHub(json)
            )
        }

        binding.btnEliminar.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar sesión")
                .setMessage("¿Eliminar \"${sesion.nombre}\"? Esta acción no se puede deshacer.")
                .setPositiveButton("Eliminar") { _, _ ->
                    store.eliminar(sesion.archivo)
                    findNavController().navigateUp()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnCompartir.setOnClickListener {
            compartirSesion(sesion, store)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
