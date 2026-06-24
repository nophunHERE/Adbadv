package com.example

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class AdbHotspotViewModel : ViewModel() {

    private val _state = MutableStateFlow(AdbHotspotState())
    val state: StateFlow<AdbHotspotState> = _state.asStateFlow()

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        _state.update {
            it.copy(
                deviceModel = deviceName,
                consoleLogs = listOf("[${timeFormat.format(Date())}] Wireless ADB & Hotspot Console ready.")
            )
        }
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("adb_hotspot_prefs", Context.MODE_PRIVATE)
        val autoRun = prefs.getBoolean("auto_run", true)
        val keepScreen = prefs.getBoolean("keep_screen", true)
        _state.update {
            it.copy(
                autoRunOnLaunch = autoRun,
                keepScreenOn = keepScreen
            )
        }
        log("Preferences loaded. Auto-run: $autoRun, Keep screen on: $keepScreen")
    }

    fun toggleAutoRun(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("adb_hotspot_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_run", enabled).apply()
        _state.update { it.copy(autoRunOnLaunch = enabled) }
        log("Auto-run ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun toggleKeepScreen(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("adb_hotspot_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("keep_screen", enabled).apply()
        _state.update { it.copy(keepScreenOn = enabled) }
        log("Keep Screen On ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun addManualLog(message: String) {
        log(message)
    }

    private fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        _state.update {
            it.copy(consoleLogs = it.consoleLogs + entry)
        }
    }

    fun clearLogs() {
        _state.update {
            it.copy(consoleLogs = listOf("[${timeFormat.format(Date())}] Console cleared."))
        }
    }

    fun refreshStatus(context: Context) {
        refreshAdbStatus(context)
        refreshIpAddress()
    }

    private fun refreshAdbStatus(context: Context) {
        val resolver = context.contentResolver
        val adbEnabled = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
        val adbWifiEnabled = try {
            Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
        } catch (e: Exception) {
            false
        }
        _state.update {
            it.copy(
                isAdbEnabled = adbEnabled,
                isWirelessDebuggingEnabled = adbWifiEnabled
            )
        }
    }

    private fun refreshIpAddress() {
        val ip = getLocalIpAddress()
        _state.update { it.copy(deviceIpAddress = ip) }
    }

    @Suppress("DEPRECATION")
    fun startLocalOnlyHotspot(context: Context) {
        if (_state.value.hotspotStatus == HotspotStatus.STARTING || _state.value.hotspotStatus == HotspotStatus.ACTIVE) {
            log("Hotspot is already starting or active.")
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            log("✗ Error: WifiManager not available")
            return
        }

        log("Requesting system to start Local-Only Hotspot...")
        _state.update { it.copy(hotspotStatus = HotspotStatus.STARTING) }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    if (reservation != null) {
                        val config = reservation.wifiConfiguration
                        
                        // Extract SSID and password. Note that on Android 11+ (API 30),
                        // SoftApConfiguration is preferred if accessible, but reservation.wifiConfiguration
                        // is still supported as a fallback or wrapped representation.
                        var ssidVal = ""
                        var passVal = ""
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val softApConfig = reservation.softApConfiguration
                                ssidVal = softApConfig.ssid ?: ""
                                passVal = softApConfig.passphrase ?: ""
                            } catch (e: Throwable) {
                                // fallback to wifiConfiguration if reflective softApConfiguration fails
                                ssidVal = config?.SSID ?: ""
                                passVal = config?.preSharedKey ?: ""
                            }
                        } else {
                            ssidVal = config?.SSID ?: ""
                            passVal = config?.preSharedKey ?: ""
                        }

                        // Clean up SSID surrounding brackets/quotes if present
                        if (ssidVal.startsWith("\"") && ssidVal.endsWith("\"")) {
                            ssidVal = ssidVal.substring(1, ssidVal.length - 1)
                        }
                        if (passVal.startsWith("\"") && passVal.endsWith("\"")) {
                            passVal = passVal.substring(1, passVal.length - 1)
                        }

                        _state.update {
                            it.copy(
                                isHotspotActive = true,
                                hotspotStatus = HotspotStatus.ACTIVE,
                                ssid = ssidVal,
                                passphrase = passVal
                            )
                        }
                        log("✓ Hotspot active! Clients can now pair.")
                        log("  SSID: $ssidVal")
                        log("  KEY:  $passVal")
                        
                        // Trigger Wireless Debugging panel launch automatically if autoRun is active
                        if (_state.value.autoRunOnLaunch) {
                            launchWirelessDebugging(context)
                        }
                    } else {
                        _state.update { it.copy(hotspotStatus = HotspotStatus.FAILED) }
                        log("✗ Failed: System returned a null hotspot reservation.")
                    }
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                    _state.update {
                        it.copy(
                            isHotspotActive = false,
                            hotspotStatus = HotspotStatus.IDLE,
                            ssid = "",
                            passphrase = ""
                        )
                    }
                    log("Hotspot stopped by system.")
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    hotspotReservation = null
                    _state.update { it.copy(hotspotStatus = HotspotStatus.FAILED) }
                    
                    val errMsg = when (reason) {
                        1 -> "No frequency channels available"
                        2 -> "Generic interface allocation failure (Failed to create iface / ifaceType=1)"
                        3 -> "Conflicting active tethering session"
                        else -> "Failed with dynamic code $reason"
                    }
                    log("✗ Hotspot starting failed: $errMsg")
                    log("💡 DIAGNOSIS: The system's Wi-Fi HAL could not spawn a local Access Point (AP) interface.")
                    log("   1. Try toggling your Wi-Fi switch OFF and ON.")
                    log("   2. Turn OFF any active standard mobile hotspots.")
                    log("   3. On some devices/emulators lacking dual-antenna support, standard tethering is required.")
                    log("   👉 Tap the settings gear icon above to open manual Tethering.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            _state.update { it.copy(hotspotStatus = HotspotStatus.FAILED) }
            log("✗ SecurityException: Missing permissions (Location or Nearby Wifi)")
        } catch (e: Exception) {
            _state.update { it.copy(hotspotStatus = HotspotStatus.FAILED) }
            log("✗ System Error: ${e.localizedMessage}")
        }
    }

    fun stopLocalOnlyHotspot() {
        if (hotspotReservation == null) {
            log("No active Local-Only Hotspot to close.")
            return
        }
        log("Closing Local-Only Hotspot session...")
        try {
            hotspotReservation?.close()
            hotspotReservation = null
            _state.update {
                it.copy(
                    isHotspotActive = false,
                    hotspotStatus = HotspotStatus.IDLE,
                    ssid = "",
                    passphrase = ""
                )
            }
            log("✓ Hotspot stopped successfully.")
        } catch (e: Exception) {
            log("✗ Error closing hotspot: ${e.localizedMessage}")
        }
    }

    fun launchWirelessDebugging(context: Context) {
        log("Broadcasting Intents for Wireless Debugging...")
        
        val wifiAdbIntent = Intent("android.settings.WIFI_ADB_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        val devSettingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val tetherSettingsIntent = Intent("android.settings.TETHER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(wifiAdbIntent)
            log("✓ Wireless Debugging screen deep link launched.")
        } catch (e: Exception) {
            log("⚠️ Direct Wireless Debug link failed. Launching Developer Options...")
            try {
                context.startActivity(devSettingsIntent)
                log("✓ Opened Developer Options. Enable 'Wireless debugging' manually.")
            } catch (e2: Exception) {
                log("✗ Failed launching Developer settings. Trying Tether settings...")
                try {
                    context.startActivity(tetherSettingsIntent)
                    log("✓ Opened Tethering & hotspot settings.")
                } catch (e3: Exception) {
                    log("✗ Native settings deep-links are inaccessible.")
                }
            }
        }
    }

    fun launchTetherSettings(context: Context) {
        log("Opening direct Wi-Fi Tethering settings...")
        val intent = Intent("android.settings.TETHER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            log("✓ Tether settings screen opened.")
        } catch (e: Exception) {
            val genericIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(genericIntent)
                log("✓ Wireless network settings opened.")
            } catch (e2: Exception) {
                log("✗ Failed opening tether settings.")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (inetAddress in addresses) {
                    if (!inetAddress.isLoopbackAddress) {
                        val ip = inetAddress.hostAddress ?: ""
                        // Check if IPv4
                        if (!ip.contains(':')) {
                            // On standard hotspot, standard network interface starts with wlan0 or ap0
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("ap") || networkInterface.name.contains("rndis")) {
                                return ip
                            }
                        }
                    }
                }
            }
            // Broad search for any IPv4 address if no specific wlan/ap interface is found
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (inetAddress in addresses) {
                    if (!inetAddress.isLoopbackAddress) {
                        val ip = inetAddress.hostAddress ?: ""
                        if (!ip.contains(':')) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "192.168.43.1" // Fallback AP gateway IP
    }
}

data class AdbHotspotState(
    val isHotspotActive: Boolean = false,
    val hotspotStatus: HotspotStatus = HotspotStatus.IDLE,
    val ssid: String = "",
    val passphrase: String = "",
    val isAdbEnabled: Boolean = false,
    val isWirelessDebuggingEnabled: Boolean = false,
    val deviceIpAddress: String = "192.168.43.1",
    val deviceModel: String = "",
    val autoRunOnLaunch: Boolean = true,
    val keepScreenOn: Boolean = true,
    val consoleLogs: List<String> = emptyList()
)

enum class HotspotStatus {
    IDLE,
    STARTING,
    ACTIVE,
    FAILED,
    STOPPING
}
