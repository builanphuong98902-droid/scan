package com.example

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.webkit.JavascriptInterface

class WebAppInterface(
    private val context: Context,
    private val onTriggerNativeScanner: () -> Unit,
    private val onBarcodeFromJs: (String) -> Unit
) {
    private val tag = "WebAppInterface"

    @JavascriptInterface
    fun scanBarcodeNative() {
        Log.d(tag, "JS requested Native CameraX ZXing Scanner")
        onTriggerNativeScanner()
    }

    @JavascriptInterface
    fun sendBarcodeToNative(barcode: String) {
        Log.d(tag, "Received barcode from Web JS: $barcode")
        onBarcodeFromJs(barcode)
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to trigger vibration", e)
        }
    }

    @JavascriptInterface
    fun getTabletInfo(): String {
        return "SM-T295 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - Hardware Accelerated Native Bridge"
    }

    @JavascriptInterface
    fun logFromWeb(message: String) {
        Log.d("WebConsole", message)
    }

    @JavascriptInterface
    fun isNativeAvailable(): Boolean {
        return true
    }
}
