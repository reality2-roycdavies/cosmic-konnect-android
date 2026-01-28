package io.github.cosmickonnect.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.cosmickonnect.ckp.CkpServiceManager
import io.github.cosmickonnect.ckp.CkpDeviceState
import io.github.cosmickonnect.protocol.DeviceManager
import io.github.cosmickonnect.service.KonnectService
import io.github.cosmickonnect.ui.theme.CosmicKonnectTheme
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private var konnectService: KonnectService? = null
    private var serviceBound = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as KonnectService.LocalBinder
            konnectService = localBinder.getService()
            serviceBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            konnectService = null
            serviceBound.value = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        // Auto-start the service
        startAndBindService()

        setContent {
            CosmicKonnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val bound by serviceBound
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    MainScreen(
                        isServiceBound = bound,
                        deviceManager = konnectService?.deviceManager,
                        ckpService = konnectService?.ckpServiceManager,
                        onRestartService = { restartService() },
                        onShareClipboard = {
                            android.util.Log.i("MainActivity", "Share Clipboard button pressed")
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboardManager.primaryClip
                            android.util.Log.i("MainActivity", "Clipboard: clip=$clip, konnectService=$konnectService")
                            if (clip != null && clip.itemCount > 0) {
                                val content = clip.getItemAt(0).text?.toString()
                                android.util.Log.i("MainActivity", "Clipboard content: ${content?.length ?: 0} chars, ckpServiceManager=${konnectService?.ckpServiceManager}")
                                if (!content.isNullOrEmpty()) {
                                    scope.launch {
                                        android.util.Log.i("MainActivity", "Calling broadcastClipboard...")
                                        konnectService?.ckpServiceManager?.broadcastClipboard(content)
                                        android.util.Log.i("MainActivity", "broadcastClipboard completed")
                                        Toast.makeText(context, "Clipboard shared", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    private var isBound = false

    override fun onStart() {
        super.onStart()
        // Try to bind if service is already running
        if (!isBound) {
            Intent(this, KonnectService::class.java).also { intent ->
                isBound = bindService(intent, serviceConnection, 0)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            serviceBound.value = false
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // BLE permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Legacy Bluetooth permissions (Android 11 and below)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Location permission (required for BLE/Wi-Fi Direct on older Android)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Wi-Fi Direct permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, KonnectService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!isBound) {
            isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopAndUnbindService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            serviceBound.value = false
        }
        stopService(Intent(this, KonnectService::class.java))
        konnectService = null
    }

    private fun restartService() {
        stopAndUnbindService()
        startAndBindService()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceBound: Boolean,
    deviceManager: DeviceManager?,
    ckpService: CkpServiceManager?,
    onRestartService: () -> Unit,
    onShareClipboard: () -> Unit
) {
    // KDE Connect devices (legacy)
    val kdeDevices by deviceManager?.devices?.collectAsState()
        ?: remember { mutableStateOf(emptyList<DeviceManager.Device>()) }

    // CKP devices (new protocol)
    val ckpDevicesMap by ckpService?.devices?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<String, CkpDeviceState>()) }

    // Convert CKP devices to a unified Device type for display
    val ckpDevicesList = ckpDevicesMap.values.map { ckp ->
        DeviceManager.Device(
            id = ckp.deviceId,
            name = ckp.name,
            type = ckp.deviceType.value,
            paired = ckp.paired,
            connected = ckp.connected
        )
    }

    // Combine both device lists (CKP devices take precedence)
    val ckpIds = ckpDevicesList.map { it.id }.toSet()
    val devices = ckpDevicesList + kdeDevices.filter { it.id !in ckpIds }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cosmic Konnect") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = onShareClipboard) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Share Clipboard")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRestartService
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Restart"
                )
            }
        }
    ) { padding ->
        var isRefreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    onRestartService()
                    delay(1000) // Give time for restart
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Connection status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceBound)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isServiceBound) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            if (isServiceBound) "Service Running" else "Service Stopped",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isServiceBound) "Found ${devices.size} device(s)" else "Starting...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Devices section
            Text(
                "Discovered Devices",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.DevicesOther,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (isServiceBound) "Searching for devices..." else "Starting service...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(devices) { device ->
                        DeviceCard(
                            device = device,
                            ckpService = ckpService,
                            onUnpair = { deviceManager?.requestUnpairing(device.id) }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceManager.Device,
    ckpService: CkpServiceManager?,
    onUnpair: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (device.type) {
                    "desktop" -> Icons.Default.Computer
                    "laptop" -> Icons.Default.Laptop
                    else -> Icons.Default.Devices
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        if (device.connected) append("Connected")
                        if (device.paired) {
                            if (device.connected) append(", ")
                            append("Paired")
                        }
                        if (!device.paired && !device.connected) append("Discovered")
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (device.connected) {
                IconButton(onClick = {
                    scope.launch {
                        ckpService?.ping(device.id, "Ping from ${ckpService?.deviceName ?: "phone"}")
                    }
                }) {
                    Icon(Icons.Default.Notifications, contentDescription = "Ping")
                }
            }
        }
    }
}
