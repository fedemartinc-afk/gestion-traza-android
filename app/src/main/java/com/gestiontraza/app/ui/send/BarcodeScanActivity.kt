package com.gestiontraza.app.ui.send

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Size
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.camera.core.ExperimentalGetImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BarcodeScanActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var found = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val previewView = PreviewView(this)
        setContentView(previewView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()

            analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                if (found) { imageProxy.close(); return@setAnalyzer }

                @ExperimentalGetImage
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull()?.rawValue?.let { value ->
                                if (!found) {
                                    found = true
                                    val intent = Intent().putExtra(EXTRA_CODE, value)
                                    setResult(Activity.RESULT_OK, intent)
                                    finish()
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val EXTRA_CODE = "barcode_result"

        fun newIntent(context: Context) = Intent(context, BarcodeScanActivity::class.java)

        fun getResultCode(result: ActivityResult): String? =
            result.data?.getStringExtra(EXTRA_CODE)
    }
}
