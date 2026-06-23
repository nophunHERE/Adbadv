package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import java.util.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Trigger verification and initialization inside compose
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                val viewModel: AdbHotspotViewModel = viewModel()
                val context = LocalContext.current
                val state by viewModel.state.collectAsState()

                // Register standard permissions check
                val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                }

                var permissionsGranted by remember {
                    mutableStateOf(hasPermissions(context, requiredPermissions))
                }

                // Handle screen keep awake
                val view = LocalView.current
                LaunchedEffect(state.keepScreenOn) {
                    if (state.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view.keepScreenOn = true
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        view.keepScreenOn = false
                    }
                }

                // Refresh state on startup & whenever activity is resumed
                DisposableEffect(Unit) {
                    viewModel.loadPreferences(context)
                    viewModel.refreshStatus(context)
                    
                    // Auto run if setting is active on start
                    if (permissionsGranted && state.autoRunOnLaunch) {
                        viewModel.startLocalOnlyHotspot(context)
                    }

                    onDispose { }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = TerminalBg
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (!permissionsGranted) {
                            PermissionRequiredScreen(
                                requiredPermissions = requiredPermissions,
                                onRequestPermissions = {
                                    requestPermissionLauncher.launch(requiredPermissions)
                                    // re-verify instantly
                                    permissionsGranted = hasPermissions(context, requiredPermissions)
                                }
                            )
                        } else {
                            MainHUDDashboard(
                                viewModel = viewModel,
                                state = state
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh statuses such as IP address and Wireless ADB enabled status when returning to app
        try {
            val mainView = findViewById<android.view.View>(android.R.id.content)
            val context = mainView.context
            val model = (application as? androidx.lifecycle.ViewModelStoreOwner)?.let {
                androidx.lifecycle.ViewModelProvider(it)[AdbHotspotViewModel::class.java]
            }
            model?.refreshStatus(context)
            model?.addManualLog("System state refreshed on focus.")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                it
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun PermissionRequiredScreen(
    requiredPermissions: Array<String>,
    onRequestPermissions: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            // Glowing Terminal Diagnostic Icon
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Permission Security Lock",
                tint = TerminalBrightCyan,
                modifier = Modifier
                    .size(80.dp)
                    .border(2.dp, TerminalBrightCyan, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            )

            Text(
                text = "PERMISSIONS REQUIRED",
                color = TerminalBrightCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "To request programmatically started WiFi networks and configure developer debugging tools without manual typing, the following underlying system capabilities are required:",
                color = TerminalWhite,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, TerminalGray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                requiredPermissions.forEach { permission ->
                    val name = permission.substringAfterLast(".")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info Icon",
                            tint = TerminalBrightGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = name,
                            color = TerminalBrightGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalBrightGreen,
                    contentColor = TerminalBg
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("request_permissions_btn")
            ) {
                Text(
                    text = "AUTHORIZE LINK DISCOVERY",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun MainHUDDashboard(
    viewModel: AdbHotspotViewModel,
    state: AdbHotspotState
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header Status bar
        HeaderHUDModule(state = state, viewModel = viewModel)

        // Local Hotspot Controller Module
        HotspotControllerHUD(state = state, viewModel = viewModel, context = context)

        // Wireless Debugging Module
        WirelessDebuggingHUD(state = state, viewModel = viewModel, context = context)

        // Quick Controls Settings Drawer
        QuickSettingsHUD(state = state, viewModel = viewModel, context = context)

        // Terminal Logging Console Stream
        TerminalConsoleHUD(state = state, viewModel = viewModel)
    }
}

@Composable
fun HeaderHUDModule(
    state: AdbHotspotState,
    viewModel: AdbHotspotViewModel
) {
    val context = LocalContext.current
    
    // Header Panel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface, RoundedCornerShape(12.dp))
            .border(1.dp, TerminalBrightCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Outer Pulse animation circle
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (state.isHotspotActive) TerminalBrightGreen else TerminalGray,
                            androidx.compose.foundation.shape.CircleShape
                        )
                )

                Text(
                    text = "WIRELESS HOST SYSTEM",
                    color = TerminalBrightCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            
            Text(
                text = state.deviceModel.uppercase(Locale.getDefault()),
                color = TerminalWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = {
                viewModel.refreshStatus(context)
                viewModel.addManualLog("Manual system state polling initiated.")
            },
            modifier = Modifier.testTag("sync_state_button")
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh Device IP Status",
                tint = TerminalBrightCyan
            )
        }
    }
}

@Composable
fun HotspotControllerHUD(
    state: AdbHotspotState,
    viewModel: AdbHotspotViewModel,
    context: Context
) {
    // Elegant Terminal Card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface, RoundedCornerShape(12.dp))
            .border(1.dp, TerminalGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Hotspot Transmitter",
                    tint = TerminalBrightGreen,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "1. LOCAL HOTSPOT CONTROLLER",
                    color = TerminalWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Simple status chip
            val statusColor = when (state.hotspotStatus) {
                HotspotStatus.ACTIVE -> TerminalBrightGreen
                HotspotStatus.STARTING -> TerminalAmber
                HotspotStatus.FAILED -> Color.Red
                else -> TerminalGray
            }
            Text(
                text = state.hotspotStatus.name,
                color = statusColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Action Toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (state.isHotspotActive) {
                        viewModel.stopLocalOnlyHotspot()
                    } else {
                        viewModel.startLocalOnlyHotspot(context)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isHotspotActive) Color.Red else TerminalBrightGreen,
                    contentColor = TerminalBg
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("hotspot_toggle_btn")
            ) {
                Icon(
                    imageVector = if (state.isHotspotActive) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (state.isHotspotActive) "STOP AP SESSION" else "LAUNCH HOTSPOT",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = { viewModel.launchTetherSettings(context) },
                modifier = Modifier
                    .background(TerminalSurfaceVariant, RoundedCornerShape(6.dp))
                    .border(1.dp, TerminalGray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .size(44.dp)
                    .testTag("tether_settings_shortcut")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "System Settings",
                    tint = TerminalBrightCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // System diagnostic helper section if failed
        AnimatedVisibility(
            visible = state.hotspotStatus == HotspotStatus.FAILED,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBg, RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        TerminalAmber.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "System Allocation Warning",
                        tint = TerminalAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AP INTERFACE ALLOCATION FAILED",
                        color = TerminalAmber,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "The system's Wi-Fi HAL failed to instantiate a virtual Local-Only Hotspot. This is a standard Android hardware or concurrency limitation (e.g., active client connections or cloud emulator environment lacking Wi-Fi hardware/HAL).",
                    color = TerminalWhite,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Text(
                    text = "💡 WORKAROUND SOLUTIONS:",
                    color = TerminalBrightCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = "• Option A: Tap the gear settings button next to 'Launch' to start your device's standard portable Hotspot manually. (Standard hotspot is 100% compatible with ADB wireless connections).",
                        color = TerminalWhite,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Text(
                        text = "• Option B: Toggle your phone's physical Wi-Fi switch OFF and ON, then try launching again.",
                        color = TerminalWhite,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Text(
                        text = "• Option C: Disable any active Wi-Fi sharing or active standard tethering sessions before launching.",
                        color = TerminalWhite,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Credentials visual section if active
        AnimatedVisibility(
            visible = state.isHotspotActive && state.ssid.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBg, RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        TerminalBrightGreen.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AP CREDENTIALS INSTABLISHED",
                        color = TerminalBrightGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "READY FOR ADB CLIENT",
                        color = TerminalBrightCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // SSID Detail Row
                CredentialDetailRow(
                    label = "SSID (NETWORK)",
                    value = state.ssid,
                    context = context,
                    testTagSuffix = "ssid"
                )

                // Password Detail Row
                CredentialDetailRow(
                    label = "PRE-SHARED KEY (PASS)",
                    value = state.passphrase,
                    context = context,
                    testTagSuffix = "passphrase"
                )

                // Cyber connection instructions + Quick pairing canvas QR placeholder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Futuristic QR scan target box
                    CyberScanTargetIndicator(
                        ssid = state.ssid,
                        passphrase = state.passphrase,
                        modifier = Modifier.size(80.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "QUICK pairing tool: QR contains local system credentials. Scan with secondary device to auto-connect to the Hotspot.",
                            color = TerminalWhite,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CredentialDetailRow(
    label: String,
    value: String,
    context: Context,
    testTagSuffix: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurfaceVariant, RoundedCornerShape(4.dp))
            .border(1.dp, TerminalGray.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = label,
                color = TerminalGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
            Text(
                text = value,
                color = TerminalWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        IconButton(
            onClick = {
                copyToClipboard(context, label, value)
            },
            modifier = Modifier
                .size(24.dp)
                .testTag("copy_${testTagSuffix}_btn")
        ) {
            Icon(
                imageVector = Icons.Default.Edit, // representing copy action generically/cleanly
                contentDescription = "Copy to clipboard",
                tint = TerminalBrightGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun CyberScanTargetIndicator(
    ssid: String,
    passphrase: String,
    modifier: Modifier = Modifier
) {
    // Draw a neat aesthetic tech HUD target grid representation with scan sweep lines
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val sweepProgression by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Canvas(
        modifier = modifier
            .background(TerminalSurfaceVariant, RoundedCornerShape(6.dp))
            .border(1.dp, TerminalBrightGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
    ) {
        val width = size.width
        val height = size.height
        val margin = 8.dp.toPx()

        // 1. Draw corner reticles
        val reticleSize = 12.dp.toPx()
        val rStroke = 2.dp.toPx()

        // Top-Left corner
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(margin, margin),
            size = Size(reticleSize, rStroke)
        )
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(margin, margin),
            size = Size(rStroke, reticleSize)
        )

        // Top-Right corner
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(width - margin - reticleSize, margin),
            size = Size(reticleSize, rStroke)
        )
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(width - margin - rStroke, margin),
            size = Size(rStroke, reticleSize)
        )

        // Bottom-Left corner
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(margin, height - margin - rStroke),
            size = Size(reticleSize, rStroke)
        )
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(margin, height - margin - reticleSize),
            size = Size(rStroke, reticleSize)
        )

        // Bottom-Right corner
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(width - margin - reticleSize, height - margin - rStroke),
            size = Size(reticleSize, rStroke)
        )
        drawRect(
            color = TerminalBrightGreen,
            topLeft = Offset(width - margin - rStroke, height - margin - reticleSize),
            size = Size(rStroke, reticleSize)
        )

        // 2. Draw aesthetic cyber tech grid pattern representing connection QR bits
        val gridLines = 5
        val xSpacing = (width - margin * 2) / (gridLines + 1)
        val ySpacing = (height - margin * 2) / (gridLines + 1)

        // Draw simulated payload matrices as blocks
        val seed = (ssid.hashCode() xor passphrase.hashCode())
        val cleanSeed = if (seed < 0) -seed else seed
        val random = Random(cleanSeed.toLong())

        for (i in 1..gridLines) {
            for (j in 1..gridLines) {
                if (random.nextBoolean()) {
                    val boxSize = 6.dp.toPx()
                    drawRect(
                        color = TerminalBrightGreen.copy(alpha = 0.4f),
                        topLeft = Offset(margin + i * xSpacing - boxSize/2, margin + j * ySpacing - boxSize/2),
                        size = Size(boxSize, boxSize)
                    )
                }
            }
        }

        // Draw central core diagnostic anchor square
        drawRect(
            color = TerminalBrightCyan,
            topLeft = Offset(width/2 - 6.dp.toPx(), height/2 - 6.dp.toPx()),
            size = Size(12.dp.toPx(), 12.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 3. Drawing scanning laser line sweep
        val laserY = margin + (height - margin * 2) * sweepProgression
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(TerminalBrightGreen.copy(alpha = 0.1f), TerminalBrightGreen),
                startY = laserY - 10.dp.toPx(),
                endY = laserY
            ),
            start = Offset(margin, laserY),
            end = Offset(width - margin, laserY),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun WirelessDebuggingHUD(
    state: AdbHotspotState,
    viewModel: AdbHotspotViewModel,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface, RoundedCornerShape(12.dp))
            .border(1.dp, TerminalGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Engineering Tool",
                    tint = TerminalBrightCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "2. WIRELESS ADB ACTIVATOR",
                    color = TerminalWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Quick ADB active flag
            val adbColor = if (state.isWirelessDebuggingEnabled) TerminalBrightGreen else TerminalAmber
            Text(
                text = if (state.isWirelessDebuggingEnabled) "ADB WIFI: ON" else "OFFLINE",
                color = adbColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .border(1.dp, adbColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Action Trigger
        Button(
            onClick = { viewModel.launchWirelessDebugging(context) },
            colors = ButtonDefaults.buttonColors(
                containerColor = TerminalBrightCyan,
                contentColor = TerminalBg
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("launch_wireless_debug_btn")
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "OPEN DEV DEBUGGING PAGE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        // Display exact client command terminal instructions
        val clientConnectCmd = "adb connect ${state.deviceIpAddress}:5555"
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalBg, RoundedCornerShape(8.dp))
                .border(1.dp, TerminalBrightCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HOST TERMINAL CONNECT SCRIPT",
                    color = TerminalBrightCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "DEVICE GATEWAY IP",
                    color = TerminalGray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurfaceVariant, RoundedCornerShape(4.dp))
                    .border(1.dp, TerminalGray.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clientConnectCmd,
                    color = TerminalBrightGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                IconButton(
                    onClick = {
                        copyToClipboard(context, "ADB Link Cmd", clientConnectCmd)
                    },
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("copy_adb_cmd_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Copy command script",
                        tint = TerminalBrightGreen,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = "💡 Pair device in ADB settings if running for the first time. The dynamic port may change (verify matching settings value if port 5555 is unassigned).",
                color = TerminalGray,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun QuickSettingsHUD(
    state: AdbHotspotState,
    viewModel: AdbHotspotViewModel,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface, RoundedCornerShape(12.dp))
            .border(1.dp, TerminalGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = TerminalAmber,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "UTILITY AUTOMATIONS",
                color = TerminalWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Option A: Auto action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.toggleAutoRun(context, !state.autoRunOnLaunch)
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Launch Actions Instantly on Startup",
                    color = TerminalWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Starts local AP session on opening the app.",
                    color = TerminalGray,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = state.autoRunOnLaunch,
                onCheckedChange = { viewModel.toggleAutoRun(context, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TerminalBrightGreen,
                    checkedTrackColor = TerminalDimGreen,
                    uncheckedThumbColor = TerminalGray,
                    uncheckedTrackColor = TerminalSurfaceVariant
                ),
                modifier = Modifier.scale(0.8f).testTag("auto_run_switch")
            )
        }

        // Option B: Keep Screen On
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.toggleKeepScreen(context, !state.keepScreenOn)
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Force Screen Awake Mode",
                    color = TerminalWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Prevents dynamic connection drop cycles.",
                    color = TerminalGray,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = state.keepScreenOn,
                onCheckedChange = { viewModel.toggleKeepScreen(context, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TerminalBrightCyan,
                    checkedTrackColor = TerminalDimCyan,
                    uncheckedThumbColor = TerminalGray,
                    uncheckedTrackColor = TerminalSurfaceVariant
                ),
                modifier = Modifier.scale(0.8f).testTag("keep_awake_switch")
            )
        }
    }
}

@Composable
fun TerminalConsoleHUD(
    state: AdbHotspotState,
    viewModel: AdbHotspotViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface, RoundedCornerShape(12.dp))
            .border(1.dp, TerminalGray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TerminalBrightGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "DIAGNOSTIC EVENT LOGGER",
                    color = TerminalGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "CLEAR LOGS",
                color = TerminalBrightCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { viewModel.clearLogs() }
                    .padding(4.dp)
                    .testTag("clear_logs_btn")
            )
        }

        // Live Console log block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 220.dp)
                .background(TerminalBg, RoundedCornerShape(8.dp))
                .border(1.dp, TerminalGray.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.consoleLogs.forEach { logLine ->
                Text(
                    text = logLine,
                    color = if (logLine.contains("✓")) TerminalBrightGreen else if (logLine.contains("✗") || logLine.contains("⚠️")) TerminalAmber else TerminalWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label Copied!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Copy Failed", Toast.LENGTH_SHORT).show()
    }
}
