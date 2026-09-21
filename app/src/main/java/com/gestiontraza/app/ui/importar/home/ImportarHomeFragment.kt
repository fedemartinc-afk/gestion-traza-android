package com.gestiontraza.app.ui.importar.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.data.ReaderTips
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.data.XlsxReader
import com.gestiontraza.app.databinding.FragmentImportarHomeBinding
import com.gestiontraza.app.ui.importar.guardarSesionImportada
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PushbackInputStream

/**
 * Punto de entrada de "Importar sesiones": conectar por Bluetooth SPP a un
 * lector RFID (AS420, Tru-Test, Gallagher, etc.) o importar un archivo de
 * texto con caravanas ya volcado por otro medio (USB/tarjeta a una PC).
 */
class ImportarHomeFragment : Fragment() {

    private var _binding: FragmentImportarHomeBinding? = null
    private val binding get() = _binding!!

    private val importarLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) procesarArchivoImportado(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportarHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnConectarBt.setOnClickListener {
            findNavController().navigate(ImportarHomeFragmentDirections.actionImportarHomeToConectar())
        }
        binding.btnSesionesLector.setOnClickListener {
            findNavController().navigate(ImportarHomeFragmentDirections.actionImportarHomeToLector())
        }
        binding.tvTips.setOnClickListener { mostrarTips() }

        binding.btnImportarArchivo.setOnClickListener {
            importarLauncher.launch(arrayOf(
                "text/*", "text/csv", "text/plain", "text/comma-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            ))
        }

        binding.btnVerSesiones.setOnClickListener {
            findNavController().navigate(ImportarHomeFragmentDirections.actionImportarHomeToSesiones())
        }
    }

    private fun mostrarTips() {
        val tips = ReaderTips.tips.joinToString("\n\n") { "${it.marca}:\n${it.instrucciones}" }
        AlertDialog.Builder(requireContext())
            .setTitle("Configuración por marca")
            .setMessage(tips)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun procesarArchivoImportado(uri: android.net.Uri) {
        var esXlsAntiguo = false
        val lineas = try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                // El formato real se detecta por los primeros bytes del archivo, no
                // por la extensión ni el MIME type que reporta el picker — no son
                // confiables (algunos exploradores de archivos marcan cualquier CSV
                // como "application/vnd.ms-excel").
                val pushback = PushbackInputStream(input, 8)
                val cabecera = ByteArray(8)
                val leidos = pushback.read(cabecera).coerceAtLeast(0)
                if (leidos > 0) pushback.unread(cabecera, 0, leidos)
                when {
                    // .xlsx es un ZIP: firma "PK" + 0x03/0x05/0x07.
                    leidos >= 4 && cabecera[0] == 0x50.toByte() && cabecera[1] == 0x4B.toByte() ->
                        XlsxReader.leerCeldas(pushback)
                    // .xls antiguo (binario OLE, pre-2007): firma D0 CF 11 E0.
                    leidos >= 4 && cabecera[0] == 0xD0.toByte() && cabecera[1] == 0xCF.toByte() &&
                        cabecera[2] == 0x11.toByte() && cabecera[3] == 0xE0.toByte() -> {
                        esXlsAntiguo = true
                        emptyList()
                    }
                    else ->
                        BufferedReader(InputStreamReader(pushback)).readLines().filter { it.isNotBlank() }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            showToast("No se pudo leer el archivo: ${e.message}")
            return
        }

        if (esXlsAntiguo) {
            showToast("Los archivos .xls antiguos (anteriores a Excel 2007) no son compatibles todavía. Abrilo en Excel/Sheets y guardalo como .xlsx o .csv.")
            return
        }

        if (lineas.isEmpty()) {
            showToast("El archivo está vacío o no se encontraron datos")
            return
        }

        pedirNombreYGuardar(lineas)
    }

    private fun pedirNombreYGuardar(lineas: List<String>) {
        guardarSesionImportada(SessionFileStore(requireContext()), "", "Archivo importado", lineas, "líneas") {
            showToast("Sesión guardada")
            findNavController().navigate(ImportarHomeFragmentDirections.actionImportarHomeToSesiones())
        }
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
