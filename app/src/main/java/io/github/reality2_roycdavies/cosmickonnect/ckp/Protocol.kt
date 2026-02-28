package io.github.reality2_roycdavies.cosmickonnect.ckp

/**
 * Cosmic Konnect Protocol (CKP) Constants
 */
object Protocol {
    /** Protocol version */
    const val VERSION: Byte = 1

    /** Magic bytes "CK" */
    val MAGIC = byteArrayOf(0x43, 0x4B)

    /** UDP discovery port */
    const val UDP_DISCOVERY_PORT = 17160

    /** TCP connection port */
    const val TCP_PORT = 17161

    // BLE UUIDs are defined in ble/BleConstants.kt

    /** Wi-Fi Direct service type */
    const val WIFI_DIRECT_SERVICE_TYPE = "_cosmickonnect._tcp"

    /** Limits */
    const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB
    const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
    const val DISCOVERY_INTERVAL_MS = 5000L
    const val CONNECTION_TIMEOUT_MS = 30000L
    const val KEEPALIVE_INTERVAL_MS = 60000L
}

/**
 * Message type identifiers
 */
object MessageType {
    const val IDENTITY: Byte = 0x01
    const val PAIR_REQUEST: Byte = 0x02
    const val PAIR_RESPONSE: Byte = 0x03
    const val PAIR_CONFIRM: Byte = 0x04
    const val PING: Byte = 0x10
    const val PONG: Byte = 0x11
    const val CLIPBOARD: Byte = 0x20
    const val NOTIFICATION: Byte = 0x30
    const val NOTIFICATION_ACTION: Byte = 0x31
    const val FILE_OFFER: Byte = 0x40
    const val FILE_ACCEPT: Byte = 0x41
    const val FILE_REJECT: Byte = 0x42
    const val FILE_CHUNK: Byte = 0x43
    const val FILE_COMPLETE: Byte = 0x44
    const val FIND_DEVICE: Byte = 0x50
    const val SHARE_URL: Byte = 0x60
    const val SHARE_TEXT: Byte = 0x61
    const val MEDIA_CONTROL: Byte = 0x70
    const val MEDIA_INFO: Byte = 0x71
    const val REMOTE_INPUT: Byte = 0x80.toByte()
    const val DISCONNECT: Byte = 0xF0.toByte()
    const val ERROR: Byte = 0xFF.toByte()
}

/**
 * Message header flags
 */
data class MessageFlags(
    val encrypted: Boolean = false,
    val compressed: Boolean = false,
    val response: Boolean = false,
    val error: Boolean = false
) {
    fun toByte(): Byte {
        var flags = 0
        if (encrypted) flags = flags or 0x01
        if (compressed) flags = flags or 0x02
        if (response) flags = flags or 0x04
        if (error) flags = flags or 0x08
        return flags.toByte()
    }

    companion object {
        fun fromByte(byte: Byte): MessageFlags {
            val b = byte.toInt() and 0xFF
            return MessageFlags(
                encrypted = (b and 0x01) != 0,
                compressed = (b and 0x02) != 0,
                response = (b and 0x04) != 0,
                error = (b and 0x08) != 0
            )
        }
    }
}

/**
 * Device types
 */
enum class DeviceType(val value: String) {
    DESKTOP("desktop"),
    LAPTOP("laptop"),
    PHONE("phone"),
    TABLET("tablet"),
    TV("tv");

    companion object {
        fun fromValue(value: String): DeviceType {
            return entries.find { it.value == value } ?: PHONE
        }
    }
}

/**
 * Capabilities that a device supports
 */
enum class Capability(val value: String) {
    CLIPBOARD("clipboard"),
    FILES("files"),
    NOTIFICATIONS("notifications"),
    FIND_DEVICE("findDevice"),
    SHARE("share"),
    MEDIA("media"),
    REMOTE_INPUT("remoteInput");

    companion object {
        fun fromValue(value: String): Capability? {
            return entries.find { it.value == value }
        }
    }
}
