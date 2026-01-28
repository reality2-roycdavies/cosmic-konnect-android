package io.github.cosmickonnect.ble

import java.util.UUID

/**
 * BLE GATT Service and Characteristic UUIDs for Cosmic Konnect.
 *
 * These are custom UUIDs using valid hexadecimal values.
 * Pattern: c05a1c00-a0aa-3c70-XXXX-000000000001
 * (mnemonic: c05a1c = cosmic, a0aa = konn, 3c70 = ect0)
 *
 * The service advertises device identity and connection information,
 * allowing nearby devices to discover each other without WiFi.
 */
object BleConstants {

    /**
     * Main Cosmic Konnect GATT Service UUID.
     * Devices advertise this service to be discoverable.
     */
    val SERVICE_UUID: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0000-000000000001")

    /**
     * Device ID characteristic - unique identifier for the device.
     * Properties: Read
     * Format: UTF-8 string (e.g., "e379b2bb_dec9_4887_b0f5_7641348dda2c")
     */
    val CHAR_DEVICE_ID: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0001-000000000001")

    /**
     * Device Name characteristic - human-readable device name.
     * Properties: Read
     * Format: UTF-8 string (e.g., "RoysTux" or "Samsung SM-S921B")
     */
    val CHAR_DEVICE_NAME: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0002-000000000001")

    /**
     * Device Type characteristic - type of device.
     * Properties: Read
     * Format: UTF-8 string ("phone", "tablet", "desktop", "laptop", "tv")
     */
    val CHAR_DEVICE_TYPE: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0003-000000000001")

    /**
     * IP Address characteristic - current IP address(es) for TCP connection.
     * Properties: Read, Notify
     * Format: UTF-8 string, comma-separated IPs (e.g., "192.168.1.50,10.42.0.82")
     */
    val CHAR_IP_ADDRESS: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0004-000000000001")

    /**
     * TCP Port characteristic - port for KDE Connect protocol.
     * Properties: Read
     * Format: UTF-8 string (e.g., "1716")
     */
    val CHAR_TCP_PORT: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0005-000000000001")

    /**
     * Protocol Version characteristic.
     * Properties: Read
     * Format: UTF-8 string (e.g., "7")
     */
    val CHAR_PROTOCOL_VERSION: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0006-000000000001")

    /**
     * Connection Request characteristic - write to initiate connection.
     * Properties: Write
     * Format: UTF-8 JSON with requester's info
     */
    val CHAR_CONNECTION_REQUEST: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0007-000000000001")

    /**
     * Hotspot SSID characteristic - WiFi hotspot name for direct connection.
     * Properties: Read
     * Format: UTF-8 string (e.g., "CosmicKonnect")
     * Empty string means no hotspot available.
     */
    val CHAR_HOTSPOT_SSID: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0008-000000000001")

    /**
     * Hotspot Password characteristic - WiFi hotspot password.
     * Properties: Read
     * Format: UTF-8 string
     */
    val CHAR_HOTSPOT_PASSWORD: UUID = UUID.fromString("c05a1c00-a0aa-3c70-0009-000000000001")

    /**
     * Client Characteristic Configuration Descriptor UUID.
     * Standard UUID for enabling notifications.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Manufacturer ID for BLE advertising data.
     * Using 0xFFFF (reserved for testing/development).
     * For production, register with Bluetooth SIG.
     */
    const val MANUFACTURER_ID = 0xFFFF

    /**
     * Advertising name prefix for filtering.
     */
    const val ADVERTISE_NAME_PREFIX = "CK-"

    /**
     * BLE scan timeout in milliseconds.
     */
    const val SCAN_TIMEOUT_MS = 30_000L

    /**
     * BLE advertising timeout (0 = no timeout).
     */
    const val ADVERTISE_TIMEOUT_MS = 0
}
