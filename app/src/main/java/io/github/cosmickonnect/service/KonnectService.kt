package io.github.cosmickonnect.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.cosmickonnect.CosmicKonnectApp
import io.github.cosmickonnect.R
import io.github.cosmickonnect.ble.BleAdvertiser
import io.github.cosmickonnect.ble.BleDiscoveredDevice
import io.github.cosmickonnect.ble.BleScanner
import io.github.cosmickonnect.ckp.CkpServiceManager
import io.github.cosmickonnect.protocol.DeviceIdentity
import io.github.cosmickonnect.protocol.DeviceManager
import io.github.cosmickonnect.protocol.Discovery
import io.github.cosmickonnect.protocol.NetworkPacket
import io.github.cosmickonnect.ui.MainActivity
import io.github.cosmickonnect.wifidirect.WifiDirectConnectionInfo
import io.github.cosmickonnect.wifidirect.WifiDirectDevice
import io.github.cosmickonnect.wifidirect.WifiDirectManager
import kotlinx.coroutines.*

class KonnectService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discovery: Discovery? = null
    var deviceManager: DeviceManager? = null
        private set

    // BLE components
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var bleEnabled = false

    // Wi-Fi Direct components
    private var wifiDirectManager: WifiDirectManager? = null
    private var wifiDirectEnabled = false

    // CKP (Cosmic Konnect Protocol) components
    private var ckpService: CkpServiceManager? = null

    // Clipboard sync
    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardContent: String = ""
    private var isSettingClipboard = false

    /**
     * Get the CKP service manager for UI access
     */
    val ckpServiceManager: CkpServiceManager?
        get() = ckpService

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): KonnectService = this@KonnectService
    }

    companion object {
        private const val TAG = "KonnectService"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KonnectService onCreate")
        deviceManager = DeviceManager(this)
        discovery = Discovery(this, deviceManager!!)

        // Initialize CKP (Cosmic Konnect Protocol)
        initializeCkp()

        // Initialize BLE components
        initializeBle()

        // Initialize Wi-Fi Direct components
        initializeWifiDirect()
    }

    private fun initializeCkp() {
        try {
            ckpService = CkpServiceManager(this)
            ckpService?.initialize()

            // Set up event handlers
            ckpService?.onPingReceived = { deviceId, message ->
                Log.i(TAG, "CKP Ping from $deviceId: $message")
                showPingNotification(deviceId, message)
            }
            ckpService?.onDeviceConnected = { deviceId, deviceName ->
                Log.i(TAG, "CKP Connected: $deviceName ($deviceId)")
            }
            ckpService?.onDeviceDisconnected = { deviceId ->
                Log.i(TAG, "CKP Disconnected: $deviceId")
            }
            ckpService?.onClipboardReceived = { deviceId, content ->
                Log.i(TAG, "CKP Clipboard from $deviceId: ${content.length} chars")
                // Set flag to prevent feedback loop
                isSettingClipboard = true
                lastClipboardContent = content
                android.os.Handler(mainLooper).post {
                    try {
                        clipboardManager?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Cosmic Konnect", content)
                        )
                        // Short vibration to indicate clipboard received
                        vibrateShort()
                    } finally {
                        // Reset flag after a short delay to allow clipboard listener to fire
                        android.os.Handler(mainLooper).postDelayed({
                            isSettingClipboard = false
                        }, 100)
                    }
                }
            }

            // Start clipboard monitoring
            startClipboardMonitor()

            Log.i(TAG, "CKP service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CKP: ${e.message}", e)
        }
    }

    private fun startClipboardMonitor() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Get initial clipboard content
        clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()?.let {
            lastClipboardContent = it
        }

        clipboardManager?.addPrimaryClipChangedListener {
            if (isSettingClipboard) {
                // We're setting the clipboard ourselves, ignore this change
                return@addPrimaryClipChangedListener
            }

            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val content = clip.getItemAt(0).text?.toString() ?: return@addPrimaryClipChangedListener

                if (content != lastClipboardContent && content.isNotEmpty()) {
                    Log.i(TAG, "Clipboard changed: ${content.length} chars")
                    lastClipboardContent = content

                    // Broadcast to all connected devices
                    serviceScope.launch {
                        ckpService?.broadcastClipboard(content)
                    }
                }
            }
        }

        Log.i(TAG, "Clipboard monitoring started")
    }

    private fun initializeBle() {
        if (!hasBlePermissions()) {
            Log.w(TAG, "BLE permissions not granted, skipping BLE initialization")
            return
        }

        try {
            // Create BLE advertiser
            bleAdvertiser = BleAdvertiser(this) { deviceId, deviceName, ipAddress ->
                Log.i(TAG, "BLE connection request from $deviceName ($deviceId) at $ipAddress")
                // Attempt TCP connection to the requesting device
                serviceScope.launch {
                    deviceManager?.connectToDevice(deviceId, deviceName, ipAddress, NetworkPacket.DEFAULT_TCP_PORT)
                }
            }

            // Create BLE scanner
            bleScanner = BleScanner(this) { bleDevice ->
                handleBleDeviceDiscovered(bleDevice)
            }

            Log.i(TAG, "BLE components created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create BLE components: ${e.message}", e)
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initializeWifiDirect() {
        if (!hasWifiDirectPermissions()) {
            Log.w(TAG, "Wi-Fi Direct permissions not granted, skipping initialization")
            return
        }

        try {
            wifiDirectManager = WifiDirectManager(
                context = this,
                onDeviceDiscovered = { device ->
                    handleWifiDirectDeviceDiscovered(device)
                },
                onConnectionEstablished = { connectionInfo ->
                    handleWifiDirectConnectionEstablished(connectionInfo)
                }
            )

            Log.i(TAG, "Wi-Fi Direct manager created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Wi-Fi Direct manager: ${e.message}", e)
        }
    }

    private fun hasWifiDirectPermissions(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Android 13+ requires NEARBY_WIFI_DEVICES
        val hasNearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasLocation || hasNearbyWifi
    }

    private fun handleWifiDirectDeviceDiscovered(device: WifiDirectDevice) {
        Log.i(TAG, "Wi-Fi Direct device discovered: ${device.deviceName} (${device.deviceAddress})")
        // Wi-Fi Direct devices don't have IP/port info until connected
        // We'll use service discovery to find Cosmic Konnect devices
    }

    private fun handleWifiDirectConnectionEstablished(connectionInfo: WifiDirectConnectionInfo) {
        Log.i(TAG, "Wi-Fi Direct connection established")
        Log.i(TAG, "  Group owner: ${connectionInfo.isGroupOwner}")
        Log.i(TAG, "  Group owner address: ${connectionInfo.groupOwnerAddress}")

        if (connectionInfo.groupFormed && connectionInfo.groupOwnerAddress != null) {
            // Now we can establish TCP connection
            val ip = connectionInfo.groupOwnerAddress.hostAddress
            if (ip != null) {
                serviceScope.launch {
                    // If we're not the group owner, connect to the group owner
                    if (!connectionInfo.isGroupOwner) {
                        Log.i(TAG, "Connecting to group owner at $ip")
                        // The group owner's address is where we need to connect
                        // We'll try the default port
                        deviceManager?.connectToDevice(
                            "wifidirect-${ip.replace(".", "_")}",
                            "Wi-Fi Direct Device",
                            ip,
                            NetworkPacket.DEFAULT_TCP_PORT
                        )
                    }
                    // If we are the group owner, we wait for incoming connections
                }
            }
        }
    }

    /**
     * Start Wi-Fi Direct discovery and service registration.
     */
    fun startWifiDirect(): Boolean {
        if (wifiDirectEnabled) {
            Log.w(TAG, "Wi-Fi Direct already enabled")
            return true
        }

        if (!hasWifiDirectPermissions()) {
            Log.e(TAG, "Wi-Fi Direct permissions not granted")
            return false
        }

        if (wifiDirectManager?.initialize() != true) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct manager")
            return false
        }

        // Register our service for discovery by other devices
        val identity = DeviceIdentity.getIdentity(this)
        wifiDirectManager?.registerService(identity.deviceId, identity.deviceName, NetworkPacket.DEFAULT_TCP_PORT)

        // Start peer discovery
        wifiDirectManager?.startDiscovery()

        // Start service discovery to find other Cosmic Konnect devices
        wifiDirectManager?.discoverServices { deviceAddress, deviceId, deviceName, tcpPort ->
            Log.i(TAG, "Found Cosmic Konnect service: $deviceName at $deviceAddress")
            // Connect to the device
            wifiDirectManager?.connect(deviceAddress)
        }

        wifiDirectEnabled = true
        Log.i(TAG, "Wi-Fi Direct started")
        return true
    }

    /**
     * Stop Wi-Fi Direct.
     */
    fun stopWifiDirect() {
        wifiDirectManager?.stopDiscovery()
        wifiDirectManager?.disconnect()
        wifiDirectEnabled = false
        Log.i(TAG, "Wi-Fi Direct stopped")
    }

    /**
     * Check if Wi-Fi Direct is enabled.
     */
    fun isWifiDirectEnabled(): Boolean = wifiDirectEnabled

    private fun handleBleDeviceDiscovered(bleDevice: BleDiscoveredDevice) {
        Log.i(TAG, "BLE discovered device: ${bleDevice.deviceName} (${bleDevice.deviceId})")
        Log.i(TAG, "  IPs: ${bleDevice.ipAddresses}, Port: ${bleDevice.tcpPort}")

        // Try to connect via TCP using the device's IP addresses
        serviceScope.launch {
            for (ip in bleDevice.ipAddresses) {
                try {
                    Log.i(TAG, "Attempting TCP connection to ${bleDevice.deviceName} at $ip:${bleDevice.tcpPort}")
                    deviceManager?.connectToDevice(
                        bleDevice.deviceId,
                        bleDevice.deviceName,
                        ip,
                        bleDevice.tcpPort
                    )
                    break // Success, stop trying other IPs
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect to $ip: ${e.message}")
                }
            }
        }
    }

    /**
     * Start BLE advertising and scanning.
     */
    fun startBle(): Boolean {
        if (bleEnabled) {
            Log.w(TAG, "BLE already enabled")
            return true
        }

        if (!hasBlePermissions()) {
            Log.e(TAG, "BLE permissions not granted")
            return false
        }

        var success = true

        // Initialize advertiser
        if (bleAdvertiser?.initialize() == true) {
            if (bleAdvertiser?.startAdvertising() == true) {
                Log.i(TAG, "BLE advertising started")
            } else {
                Log.e(TAG, "Failed to start BLE advertising")
                success = false
            }
        } else {
            Log.e(TAG, "Failed to initialize BLE advertiser")
            success = false
        }

        // Initialize scanner
        if (bleScanner?.initialize() == true) {
            if (bleScanner?.startScanning() == true) {
                Log.i(TAG, "BLE scanning started")
            } else {
                Log.e(TAG, "Failed to start BLE scanning")
                success = false
            }
        } else {
            Log.e(TAG, "Failed to initialize BLE scanner")
            success = false
        }

        bleEnabled = success
        return success
    }

    /**
     * Stop BLE advertising and scanning.
     */
    fun stopBle() {
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScanning()
        bleEnabled = false
        Log.i(TAG, "BLE stopped")
    }

    /**
     * Check if BLE is currently enabled.
     */
    fun isBleEnabled(): Boolean = bleEnabled

    /**
     * Trigger a new BLE scan.
     */
    fun triggerBleScan(): Boolean {
        return bleScanner?.startScanning() ?: false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KonnectService onStartCommand")
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // Start CKP (Cosmic Konnect Protocol) - new protocol
                Log.i(TAG, "Starting CKP service...")
                ckpService?.start()
                Log.i(TAG, "CKP service started")

                // Also start legacy KDE Connect discovery
                Log.i(TAG, "Starting KDE Connect discovery...")
                discovery?.start()
                Log.i(TAG, "Discovery started successfully")

                // Also start BLE discovery
                if (hasBlePermissions()) {
                    startBle()
                }

                // Also start Wi-Fi Direct discovery
                if (hasWifiDirectPermissions()) {
                    startWifiDirect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery failed to start: ${e.message}", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "KonnectService onBind")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "KonnectService onDestroy")
        serviceScope.cancel()
        ckpService?.stop()
        discovery?.stop()
        stopBle()
        bleScanner?.release()
        stopWifiDirect()
        wifiDirectManager?.release()
        deviceManager?.disconnectAll()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CosmicKonnectApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showPingNotification(deviceId: String, message: String?) {
        // Get device name if available
        val deviceName = ckpService?.devices?.value?.get(deviceId)?.name ?: "Desktop"

        // Create notification
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CosmicKonnectApp.CHANNEL_NOTIFICATIONS)
            .setContentTitle("Ping from $deviceName")
            .setContentText(message ?: "Ping!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)

        vibrate(500)
    }

    /**
     * Vibrate for a given duration in milliseconds
     */
    private fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    /**
     * Short vibration for clipboard received feedback
     */
    private fun vibrateShort() {
        vibrate(100)
    }

}
