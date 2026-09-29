package com.gestiontraza.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Baja el APK de la actualización con el DownloadManager del sistema (misma barra
 *  de progreso que usa cualquier descarga de Android) y devuelve el Uri del archivo
 *  ya descargado, listo para pasarle a un Intent.ACTION_VIEW que lo instale. */
object ApkDownloader {

    suspend fun descargar(
        context: Context,
        url: String,
        versionName: String,
        onProgreso: (Int) -> Unit
    ): Uri? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Gestión Traza $versionName")
            .setDescription("Descargando actualización…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "GestionTraza-$versionName.apk")
            .setMimeType("application/vnd.android.package-archive")
        val id = dm.enqueue(request)

        val exito = withContext(Dispatchers.IO) {
            var terminado = false
            var ok = false
            while (!terminado) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) {
                        terminado = true
                    } else {
                        when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> { terminado = true; ok = true }
                            DownloadManager.STATUS_FAILED -> terminado = true
                            DownloadManager.STATUS_RUNNING -> {
                                val bajado = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                if (total > 0) withContext(Dispatchers.Main) { onProgreso((bajado * 100 / total).toInt()) }
                            }
                        }
                    }
                }
                if (!terminado) delay(300)
            }
            ok
        }
        return if (exito) dm.getUriForDownloadedFile(id) else null
    }
}
