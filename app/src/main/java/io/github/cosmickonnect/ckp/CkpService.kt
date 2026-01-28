package io.github.cosmickonnect.ckp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Device state for UI
 */
data class CkpDeviceState(
    val deviceId: String,
    val name: String,
    val deviceType: DeviceType,
    val paired: Boolean,
    val connected: Boolean
)

/**
 * CKP Service for Android
 *
 * Manages discovery, connections, and message handling.
 */
class CkpServiceManager(private val context: Context) {
    private val TAG = "CkpServiceManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var identity: Identity
    private var discovery: CkpDiscovery? = null
    private var connectionManager: CkpConnectionManager? = null

    private val _devices = MutableStateFlow<Map<String, CkpDeviceState>>(emptyMap())
    val devices: StateFlow<Map<String, CkpDeviceState>> = _devices

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** Get our device name */
    val deviceName: String
        get() = if (::identity.isInitialized) identity.name else "Android"

    private val pairedDevices = mutableMapOf<String, ByteArray>() // deviceId -> pairingKey

    // Auto-accept pairing (Apple-style seamless sync)
    var autoAcceptPairing = true

    // Event callbacks
    var onPairingRequested: ((deviceId: String, deviceName: String, verificationCode: String) -> Unit)? = null
    var onDeviceConnected: ((deviceId: String, deviceName: String) -> Unit)? = null
    var onDeviceDisconnected: ((deviceId: String) -> Unit)? = null
    var onPingReceived: ((deviceId: String, message: String?) -> Unit)? = null
    var onClipboardReceived: ((deviceId: String, content: String) -> Unit)? = null
    var onNotificationReceived: ((deviceId: String, notification: Notification) -> Unit)? = null
    var onFileOfferReceived: ((deviceId: String, filename: String, size: Long) -> Unit)? = null

    /**
     * Initialize the service
     */
    fun initialize() {
        // Load or create device identity
        val prefs = context.getSharedPreferences("ckp_identity", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }

        val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL

        identity = Identity(
            deviceId = deviceId,
            name = deviceName,
            deviceType = DeviceType.PHONE,
            tcpPort = Protocol.TCP_PORT
        )

        Log.i(TAG, "Initialized as $deviceName ($deviceId)")

        // Load paired devices
        loadPairedDevices()
    }

    /**
     * Start the CKP service
     */
    fun start() {
        if (_isRunning.value) return

        // Start discovery
        discovery = CkpDiscovery(context, identity) { device ->
            handleDeviceDiscovered(device)
        }
        discovery?.start()

        // Start connection manager
        connectionManager = CkpConnectionManager(identity) { event ->
            handleConnectionEvent(event)
        }

        // Start TCP listener for incoming connections
        if (connectionManager?.startListener() == true) {
            Log.i(TAG, "CKP TCP listener started on port ${Protocol.TCP_PORT}")
        } else {
            Log.e(TAG, "Failed to start CKP TCP listener")
        }

        _isRunning.value = true
        Log.i(TAG, "CKP service started")
    }

    /**
     * Stop the CKP service
     */
    fun stop() {
        discovery?.stop()
        discovery = null

        connectionManager?.getConnectedDevices()?.forEach { deviceId ->
            connectionManager?.disconnect(deviceId)
        }
        connectionManager?.stopListener()
        connectionManager = null

        _isRunning.value = false
        Log.i(TAG, "CKP service stopped")
    }

    /**
     * Connect to a device
     */
    suspend fun connect(deviceId: String): Boolean {
        val device = discovery?.devices?.value?.get(deviceId) ?: return false
        return connectionManager?.connect(deviceId, device.address.hostAddress ?: "", device.tcpPort) ?: false
    }

    /**
     * Disconnect from a device
     */
    fun disconnect(deviceId: String) {
        connectionManager?.disconnect(deviceId)
    }

    /**
     * Send a ping to a device
     */
    suspend fun ping(deviceId: String, message: String? = null) {
        connectionManager?.sendMessage(deviceId, Ping(message))
    }

    /**
     * Send clipboard content to a device
     */
    suspend fun sendClipboard(deviceId: String, content: String) {
        val clipboard = Clipboard(
            content = content,
            timestamp = System.currentTimeMillis()
        )
        connectionManager?.sendMessage(deviceId, clipboard)
    }

    /**
     * Send clipboard content to all connected devices
     */
    suspend fun broadcastClipboard(content: String) {
        val devices = connectionManager?.getConnectedDevices() ?: emptyList()
        Log.i(TAG, "Broadcasting clipboard to ${devices.size} devices: $devices")
        devices.forEach { deviceId ->
            Log.i(TAG, "Sending clipboard to $deviceId (${content.length} chars)")
            sendClipboard(deviceId, content)
        }
    }

    /**
     * Share a URL with a device
     */
    suspend fun shareUrl(deviceId: String, url: String) {
        connectionManager?.sendMessage(deviceId, ShareUrl(url))
    }

    /**
     * Share text with a device
     */
    suspend fun shareText(deviceId: String, text: String) {
        connectionManager?.sendMessage(deviceId, ShareText(text))
    }

    /**
     * Find device (ring the desktop)
     */
    suspend fun findDevice(deviceId: String) {
        connectionManager?.sendMessage(deviceId, FindDevice())
    }

    private fun handleDeviceDiscovered(device: DiscoveredDevice) {
        val isPaired = pairedDevices.containsKey(device.deviceId)
        val isConnected = connectionManager?.getConnectedDevices()?.contains(device.deviceId) ?: false

        val updated = _devices.value.toMutableMap()
        updated[device.deviceId] = CkpDeviceState(
            deviceId = device.deviceId,
            name = device.name,
            deviceType = device.deviceType,
            paired = isPaired,
            connected = isConnected
        )
        _devices.value = updated

        // Auto-connect to paired devices
        if (isPaired && !isConnected) {
            scope.launch {
                connect(device.deviceId)
            }
        }
    }

    private suspend fun handleConnectionEvent(event: CkpConnectionEvent) {
        when (event) {
            is CkpConnectionEvent.Connected -> {
                // Add device to map if it doesn't exist (for incoming connections)
                if (!_devices.value.containsKey(event.deviceId)) {
                    val updated = _devices.value.toMutableMap()
                    updated[event.deviceId] = CkpDeviceState(
                        deviceId = event.deviceId,
                        name = event.deviceName,
                        deviceType = DeviceType.DESKTOP, // Assume desktop for incoming connections
                        paired = pairedDevices.containsKey(event.deviceId),
                        connected = true
                    )
                    _devices.value = updated
                } else {
                    updateDeviceState(event.deviceId) { it.copy(connected = true) }
                }
                onDeviceConnected?.invoke(event.deviceId, event.deviceName)
            }
            is CkpConnectionEvent.Disconnected -> {
                updateDeviceState(event.deviceId) { it.copy(connected = false) }
                onDeviceDisconnected?.invoke(event.deviceId)
            }
            is CkpConnectionEvent.PingReceived -> {
                onPingReceived?.invoke(event.deviceId, event.message)
            }
            is CkpConnectionEvent.ClipboardReceived -> {
                // Notify callback if set, otherwise update clipboard directly
                if (onClipboardReceived != null) {
                    onClipboardReceived?.invoke(event.deviceId, event.content)
                } else {
                    withContext(Dispatchers.Main) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cosmic Konnect", event.content))
                    }
                }
            }
            is CkpConnectionEvent.NotificationReceived -> {
                onNotificationReceived?.invoke(event.deviceId, event.notification)
            }
            is CkpConnectionEvent.FileOfferReceived -> {
                onFileOfferReceived?.invoke(event.deviceId, event.offer.filename, event.offer.size)
            }
            is CkpConnectionEvent.FindDeviceReceived -> {
                // Ring the phone
                ringPhone()
            }
            is CkpConnectionEvent.UrlReceived -> {
                // Open URL in browser
                withContext(Dispatchers.Main) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open URL: ${e.message}")
                    }
                }
            }
            is CkpConnectionEvent.TextReceived -> {
                // Copy to clipboard
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cosmic Konnect", event.text))
                }
            }
            is CkpConnectionEvent.PairingRequested -> {
                // Apple-style: always auto-accept pairing
                Log.i(TAG, "Pairing requested from ${event.deviceName}, auto-accepting")
                connectionManager?.acceptPairing(event.deviceId, event.publicKey)
            }
            is CkpConnectionEvent.PairingAccepted -> {
                Log.i(TAG, "Pairing accepted from ${event.deviceId}")
                // Save pairing key and update device state
                savePairedDevice(event.deviceId, event.pairingKey)
                updateDeviceState(event.deviceId) { it.copy(paired = true) }
            }
            is CkpConnectionEvent.PairingRejected -> {
                Log.i(TAG, "Pairing rejected from ${event.deviceId}: ${event.reason}")
            }
        }
    }

    private fun updateDeviceState(deviceId: String, update: (CkpDeviceState) -> CkpDeviceState) {
        val current = _devices.value[deviceId] ?: return
        val updated = _devices.value.toMutableMap()
        updated[deviceId] = update(current)
        _devices.value = updated
    }

    private fun ringPhone() {
        scope.launch(Dispatchers.Main) {
            try {
                // Play ringtone
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = false
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ringtone.audioAttributes = audioAttributes
                }

                ringtone.play()

                // Stop after 5 seconds
                delay(5000)
                ringtone.stop()

                // Also vibrate
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ring phone: ${e.message}")
            }
        }
    }

    private fun loadPairedDevices() {
        val prefs = context.getSharedPreferences("ckp_paired", Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (value is String) {
                try {
                    val keyBytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
                    pairedDevices[key] = keyBytes
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load pairing key for $key")
                }
            }
        }
        Log.i(TAG, "Loaded ${pairedDevices.size} paired devices")
    }

    private fun savePairedDevice(deviceId: String, pairingKey: ByteArray) {
        val prefs = context.getSharedPreferences("ckp_paired", Context.MODE_PRIVATE)
        val encoded = android.util.Base64.encodeToString(pairingKey, android.util.Base64.DEFAULT)
        prefs.edit().putString(deviceId, encoded).apply()
        pairedDevices[deviceId] = pairingKey
    }

    private fun removePairedDevice(deviceId: String) {
        val prefs = context.getSharedPreferences("ckp_paired", Context.MODE_PRIVATE)
        prefs.edit().remove(deviceId).apply()
        pairedDevices.remove(deviceId)
    }
}
