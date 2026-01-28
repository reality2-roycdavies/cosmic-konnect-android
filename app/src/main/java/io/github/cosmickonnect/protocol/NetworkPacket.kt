package io.github.cosmickonnect.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * KDE Connect network packet format.
 * All communication uses JSON packets terminated by newline.
 */
@Serializable
data class NetworkPacket(
    val id: Long = System.currentTimeMillis(),
    val type: String,
    val body: JsonObject = JsonObject(emptyMap()),
    @SerialName("payloadSize")
    val payloadSize: Long? = null,
    @SerialName("payloadTransferInfo")
    val payloadTransferInfo: JsonObject? = null
) {
    companion object {
        // Packet types
        const val TYPE_IDENTITY = "kdeconnect.identity"
        const val TYPE_PAIR = "kdeconnect.pair"
        const val TYPE_PING = "kdeconnect.ping"
        const val TYPE_CLIPBOARD = "kdeconnect.clipboard"
        const val TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
        const val TYPE_NOTIFICATION = "kdeconnect.notification"
        const val TYPE_NOTIFICATION_REQUEST = "kdeconnect.notification.request"
        const val TYPE_SHARE_REQUEST = "kdeconnect.share.request"
        const val TYPE_FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
        const val TYPE_BATTERY = "kdeconnect.battery"
        const val TYPE_BATTERY_REQUEST = "kdeconnect.battery.request"

        // Protocol version
        const val PROTOCOL_VERSION = 7

        // Default ports
        const val DEFAULT_TCP_PORT = 1716
        const val DEFAULT_UDP_PORT = 1716

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(jsonString: String): NetworkPacket {
            return json.decodeFromString(jsonString)
        }
    }

    fun toJson(): String {
        return json.encodeToString(serializer(), this)
    }
}
