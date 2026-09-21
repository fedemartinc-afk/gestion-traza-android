package com.gestiontraza.app.lectores

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Abre un socket RFCOMM/SPP contra el lector. Algunos lectores no completan
 * bien el handshake del socket "seguro", así que se prueban tres variantes.
 * Lanza IOException si ninguna conecta.
 */
@SuppressLint("MissingPermission")
internal suspend fun abrirRfcomm(adapter: BluetoothAdapter?, device: BluetoothDevice): BluetoothSocket =
    withContext(Dispatchers.IO) {
        adapter?.cancelDiscovery()
        var ultimo: Exception? = null
        for (intento in listOf("segura", "insegura", "canal1")) {
            var sock: BluetoothSocket? = null
            try {
                val nuevo = when (intento) {
                    "segura"   -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                    "insegura" -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    else       -> device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                }
                sock = nuevo
                nuevo.connect()
                return@withContext nuevo
            } catch (e: Exception) {
                Log.e("BtRfcomm", "connect error ($intento)", e)
                ultimo = e
                runCatching { sock?.close() }
            }
        }
        throw IOException("No se pudo conectar con el lector: ${ultimo?.message}")
    }
