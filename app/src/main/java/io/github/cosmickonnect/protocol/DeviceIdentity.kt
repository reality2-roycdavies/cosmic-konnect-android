package io.github.cosmickonnect.protocol

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Represents this device's identity for KDE Connect handshake.
 */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "phone",
    val protocolVersion: Int = NetworkPacket.PROTOCOL_VERSION,
    val incomingCapabilities: List<String>,
    val outgoingCapabilities: List<String>,
    val tcpPort: Int = 1716
) {
    companion object {
        private var cachedIdentity: DeviceIdentity? = null

        fun getIdentity(context: Context): DeviceIdentity {
            cachedIdentity?.let { return it }

            val deviceId = getDeviceId(context)
            val deviceName = getDeviceName()

            val capabilities = listOf(
                "kdeconnect.pair",
                "kdeconnect.ping",
                "kdeconnect.clipboard",
                "kdeconnect.clipboard.connect",
                "kdeconnect.notification",
                "kdeconnect.notification.request",
                "kdeconnect.share.request",
                "kdeconnect.findmyphone.request",
                "kdeconnect.battery",
                "kdeconnect.battery.request"
            )

            val identity = DeviceIdentity(
                deviceId = deviceId,
                deviceName = deviceName,
                incomingCapabilities = capabilities,
                outgoingCapabilities = capabilities
            )

            cachedIdentity = identity
            return identity
        }

        private fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("cosmic_konnect", Context.MODE_PRIVATE)
            var deviceId = prefs.getString("device_id", null)

            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString("device_id", deviceId).apply()
            }

            return deviceId
        }

        private fun getDeviceName(): String {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
        }
    }

    fun toIdentityPacket(): NetworkPacket {
        val body = buildJsonObject {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", deviceType)
            put("protocolVersion", protocolVersion)
            put("tcpPort", tcpPort)
            putJsonArray("incomingCapabilities") {
                incomingCapabilities.forEach { add(it) }
            }
            putJsonArray("outgoingCapabilities") {
                outgoingCapabilities.forEach { add(it) }
            }
        }

        return NetworkPacket(
            type = NetworkPacket.TYPE_IDENTITY,
            body = body
        )
    }
}
