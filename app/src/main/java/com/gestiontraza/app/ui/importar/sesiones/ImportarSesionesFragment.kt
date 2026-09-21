package com.gestiontraza.app.ui.importar.sesiones

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gestiontraza.app.data.SesionGuardada
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.FragmentImportarSesionesBinding
import com.gestiontraza.app.ui.importar.compartirSesion
import com.gestiontraza.app.ui.importar.renombrarSesion

class ImportarSesionesFragment : Fragment() {

    private var _binding: FragmentImportarSesionesBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: SessionFileStore
    private lateinit var adapter: ImportarSesionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarSesionesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        store = SessionFileStore(requireContext())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        adapter = ImportarSesionAdapter(
            onAbrir = { sesion ->
                findNavController().navigate(
                    ImportarSesionesFragmentDirections.actionImportarSesionesToDetalle(sesion.archivo.absolutePath)
                )
            },
            onEliminar = { sesion -> confirmarEliminar(sesion) },
            onCompartir = { sesion -> compartirSesion(sesion, store) },
            onEditar = { sesion ->
                findNavController().navigate(
                    ImportarSesionesFragmentDirections.actionImportarSesionesToEditar(sesion.archivo.absolutePath)
                )
            },
            onRenombrar = { sesion -> renombrarSesion(sesion, store) { refrescar() } }
        )
        binding.recyclerSesiones.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSesiones.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun refrescar() {
        val sesiones = store.listar()
        adapter.submitList(sesiones)
        binding.tvVacio.visibility = if (sesiones.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerSesiones.visibility = if (sesiones.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmarEliminar(sesion: SesionGuardada) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar sesión")
            .setMessage("¿Eliminar \"${sesion.nombre}\"? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                store.eliminar(sesion.archivo)
                refrescar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
