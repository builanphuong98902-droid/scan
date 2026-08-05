package com.example

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
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
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
                    BarcodeFormat.ITF,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.PDF_417
                )
            )
            put(DecodeHintType.TRY_HARDER, true)
        }
        setHints(hints)
    }

    private var lastScannedCode = ""
    private var lastScannedTimestamp = 0L
    private val debounceMs = 1000L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val yPlane = mediaImage.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val rawWidth = imageProxy.width
            val rawHeight = imageProxy.height

            // 1. Extract exact Y bytes handling rowStride padding
            val yData: ByteArray
            if (rowStride == rawWidth) {
                yData = ByteArray(yBuffer.remaining())
                yBuffer.get(yData)
            } else {
                yData = ByteArray(rawWidth * rawHeight)
                val rowBuffer = ByteArray(rowStride)
                val limit = yBuffer.remaining()
                for (i in 0 until rawHeight) {
                    val pos = i * rowStride
                    if (pos >= limit) break
                    yBuffer.position(pos)
                    val bytesToRead = minOf(rowStride, limit - pos)
                    yBuffer.get(rowBuffer, 0, bytesToRead)
                    val copyLen = minOf(rawWidth, bytesToRead)
                    System.arraycopy(rowBuffer, 0, yData, i * rawWidth, copyLen)
                }
            }

            // 2. Rotate YUV data according to sensor orientation so ZXing receives a properly aligned image
            val rotatedData: ByteArray
            val width: Int
            val height: Int

            when (rotationDegrees) {
                90 -> {
                    rotatedData = rotateYUV90(yData, rawWidth, rawHeight)
                    width = rawHeight
                    height = rawWidth
                }
                180 -> {
                    rotatedData = rotateYUV180(yData, rawWidth, rawHeight)
                    width = rawWidth
                    height = rawHeight
                }
                270 -> {
                    rotatedData = rotateYUV270(yData, rawWidth, rawHeight)
                    width = rawHeight
                    height = rawWidth
                }
                else -> {
                    rotatedData = yData
                    width = rawWidth
                    height = rawHeight
                }
            }

            val source = PlanarYUVLuminanceSource(
                rotatedData,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )

            // Try fast GlobalHistogramBinarizer first (optimal for 1D barcodes)
            var result: Result? = null
            try {
                result = reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
            } catch (_: NotFoundException) {
                reader.reset()
                // Fallback to HybridBinarizer if GlobalHistogram missed it
                try {
                    result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                } catch (_: NotFoundException) { }
            }

            if (result != null) {
                val code = result.text.trim()
                val format = result.barcodeFormat.name
                val now = System.currentTimeMillis()

                if (code.isNotEmpty() && (code != lastScannedCode || now - lastScannedTimestamp > debounceMs)) {
                    lastScannedCode = code
                    lastScannedTimestamp = now
                    Log.d("NativeZXing", "Scanned: $code ($format)")
                    onBarcodeScanned(code, format)
                }
            }
        } catch (e: Exception) {
            Log.e("NativeZXing", "Error analyzing frame", e)
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    private fun rotateYUV90(data: ByteArray, imageWidth: Int, imageHeight: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in 0 until imageWidth) {
            for (y in imageHeight - 1 downTo 0) {
                rotated[i++] = data[y * imageWidth + x]
            }
        }
        return rotated
    }

    private fun rotateYUV180(data: ByteArray, imageWidth: Int, imageHeight: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var i = 0
        for (j in data.size - 1 downTo 0) {
            rotated[i++] = data[j]
        }
        return rotated
    }

    private fun rotateYUV270(data: ByteArray, imageWidth: Int, imageHeight: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in imageWidth - 1 downTo 0) {
            for (y in 0 until imageHeight) {
                rotated[i++] = data[y * imageWidth + x]
            }
        }
        return rotated
    }
}
