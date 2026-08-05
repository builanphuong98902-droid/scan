package com.example

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

class NativeBarcodeScanner(
    private val onBarcodeScanned: (code: String, format: String, decodeMs: Long) -> Unit
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
                    BarcodeFormat.CODE_93,
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
    private val debounceMs = 800L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
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

            // 1. Extract Y plane bytes safely handling rowStride padding
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

            // 2. Rotate YUV data according to sensor orientation
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

            val fullSource = PlanarYUVLuminanceSource(
                rotatedData,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )

            var result: Result? = null

            // Pass 1: Standard HybridBinarizer (Fastest & best for clean/normal codes)
            result = tryDecode(fullSource, isHybrid = true)

            // Pass 2: GlobalHistogramBinarizer (Best for 1D codes with non-uniform lighting)
            if (result == null) {
                result = tryDecode(fullSource, isHybrid = false)
            }

            // Pass 3: Inverted Luminance (Best for white-on-dark, high reflectivity, or inverse codes)
            if (result == null) {
                val invertedSource = fullSource.invert()
                result = tryDecode(invertedSource, isHybrid = true) ?: tryDecode(invertedSource, isHybrid = false)
            }

            // Pass 4: Central ROI Zoom & Crop (50% Center Crop - Best for small/far/fine-line barcodes)
            if (result == null) {
                val cropW = width / 2
                val cropH = height / 2
                val cropL = width / 4
                val cropT = height / 4
                if (cropW > 100 && cropH > 100) {
                    val croppedSource = fullSource.crop(cropL, cropT, cropW, cropH)
                    result = tryDecode(croppedSource, isHybrid = true) ?: tryDecode(croppedSource, isHybrid = false)
                }
            }

            // Pass 5: Extreme Dynamic Contrast Stretching (Best for faded thermal print, low contrast, glare)
            if (result == null) {
                val stretchedData = stretchContrast(rotatedData)
                if (stretchedData != null) {
                    val stretchedSource = PlanarYUVLuminanceSource(
                        stretchedData,
                        width,
                        height,
                        0,
                        0,
                        width,
                        height,
                        false
                    )
                    result = tryDecode(stretchedSource, isHybrid = true) ?: tryDecode(stretchedSource, isHybrid = false)
                }
            }

            if (result != null) {
                val code = result.text.trim()
                val format = result.barcodeFormat.name
                val now = System.currentTimeMillis()
                val decodeMs = now - startTime

                if (code.isNotEmpty() && (code != lastScannedCode || now - lastScannedTimestamp > debounceMs)) {
                    lastScannedCode = code
                    lastScannedTimestamp = now
                    Log.d("NativeZXing", "Decoded in ${decodeMs}ms: $code ($format)")
                    onBarcodeScanned(code, format, decodeMs)
                }
            }
        } catch (e: Exception) {
            Log.e("NativeZXing", "Error analyzing frame", e)
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    private fun tryDecode(source: LuminanceSource, isHybrid: Boolean): Result? {
        return try {
            val binarizer = if (isHybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
            reader.decodeWithState(BinaryBitmap(binarizer))
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun stretchContrast(data: ByteArray): ByteArray? {
        var minVal = 255
        var maxVal = 0
        val sampleStep = maxOf(1, data.size / 2000) // fast sub-sample min/max calculation

        for (i in 0 until data.size step sampleStep) {
            val v = data[i].toInt() and 0xFF
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = maxVal - minVal
        // Only apply contrast stretch if image is low-contrast (range < 160)
        if (range in 20..165) {
            val stretched = ByteArray(data.size)
            val scale = 255.0f / range
            for (i in data.indices) {
                val v = (data[i].toInt() and 0xFF) - minVal
                val newV = (v * scale).toInt().coerceIn(0, 255)
                stretched[i] = newV.toByte()
            }
            return stretched
        }
        return null
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
