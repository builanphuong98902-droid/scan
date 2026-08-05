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

            // 1. Crucial: Rewind buffer before reading
            yBuffer.rewind()
            val yData = ByteArray(rawWidth * rawHeight)

            if (rowStride == rawWidth) {
                val toRead = minOf(yBuffer.remaining(), yData.size)
                yBuffer.get(yData, 0, toRead)
            } else {
                val rowBuffer = ByteArray(rowStride)
                val totalRemaining = yBuffer.remaining()
                for (i in 0 until rawHeight) {
                    val pos = i * rowStride
                    if (pos >= totalRemaining) break
                    yBuffer.position(pos)
                    val bytesToRead = minOf(rowStride, totalRemaining - pos)
                    yBuffer.get(rowBuffer, 0, bytesToRead)
                    val copyLen = minOf(rawWidth, bytesToRead)
                    System.arraycopy(rowBuffer, 0, yData, i * rawWidth, copyLen)
                }
            }

            // 2. Prepare rotated YUV data according to camera orientation
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

            var result: Result? = null

            // Pass 1: Standard upright image (GlobalHistogram + Hybrid)
            val uprightSource = PlanarYUVLuminanceSource(
                rotatedData, width, height, 0, 0, width, height, false
            )
            result = tryDecode(uprightSource, isHybrid = false) ?: tryDecode(uprightSource, isHybrid = true)

            // Pass 2: Thermal Print Head Gap Repair (Vertical Min Filter)
            // Repairs broken/faded vertical bars caused by defective thermal print pins
            if (result == null) {
                val healedData = repairThermalPrintHeadGaps(rotatedData, width, height)
                val healedSource = PlanarYUVLuminanceSource(
                    healedData, width, height, 0, 0, width, height, false
                )
                result = tryDecode(healedSource, isHybrid = false) ?: tryDecode(healedSource, isHybrid = true)
            }

            // Pass 3: Center ROI Crop (High-density focus on central barcode region)
            if (result == null) {
                val cropW = (width * 0.7f).toInt()
                val cropH = (height * 0.7f).toInt()
                val cropL = (width - cropW) / 2
                val cropT = (height - cropH) / 2
                if (cropW > 100 && cropH > 100) {
                    val cropSource = uprightSource.crop(cropL, cropT, cropW, cropH)
                    result = tryDecode(cropSource, isHybrid = false) ?: tryDecode(cropSource, isHybrid = true)
                }
            }

            // Pass 4: Adaptive Contrast Stretching & Sharpening for faint/faded thermal paper
            if (result == null) {
                val enhancedData = enhanceFadedThermalContrast(rotatedData, width, height)
                if (enhancedData != null) {
                    val enhancedSource = PlanarYUVLuminanceSource(
                        enhancedData, width, height, 0, 0, width, height, false
                    )
                    result = tryDecode(enhancedSource, isHybrid = false) ?: tryDecode(enhancedSource, isHybrid = true)
                }
            }

            // Pass 5: Inverted Luminance (for white-on-dark or high thermal paper reflectivity)
            if (result == null) {
                val invertedSource = uprightSource.invert()
                result = tryDecode(invertedSource, isHybrid = false) ?: tryDecode(invertedSource, isHybrid = true)
            }

            // Pass 6: Raw sensor orientation (handles orthogonal/rotated barcodes)
            if (result == null && (width != rawWidth || height != rawHeight)) {
                val rawSource = PlanarYUVLuminanceSource(
                    yData, rawWidth, rawHeight, 0, 0, rawWidth, rawHeight, false
                )
                result = tryDecode(rawSource, isHybrid = false) ?: tryDecode(rawSource, isHybrid = true)
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

    private fun repairThermalPrintHeadGaps(data: ByteArray, w: Int, h: Int): ByteArray {
        val output = ByteArray(data.size)
        // Copy top and bottom row directly
        System.arraycopy(data, 0, output, 0, w)
        System.arraycopy(data, (h - 1) * w, output, (h - 1) * w, w)

        // Vertical min filter (dark pixel dilation along vertical barcode line direction)
        // Fixes horizontal white streaks caused by burned/dead thermal print head pins
        for (y in 1 until h - 1) {
            val rowOffset = y * w
            val prevRow = (y - 1) * w
            val nextRow = (y + 1) * w

            for (x in 0 until w) {
                val current = data[rowOffset + x].toInt() and 0xFF
                val top = data[prevRow + x].toInt() and 0xFF
                val bottom = data[nextRow + x].toInt() and 0xFF
                // Darker value (min) wins to connect broken vertical black bars
                val minVal = minOf(current, top, bottom)
                output[rowOffset + x] = minVal.toByte()
            }
        }
        return output
    }

    private fun enhanceFadedThermalContrast(data: ByteArray, w: Int, h: Int): ByteArray? {
        var minVal = 255
        var maxVal = 0
        val sampleStep = maxOf(1, data.size / 3000)

        for (i in 0 until data.size step sampleStep) {
            val v = data[i].toInt() and 0xFF
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = maxVal - minVal
        if (range < 15) return null // Complete darkness or blank paper

        val enhanced = ByteArray(data.size)
        // Gamma correction & high contrast threshold curve for low quality thermal prints
        val scale = 255.0f / range.toFloat()

        for (i in data.indices) {
            val v = (data[i].toInt() and 0xFF) - minVal
            var norm = (v * scale).toInt().coerceIn(0, 255)
            // Sharpen black bars and brighten faded white spaces
            norm = if (norm < 110) {
                (norm * 0.5f).toInt()
            } else {
                minOf(255, (norm * 1.25f).toInt())
            }
            enhanced[i] = norm.toByte()
        }
        return enhanced
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
