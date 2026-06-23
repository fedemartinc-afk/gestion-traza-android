package com.gestiontraza.app.ui.reading

import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtManager
import com.gestiontraza.app.databinding.FragmentReadingBinding
import org.json.JSONArray

class ReadingFragment : Fragment() {

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val vm: ReadingViewModel by viewModels()

    private lateinit var adapter: CaravanaAdapter
    private lateinit var btManager: BtManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        adapter = CaravanaAdapter()
        binding.recyclerCaravanas.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCaravanas.adapter = adapter

        vm.caravanas.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val validas = vm.totalValidas()
            val dups = list.count { it.esDuplicado }
            binding.tvContador.text = if (dups > 0)
                "Leídas: $validas  •  Duplicadas: $dups"
            else
                "Leídas: $validas"
            binding.recyclerCaravanas.scrollToPosition(0)
        }

        // Modo HID: EditText oculto que recibe input del lector (emulación teclado)
        setupHidInput()

        // Entrada manual visible
        setupManualInput()

        // Modo SPP: recibir líneas del BtManager
        val btAdapter = requireContext().getSystemService(BluetoothManager::class.java)?.adapter
        btManager = BtManager(btAdapter)
        btManager.listener = object : BtManager.Listener {
            override fun onConnected(deviceName: String) {
                binding.tvHidInfo.text = "Lector SPP conectado: $deviceName"
            }
            override fun onLine(line: String) { vm.addRaw(line) }
            override fun onDisconnected() {
                binding.tvHidInfo.text = "Modo HID activo — el lector escribe directo en la app"
            }
            override fun onError(msg: String) { binding.tvHidInfo.text = msg }
        }

        binding.btnFinalizar.setOnClickListener {
            val validas = vm.validas()
            if (validas.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No hay caravanas válidas", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val duplicadas = vm.duplicadas()
            if (duplicadas.isNotEmpty()) {
                val lista = duplicadas.joinToString("\n• ", prefix = "• ")
                val msg = if (duplicadas.size == 1)
                    "La siguiente caravana está duplicada y no se enviará:\n\n$lista\n\n¿Deseás continuar o volver a revisar?"
                else
                    "Las siguientes ${duplicadas.size} caravanas están duplicadas y no se enviarán:\n\n$lista\n\n¿Deseás continuar o volver a revisar?"
                AlertDialog.Builder(requireContext())
                    .setTitle("Caravanas duplicadas")
                    .setMessage(msg)
                    .setPositiveButton("Continuar") { _, _ ->
                        val json = JSONArray(validas).toString()
                        findNavController().navigate(ReadingFragmentDirections.actionReadingToSend(json))
                    }
                    .setNegativeButton("Revisar", null)
                    .show()
            } else {
                val json = JSONArray(validas).toString()
                findNavController().navigate(ReadingFragmentDirections.actionReadingToSend(json))
            }
        }
    }

    private fun setupManualInput() {
        binding.btnAgregar.setOnClickListener {
            val codigo = binding.etManual.text?.toString()?.trim() ?: ""
            if (codigo.isEmpty()) return@setOnClickListener
            vm.addRaw(codigo)
            binding.etManual.setText("")
            // Ocultar teclado y devolver foco al HID
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(binding.etManual.windowToken, 0)
        }

        binding.etManual.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnAgregar.performClick()
                true
            } else false
        }
    }

    private fun setupHidInput() {
        val et = binding.etHidInput
        et.requestFocus()

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                if (text.contains('\n') || text.length >= 15) {
                    text.split('\n').forEach { line ->
                        val clean = line.trim()
                        if (clean.isNotEmpty()) vm.addRaw(clean)
                    }
                    et.setText("")
                }
            }
        })

        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val text = et.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) { vm.addRaw(text); et.setText("") }
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        binding.etHidInput.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        btManager.disconnect()
        _binding = null
    }
}
