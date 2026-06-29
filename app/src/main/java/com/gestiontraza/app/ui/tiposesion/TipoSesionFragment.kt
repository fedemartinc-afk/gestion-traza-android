package com.gestiontraza.app.ui.tiposesion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.R
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentTipoSesionBinding

class TipoSesionFragment : Fragment() {

    private var _binding: FragmentTipoSesionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentTipoSesionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        val session = SessionManager(requireContext())
        val permitidos = session.tiposSesionPermitidos

        binding.tvSesionNombre.text = session.sesionNombre.ifBlank { session.usuarioNombre }

        binding.btnConsignatario.setOnClickListener {
            if ("consignatario" !in permitidos) {
                Toast.makeText(
                    requireContext(),
                    "Tu usuario no tiene acceso al perfil Consignatario",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            session.tipoSesionActual = "consignatario"
            findNavController().navigate(R.id.action_tipoSesion_to_home)
        }

        binding.btnProductor.setOnClickListener {
            if ("productor" !in permitidos) {
                Toast.makeText(
                    requireContext(),
                    "Tu usuario no tiene acceso al perfil Productor",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            session.tipoSesionActual = "productor"
            findNavController().navigate(R.id.action_tipoSesion_to_home)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
