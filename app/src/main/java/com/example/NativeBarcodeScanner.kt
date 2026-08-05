package com.example

import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.EnumMap

class NativeBarcodeScanner(
    private val onBarcodeScanned: (code: String, format: String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(
                DecodeHintType.POSSIBLE_FORMATS,
                listOf(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.ITF,
                    BarcodeFormat.PDF_417
                )
            )
            put(DecodeHintType.TRY_HARDER, true)
        }
        setHints(hints)
    }

    private var lastScannedCode = ""
    private var lastScannedTimestamp = 0L
    private val debounceMs = 1200L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val buffer = mediaImage.planes[0].buffer
        val data = toByteArray(buffer)
        val width = mediaImage.width
        val height = mediaImage.height

        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )

        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            val code = result.text.trim()
            val format = result.barcodeFormat.name
            val now = System.currentTimeMillis()

            if (code.isNotEmpty() && (code != lastScannedCode || now - lastScannedTimestamp > debounceMs)) {
                lastScannedCode = code
                lastScannedTimestamp = now
                Log.d("NativeZXing", "Scanned: $code ($format)")
                onBarcodeScanned(code, format)
            }
        } catch (_: NotFoundException) {
            // No barcode found in frame
        } catch (e: Exception) {
            Log.e("NativeZXing", "Error analyzing frame", e)
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    private fun toByteArray(buffer: ByteBuffer): ByteArray {
        buffer.rewind()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }
}
