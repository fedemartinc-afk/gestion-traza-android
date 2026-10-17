package com.gestiontraza.app.ui.importar.sesiones

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputFilter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.navArgs
import com.gestiontraza.app.data.UnirSesiones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val args: ImportarSesionesFragmentArgs by navArgs()

    /** Rutas de las sesiones elegidas en el modo "Unir sesiones". */
    private val elegidas = linkedSetOf<String>()

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
            onRenombrar = { sesion -> renombrarSesion(sesion, store) { refrescar() } },
            onAlternar = { sesion -> alternar(sesion) }
        )
        binding.recyclerSesiones.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSesiones.adapter = adapter

        binding.btnUnirSesiones.setOnClickListener { entrarModoUnir() }
        binding.btnCancelarUnir.setOnClickListener { salirModoUnir() }
        binding.btnConfirmarUnir.setOnClickListener { confirmarUnir() }
        // Desde "Ver / importar sesiones" → "Unir sesiones" entra directo a elegir.
        if (saved == null && args.modoUnir) entrarModoUnir()
    }

    // ── Unir sesiones ────────────────────────────────────────────────────

    private fun entrarModoUnir() {
        if (store.listar().size < 2) {
            Toast.makeText(requireContext(), "Necesitás al menos 2 sesiones guardadas para unirlas", Toast.LENGTH_LONG).show()
            return
        }
        elegidas.clear()
        adapter.seleccionadas = emptySet()
        adapter.modoUnir = true
        binding.barraUnir.visibility = View.VISIBLE
        binding.btnUnirSesiones.visibility = View.GONE
        actualizarSeleccion()
    }

    private fun salirModoUnir() {
        elegidas.clear()
        adapter.seleccionadas = emptySet()
        adapter.modoUnir = false
        binding.barraUnir.visibility = View.GONE
        refrescar()
    }

    private fun alternar(sesion: SesionGuardada) {
        val ruta = sesion.archivo.absolutePath
        if (!elegidas.add(ruta)) elegidas.remove(ruta)
        adapter.seleccionadas = elegidas.toSet()
        actualizarSeleccion()
    }

    private fun actualizarSeleccion() {
        val sesiones = store.listar().filter { it.archivo.absolutePath in elegidas }
        val lineas = sesiones.sumOf { it.lineas }
        binding.tvSeleccion.text = when (sesiones.size) {
            0 -> "Tocá las sesiones que querés unir"
            else -> "${sesiones.size} sesión${if (sesiones.size != 1) "es" else ""} elegida${if (sesiones.size != 1) "s" else ""} · $lineas línea${if (lineas != 1) "s" else ""}"
        }
        binding.btnConfirmarUnir.text = "Unir"
        binding.btnConfirmarUnir.isEnabled = sesiones.size >= 2
    }

    /** Pide el nombre de la sesión unida (y si se borran las originales) y la genera. */
    private fun confirmarUnir() {
        // Se unen de la más antigua a la más nueva: si una caravana está en varias, manda la primera.
        val sesiones = store.listar().filter { it.archivo.absolutePath in elegidas }.sortedBy { it.fecha }
        if (sesiones.size < 2) return

        val ctx = requireContext()
        val et = EditText(ctx).apply {
            hint = "Nombre de la sesión unida"
            setText("Unión " + SimpleDateFormat("dd-MM HH:mm", Locale("es", "AR")).format(Date()))
            setSelectAllOnFocus(true)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        val cbBorrar = CheckBox(ctx).apply { text = "Eliminar las ${sesiones.size} sesiones originales" }
        val contenido = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
            addView(et)
            addView(cbBorrar)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Unir ${sesiones.size} sesiones")
            .setView(contenido)
            .setPositiveButton("Unir") { _, _ ->
                val nombre = et.text?.toString()?.trim().orEmpty().ifEmpty { "Unión ${System.currentTimeMillis()}" }
                unir(sesiones, nombre, cbBorrar.isChecked)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun unir(sesiones: List<SesionGuardada>, nombre: String, borrarOriginales: Boolean) {
        val resultado = UnirSesiones.unir(sesiones.map { store.leer(it.archivo) })
        if (resultado.lineas.isEmpty()) {
            Toast.makeText(requireContext(), "Las sesiones elegidas no tienen caravanas", Toast.LENGTH_LONG).show()
            return
        }
        store.guardar(nombre, "Unida", resultado.lineas)
        if (borrarOriginales) sesiones.forEach { store.eliminar(it.archivo) }

        val aviso = buildString {
            append("Sesión \"$nombre\" creada: ${resultado.lineas.size} caravanas")
            if (resultado.repetidas > 0) append(", se quitaron ${resultado.repetidas} repetida${if (resultado.repetidas != 1) "s" else ""}")
            if (resultado.sexosDistintos > 0) {
                append(". ${resultado.sexosDistintos} caravana${if (resultado.sexosDistintos != 1) "s tenían" else " tenía"} " +
                    "sexo distinto entre sesiones: se conservó el de la sesión más antigua")
            }
        }
        Toast.makeText(requireContext(), aviso, Toast.LENGTH_LONG).show()
        salirModoUnir()
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun refrescar() {
        val sesiones = store.listar()
        adapter.submitList(sesiones)
        binding.btnUnirSesiones.visibility =
            if (sesiones.size >= 2 && !adapter.modoUnir) View.VISIBLE else View.GONE
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
