package com.gestiontraza.app.ui.cuentas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.PendingQueue
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentCuentasBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lista las cuentas guardadas en el dispositivo y permite elegir con cuál
 * seguir trabajando por su nombre de usuario, sin volver a escanear el QR
 * cada vez que cambia quién usa el celular.
 */
class CuentasFragment : Fragment() {

    private var _binding: FragmentCuentasBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentCuentasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnAgregarCuenta.setOnClickListener {
            findNavController().navigate(
                CuentasFragmentDirections.actionCuentasToConfig(agregarCuenta = true)
            )
        }

        binding.recyclerCuentas.layoutManager = LinearLayoutManager(requireContext())
        cargarLista()
    }

    private fun cargarLista() {
        val adapter = CuentaAdapter(
            onSeleccionar = { id -> cambiarACuenta(id) },
            onEliminar = { id, nombre -> confirmarEliminar(id, nombre) }
        )
        binding.recyclerCuentas.adapter = adapter
        adapter.submitList(session.listarCuentas())
    }

    /**
     * Antes de activar la cuenta elegida se revalida el token contra la web
     * (mismo chequeo que "Conectar" en Config): si lo revocaron del lado del
     * servidor, se avisa acá en vez de dejar la app en un estado roto.
     */
    private fun cambiarACuenta(id: String) {
        val cred = session.credencialesDe(id)
        if (cred == null) {
            showToast("Esa cuenta ya no está disponible")
            cargarLista()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.verificarSesion(cred.baseUrl, cred.token)
            }
            setLoading(false)

            if (!result.ok || result.data == null) {
                showToast("No se pudo validar esa cuenta: ${result.message}")
                return@launch
            }

            session.activarCuenta(id)

            // Refrescar datos derivados por si cambiaron del lado del servidor
            // desde la última vez que se usó esta cuenta en este dispositivo.
            session.sesionNombre  = result.data.optString("sesion", "")
            session.usuarioNombre = result.data.optString("usuario", "")
            session.wsUsername    = result.data.optString("wsUsername", "")
            session.wsToken       = result.data.optString("wsToken", "")
            session.isAdmin       = result.data.optBoolean("esAdmin", false)
            val envServer = result.data.optString("senasaEnv", "")
            if (envServer.isNotEmpty()) session.senasaEnv = envServer

            val tiposArr = result.data.optJSONArray("tiposSesion")
            val tipos = mutableListOf<String>()
            if (tiposArr != null) for (i in 0 until tiposArr.length()) tipos.add(tiposArr.getString(i))
            if (tipos.isEmpty()) tipos.add("consignatario")
            session.tiposSesionPermitidos = tipos

            findNavController().navigate(CuentasFragmentDirections.actionCuentasToTipoSesion())
        }
    }

    private fun confirmarEliminar(id: String, nombre: String) {
        val pendientes = PendingQueue(requireContext()).countPara(id)
        val aviso = if (pendientes > 0)
            "\n\nTiene $pendientes envío(s) pendiente(s) sin enviar — se van a perder."
        else ""
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar cuenta")
            .setMessage("¿Eliminar \"$nombre\" de este dispositivo?$aviso")
            .setPositiveButton("Eliminar") { _, _ ->
                PendingQueue(requireContext()).reemplazarCuenta(id, emptyList())
                session.eliminarCuenta(id)
                cargarLista()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setLoading(on: Boolean) {
        binding.progressCuentas.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
