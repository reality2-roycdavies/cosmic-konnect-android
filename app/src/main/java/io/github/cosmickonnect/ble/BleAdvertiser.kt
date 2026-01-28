package io.github.cosmickonnect.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.cosmickonnect.protocol.DeviceIdentity
import java.net.NetworkInterface

/**
 * BLE GATT Server and Advertiser for Cosmic Konnect.
 *
 * This class handles:
 * - Advertising the Cosmic Konnect GATT service
 * - Serving device information via GATT characteristics
 * - Handling connection requests from other devices
 */
class BleAdvertiser(
    private val context: Context,
    private val onConnectionRequest: (deviceId: String, deviceName: String, ipAddress: String) -> Unit
) {
    private val TAG = "BleAdvertiser"

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false

    // Device identity info
    private lateinit var deviceIdentity: DeviceIdentity

    /**
     * Initialize the BLE advertiser.
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth not supported on this device")
                return false
            }

            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "Bluetooth is not enabled")
                return false
            }

            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BLE advertising not supported")
                return false
            }

            deviceIdentity = DeviceIdentity.getIdentity(context)
            Log.i(TAG, "BLE Advertiser initialized for ${deviceIdentity.deviceName}")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BLE: ${e.message}")
            return false
        }
    }

    /**
     * Start advertising and GATT server.
     */
    fun startAdvertising(): Boolean {
        if (isAdvertising) {
            Log.w(TAG, "Already advertising")
            return true
        }

        try {
            // Start GATT server first
            if (!startGattServer()) {
                Log.e(TAG, "Failed to start GATT server")
                return false
            }

            // Configure advertising settings
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(BleConstants.ADVERTISE_TIMEOUT_MS)
                .build()

            // Advertising data - service UUID
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)  // Name goes in scan response
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

            // Scan response - device name
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            // Set a short local name for advertising
            val shortName = "${BleConstants.ADVERTISE_NAME_PREFIX}${deviceIdentity.deviceName.take(10)}"
            bluetoothAdapter?.setName(shortName)

            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)

            Log.i(TAG, "Started BLE advertising as '$shortName'")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for advertising: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}")
            return false
        }
    }

    /**
     * Stop advertising and GATT server.
     */
    fun stopAdvertising() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.i(TAG, "Stopped BLE advertising")
            }

            gattServer?.close()
            gattServer = null
            Log.i(TAG, "Closed GATT server")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping: ${e.message}")
        }
    }

    private fun startGattServer(): Boolean {
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return false
            }

            // Create the Cosmic Konnect service
            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Add characteristics
            service.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_DEVICE_ID))
            service.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_DEVICE_NAME))
            service.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_DEVICE_TYPE))
            service.addCharacteristic(createReadNotifyCharacteristic(BleConstants.CHAR_IP_ADDRESS))
            service.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_TCP_PORT))
            service.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_PROTOCOL_VERSION))
            service.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_CONNECTION_REQUEST))

            gattServer?.addService(service)
            Log.i(TAG, "GATT service added")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for GATT server: ${e.message}")
            return false
        }
    }

    private fun createReadCharacteristic(uuid: java.util.UUID): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
    }

    private fun createReadNotifyCharacteristic(uuid: java.util.UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Add CCCD for notifications
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)

        return characteristic
    }

    private fun createWriteCharacteristic(uuid: java.util.UUID): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    /**
     * Get current device IP addresses.
     */
    private fun getIpAddresses(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.mapNotNull { it.hostAddress }
                ?.joinToString(",")
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get IP addresses: ${e.message}")
            ""
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val error = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "BLE advertising failed: $error")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val state = if (newState == BluetoothGatt.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"
            Log.i(TAG, "GATT connection state: $state (device: ${device?.address})")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            try {
                val value = when (characteristic?.uuid) {
                    BleConstants.CHAR_DEVICE_ID -> deviceIdentity.deviceId
                    BleConstants.CHAR_DEVICE_NAME -> deviceIdentity.deviceName
                    BleConstants.CHAR_DEVICE_TYPE -> deviceIdentity.deviceType
                    BleConstants.CHAR_IP_ADDRESS -> getIpAddresses()
                    BleConstants.CHAR_TCP_PORT -> "1716"
                    BleConstants.CHAR_PROTOCOL_VERSION -> "7"
                    else -> null
                }

                if (value != null) {
                    val data = value.toByteArray(Charsets.UTF_8)
                    val responseData = if (offset < data.size) {
                        data.copyOfRange(offset, data.size)
                    } else {
                        ByteArray(0)
                    }

                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        responseData
                    )
                    Log.d(TAG, "Read request for ${characteristic?.uuid}: $value")
                } else {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied in read request: ${e.message}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            try {
                if (characteristic?.uuid == BleConstants.CHAR_CONNECTION_REQUEST && value != null) {
                    val request = String(value, Charsets.UTF_8)
                    Log.i(TAG, "Connection request received: $request")

                    // Parse the request (JSON format: {"deviceId":"...", "deviceName":"...", "ipAddress":"..."})
                    try {
                        val json = org.json.JSONObject(request)
                        val deviceId = json.getString("deviceId")
                        val deviceName = json.getString("deviceName")
                        val ipAddress = json.getString("ipAddress")

                        onConnectionRequest(deviceId, deviceName, ipAddress)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse connection request: ${e.message}")
                    }

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null
                        )
                    }
                } else if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied in write request: ${e.message}")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            try {
                if (descriptor?.uuid == BleConstants.CCCD_UUID) {
                    // Client enabling/disabling notifications
                    val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                    Log.d(TAG, "Notifications ${if (enabled) "enabled" else "disabled"} for ${descriptor.characteristic?.uuid}")

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied in descriptor write: ${e.message}")
            }
        }
    }
}
