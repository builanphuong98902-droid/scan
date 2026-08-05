package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var webViewInstance: WebView? = null
    private var webAppInterface: WebAppInterface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = viewModel,
                    onInitWebView = { webView, appInterface ->
                        webViewInstance = webView
                        webAppInterface = appInterface
                    },
                    onReloadWebView = {
                        webViewInstance?.reload()
                    },
                    onLoadUrl = { url ->
                        webViewInstance?.loadUrl(url)
                    },
                    onSendBarcodeToWebView = { code, format ->
                        webAppInterface?.updateScannedBarcode(code, format)
                        dispatchBarcodeToWebView(webViewInstance, code, format)
                    }
                )
            }
        }
    }

    private fun dispatchBarcodeToWebView(webView: WebView?, code: String, format: String) {
        if (webView == null) return
        val jsonCode = JSONObject.quote(code)
        val jsonFormat = JSONObject.quote(format)
        val js = """
            (function() {
                var code = $jsonCode;
                var format = $jsonFormat;
                console.log("Native dispatched barcode to WebView: " + code + " (" + format + ")");
                
                if (typeof window.onNativeBarcodeScanned === 'function') {
                    window.onNativeBarcodeScanned(code, format);
                }
                if (typeof window.handleScannedCode === 'function') {
                    window.handleScannedCode(code, 'Native ' + format);
                }
                try {
                    var event = new CustomEvent('nativeBarcodeScanned', { detail: { barcode: code, format: format } });
                    window.dispatchEvent(event);
                    document.dispatchEvent(event);
                } catch(e) { }
            })();
        """.trimIndent()
        
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onInitWebView: (WebView, WebAppInterface) -> Unit,
    onReloadWebView: () -> Unit,
    onLoadUrl: (String) -> Unit,
    onSendBarcodeToWebView: (code: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val webUrl by viewModel.webUrl.collectAsState()
    val isNativeScannerOpen by viewModel.isNativeScannerOpen.collectAsState()
    val hasCameraPermission by viewModel.hasCameraPermission.collectAsState()

    var urlInputText by remember { mutableStateOf(webUrl) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setCameraPermissionGranted(isGranted)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setCameraPermissionGranted(granted)
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF8FAFC)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Top Control Bar - Clean Light Mode
                Surface(
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // App Title & System Subtitle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2563EB)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = "Logo",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Scanner WebView",
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Tablet SM-T295 Native Bridge",
                                        color = Color(0xFF0284C7),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Action Badges & Buttons
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Camera Status Badge
                                Surface(
                                    color = if (hasCameraPermission) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (hasCameraPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (hasCameraPermission) Color(0xFF15803D) else Color(0xFFB91C1C),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (hasCameraPermission) "Camera OK" else "Thiếu Quyền",
                                            color = if (hasCameraPermission) Color(0xFF15803D) else Color(0xFFB91C1C),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Reload Button
                                IconButton(
                                    onClick = onReloadWebView,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color(0xFFF1F5F9), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reload",
                                        tint = Color(0xFF334155),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Native ZXing Scanner Button
                                Button(
                                    onClick = {
                                        if (!hasCameraPermission) {
                                            permissionLauncher.launch(Manifest.permission.CAMERA)
                                        } else {
                                            viewModel.openNativeScanner()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2563EB)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Quét Native", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // URL Address Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = urlInputText,
                                onValueChange = { urlInputText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    viewModel.setWebUrl(urlInputText)
                                    onLoadUrl(urlInputText)
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFFF8FAFC),
                                    unfocusedContainerColor = Color(0xFFF8FAFC),
                                    focusedBorderColor = Color(0xFF2563EB),
                                    unfocusedBorderColor = Color(0xFFE2E8F0),
                                    focusedTextColor = Color(0xFF0F172A),
                                    unfocusedTextColor = Color(0xFF334155)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    val localUrl = "file:///android_asset/www/index.html"
                                    urlInputText = localUrl
                                    viewModel.setWebUrl(localUrl)
                                    onLoadUrl(localUrl)
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home App",
                                    tint = Color(0xFF1E293B)
                                )
                            }
                        }
                    }
                }

                // Camera Permission Warning Banner
                if (!hasCameraPermission) {
                    Surface(
                        color = Color(0xFF7F1D1D),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color.Yellow
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Ứng dụng cần cấp quyền Camera để thực hiện quét mã vạch trên Tablet SM-T295.",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                            ) {
                                Text("Cấp Quyền", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // WebView Container
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val webAppInterface = WebAppInterface(
                                    context = ctx,
                                    onTriggerNativeScanner = {
                                        viewModel.openNativeScanner()
                                    },
                                    onBarcodeFromJs = { barcode ->
                                        viewModel.onBarcodeScanned(barcode, "Web JS")
                                    }
                                )

                                setupWebView(
                                    webView = this,
                                    webAppInterface = webAppInterface
                                )

                                onInitWebView(this, webAppInterface)
                                loadUrl(webUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Native CameraX ZXing Overlay Screen
            AnimatedVisibility(
                visible = isNativeScannerOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CameraOverlay(
                    onBarcodeScanned = { code, format ->
                        viewModel.onBarcodeScanned(code, format)
                        onSendBarcodeToWebView(code, format)
                        viewModel.closeNativeScanner()
                    },
                    onClose = {
                        viewModel.closeNativeScanner()
                    }
                )
            }
        }
    }
}
