package com.example

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanResultItem(
    val code: String,
    val format: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _webUrl = MutableStateFlow("file:///android_asset/www/index.html")
    val webUrl: StateFlow<String> = _webUrl.asStateFlow()

    private val _isNativeScannerOpen = MutableStateFlow(false)
    val isNativeScannerOpen: StateFlow<Boolean> = _isNativeScannerOpen.asStateFlow()

    private val _scanHistory = MutableStateFlow<List<ScanResultItem>>(emptyList())
    val scanHistory: StateFlow<List<ScanResultItem>> = _scanHistory.asStateFlow()

    private val _lastScannedBarcode = MutableStateFlow<String?>(null)
    val lastScannedBarcode: StateFlow<String?> = _lastScannedBarcode.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) { }
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        _hasCameraPermission.value = granted
    }

    fun setWebUrl(url: String) {
        _webUrl.value = url
    }

    fun openNativeScanner() {
        _isNativeScannerOpen.value = true
    }

    fun closeNativeScanner() {
        _isNativeScannerOpen.value = false
    }

    fun onBarcodeScanned(code: String, format: String) {
        _lastScannedBarcode.value = code
        val newItem = ScanResultItem(code = code, format = format)
        _scanHistory.value = listOf(newItem) + _scanHistory.value.take(49)
        playSuccessFeedback()
    }

    fun playSuccessFeedback() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            val context = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(60)
                }
            }
        } catch (_: Exception) { }
    }

    fun clearHistory() {
        _scanHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        toneGenerator?.release()
        toneGenerator = null
    }
}
