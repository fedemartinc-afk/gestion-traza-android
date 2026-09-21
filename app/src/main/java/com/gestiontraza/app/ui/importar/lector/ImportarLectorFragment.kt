package com.gestiontraza.app.ui.importar.lector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.FragmentImportarLectorBinding
import com.gestiontraza.app.databinding.ItemSesionLectorBinding
import com.gestiontraza.app.lectores.AdiHttp
import com.gestiontraza.app.lectores.GallagherSesiones
import com.gestiontraza.app.lectores.LectorSesiones
import com.gestiontraza.app.lectores.RegistroDiagnostico
import com.gestiontraza.app.lectores.ScpClient
import com.gestiontraza.app.lectores.SesionLector
import com.gestiontraza.app.lectores.Xrs2Sesiones
import com.gestiontraza.app.lectores.abrirRfcomm
import com.gestiontraza.app.ui.importar.guardarSesionImportada
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lista las sesiones guardadas en el lector conectado por Bluetooth y permite
 * importarlas a la app, como hace la app oficial del fabricante.
 *  - Tru-Test XRS2 / SRS2: protocolo SCP, probado contra un XRS2 real.
 *  - Gallagher HR: protocolo ADI (HTTP sobre Bluetooth), SIN probar con un lector real.
 */
@SuppressLint("MissingPermission")
class ImportarLectorFragment : Fragment() {

    private enum class Marca { TRUTEST, GALLAGHER }

    private var _binding: FragmentImportarLectorBinding? = null
    private val binding get() = _binding!!
    private val btVm: BtConnectionViewModel by activityViewModels()

    private var scp: ScpClient? = null
    private var socketAdi: BluetoothSocket? = null
    private var driver: LectorSesiones? = null
    private var ocupado = false
    private val registro = RegistroDiagnostico()
    private var descripcionLector = ""

    // La conexión y la lista viven en el fragmento, no en su vista: al ir a "Sesiones
    // guardadas" y volver, la pantalla se recrea pero el lector sigue conectado.
    private var textoEstado: String? = null
    private var textoSubtitulo: String? = null

    private val adapter = SesionLectorAdapter { importar(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarLectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.recyclerSesionesLector.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSesionesLector.adapter = adapter
        textoEstado?.let { binding.tvEstado.text = it }
        textoSubtitulo?.let { binding.tvSubtitulo.text = it }
        binding.btnElegirLector.setOnClickListener { elegirLector() }
        binding.btnCompartirRegistro.setOnClickListener {
            registro.compartir(requireContext(), descripcionLector)
        }
        binding.btnSesionesGuardadas.setOnClickListener {
            findNavController().navigate(ImportarLectorFragmentDirections.actionImportarLectorToSesiones())
        }
    }

    private fun elegirLector() {
        if (ocupado) return
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
            .setItems(nombres) { _, idx -> elegirMarca(dispositivos[idx]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Por el nombre Bluetooth se deduce la marca; si no se reconoce, se pregunta. */
    private fun elegirMarca(device: android.bluetooth.BluetoothDevice) {
        val nombre = device.name ?: device.address
        val u = nombre.uppercase()
        when {
            GallagherSesiones.esGallagher(nombre) -> conectarYListar(device, Marca.GALLAGHER)
            u.contains("XRS") || u.contains("SRS") || u.contains("TRU") -> conectarYListar(device, Marca.TRUTEST)
            else -> AlertDialog.Builder(requireContext())
                .setTitle("¿De qué marca es \"$nombre\"?")
                .setItems(arrayOf("Tru-Test (XRS2 / SRS2)", "Gallagher (HR4 / HR5)")) { _, idx ->
                    conectarYListar(device, if (idx == 0) Marca.TRUTEST else Marca.GALLAGHER)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun conectarYListar(device: android.bluetooth.BluetoothDevice, marca: Marca) {
        ocupado = true
        adapter.submitList(emptyList())
        // El lector atiende una sola conexión RFCOMM: se suelta la de lectura en vivo.
        btVm.desconectar()
        cerrarConexion()
        registro.limpiar()
        val nombre = device.name ?: device.address
        descripcionLector = "Lector: $nombre (${device.address}), marca elegida: $marca"
        registro.agregar(descripcionLector)

        binding.btnElegirLector.isEnabled = false
        subtitulo(nombre)
        estado("Conectando con $nombre…")
        binding.progreso.visibility = View.VISIBLE
        binding.progreso.isIndeterminate = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bt = requireContext().getSystemService(BluetoothManager::class.java)?.adapter
                val d: LectorSesiones? = when (marca) {
                    Marca.TRUTEST -> {
                        val cliente = ScpClient().also { scp = it }
                        cliente.registrar = { registro.agregar(it) }
                        cliente.conectar(bt, device)
                        estado("Identificando el lector…")
                        val (modelo, drv) = Xrs2Sesiones.detectar(cliente)
                        if (drv == null) {
                            estado("Lector \"$modelo\" todavía no soportado. Por ahora funciona con Tru-Test XRS2 y SRS2.")
                        }
                        drv
                    }
                    Marca.GALLAGHER -> {
                        val sock = abrirRfcomm(bt, device)
                        socketAdi = sock
                        GallagherSesiones(
                            AdiHttp(sock.inputStream, sock.outputStream) { registro.agregar(it) },
                            "Gallagher"
                        )
                    }
                }
                driver = d
                if (d == null) return@launch
                subtitulo("${d.familia} · $nombre")
                estado("Leyendo la lista de sesiones…")
                binding.progreso.isIndeterminate = false
                val sesiones = d.listarSesiones { i, total ->
                    binding.progreso.max = total
                    binding.progreso.progress = i
                }
                adapter.submitList(sesiones)
                estado(
                    if (sesiones.isEmpty()) "El lector no tiene sesiones guardadas."
                    else "${sesiones.size} sesión(es) en el lector. Tocá \"Importar\" en las que quieras traer."
                )
            } catch (e: Exception) {
                registro.agregar("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                cerrarConexion()
                estado("No se pudo leer el lector: ${e.message}")
            } finally {
                ocupado = false
                _binding?.let {
                    it.btnElegirLector.isEnabled = true
                    it.progreso.visibility = View.GONE
                }
            }
        }
    }

    private val conectado: Boolean
        get() = scp?.estaConectado == true || socketAdi?.isConnected == true

    /** Cierra la conexión actual. Con Gallagher antes se apaga el modo de transferencia. */
    private fun cerrarConexion() {
        val c = scp
        val sock = socketAdi
        val d = driver
        scp = null
        socketAdi = null
        driver = null
        c?.cerrar()
        if (sock != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { d?.finalizar() }
                runCatching { sock.close() }
            }
        }
    }

    private fun importar(sesion: SesionLector) {
        val d = driver ?: return
        if (ocupado) return
        if (!conectado) {
            estado("Se perdió la conexión con el lector. Elegilo de nuevo.")
            return
        }
        ocupado = true
        binding.btnElegirLector.isEnabled = false
        binding.progreso.visibility = View.VISIBLE
        binding.progreso.isIndeterminate = true
        estado("Importando \"${sesion.nombre}\"…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val caravanas = d.descargarSesion(sesion)
                if (caravanas.isEmpty()) {
                    estado("La sesión \"${sesion.nombre}\" no tiene caravanas.")
                } else {
                    estado("Descargada \"${sesion.nombre}\": ${caravanas.size} caravanas. Todavía sin guardar.")
                    // Avisa si ya está importada y deja cambiarle el nombre antes de guardar.
                    guardarSesionImportada(
                        SessionFileStore(requireContext()), sesion.nombre, d.familia, caravanas
                    ) { nombre ->
                        estado("Importada \"$nombre\": ${caravanas.size} caravanas. Ya está en \"Ver sesiones\".")
                        android.widget.Toast.makeText(
                            requireContext(), "Sesión importada (${caravanas.size} caravanas)", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                registro.agregar("ERROR al importar: ${e.javaClass.simpleName}: ${e.message}")
                estado("No se pudo importar \"${sesion.nombre}\": ${e.message}")
            } finally {
                ocupado = false
                _binding?.let {
                    it.btnElegirLector.isEnabled = true
                    it.progreso.visibility = View.GONE
                }
            }
        }
    }

    private fun estado(texto: String) {
        textoEstado = texto
        _binding?.tvEstado?.text = texto
        mostrarBotonDiagnostico()
    }

    /**
     * El registro de diagnóstico (todo lo que se habló con el lector) se puede compartir
     * solo en las compilaciones de prueba; el APK de release no muestra el botón.
     */
    private fun mostrarBotonDiagnostico() {
        val ctx = context ?: return
        val esDebug = (ctx.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        _binding?.btnCompartirRegistro?.visibility =
            if (esDebug && registro.hayDatos) View.VISIBLE else View.GONE
    }

    private fun subtitulo(texto: String) {
        textoSubtitulo = texto
        _binding?.tvSubtitulo?.text = texto
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Solo se suelta el lector cuando la pantalla se cierra de verdad.
    override fun onDestroy() {
        super.onDestroy()
        cerrarConexion()
    }
}

private class SesionLectorAdapter(
    private val onImportar: (SesionLector) -> Unit
) : ListAdapter<SesionLector, SesionLectorAdapter.VH>(DIFF) {

    class VH(val binding: ItemSesionLectorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSesionLectorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        holder.binding.tvNombre.text = s.nombre
        holder.binding.tvDetalle.text =
            "${s.fecha.ifEmpty { "sin fecha" }} · ${s.registros} caravana${if (s.registros != 1) "s" else ""}"
        holder.binding.btnImportar.setOnClickListener { onImportar(s) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SesionLector>() {
            override fun areItemsTheSame(a: SesionLector, b: SesionLector) = a.id == b.id
            override fun areContentsTheSame(a: SesionLector, b: SesionLector) = a == b
        }
    }
}
