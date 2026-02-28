package io.github.reality2_roycdavies.cosmickonnect.ckp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

/**
 * mDNS/DNS-SD discovery for CKP.
 *
 * Uses Android's NsdManager to browse for `_cosmic-konnect._tcp.` services
 * on the local network. This is compatible with the daemon's mDNS advertising
 * via mdns-sd.
 *
 * Also registers our own service so the daemon can discover us.
 */
class MdnsDiscovery(
    private val context: Context,
    private val ourIdentity: Identity,
    private val onDeviceDiscovered: (DiscoveredDevice) -> Unit
) {
    private val TAG = "MdnsDiscovery"

    companion object {
        /** mDNS service type - must match daemon's `_cosmic-konnect._tcp.local.` */
        private const val SERVICE_TYPE = "_cosmic-konnect._tcp."
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isDiscovering = false
    private var isRegistered = false

    /**
     * Start mDNS discovery and service registration.
     */
    fun start() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.e(TAG, "NSD service not available")
            return
        }

        registerService()
        startDiscovery()
    }

    /**
     * Stop mDNS discovery and unregister service.
     */
    fun stop() {
        stopDiscovery()
        unregisterService()
        nsdManager = null
    }

    /**
     * Register our own service so the daemon can discover us via mDNS.
     */
    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "cosmic-konnect-${ourIdentity.deviceId.takeLast(8)}"
            serviceType = SERVICE_TYPE
            port = Protocol.TCP_PORT
            setAttribute("id", ourIdentity.deviceId)
            setAttribute("name", ourIdentity.name)
            setAttribute("type", ourIdentity.deviceType.value)
            setAttribute("protocol", Protocol.VERSION.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRegistered = true
                Log.i(TAG, "mDNS service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.e(TAG, "mDNS registration failed: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRegistered = false
                Log.i(TAG, "mDNS service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: error $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service: ${e.message}")
        }
    }

    /**
     * Unregister our mDNS service.
     */
    private fun unregisterService() {
        if (isRegistered) {
            try {
                registrationListener?.let { nsdManager?.unregisterService(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister mDNS service: ${e.message}")
            }
        }
        registrationListener = null
    }

    /**
     * Start browsing for other Cosmic Konnect services.
     */
    private fun startDiscovery() {
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering = true
                Log.i(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Log.i(TAG, "mDNS discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS service found: ${serviceInfo.serviceName}")
                // Resolve to get IP and port
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.e(TAG, "mDNS discovery start failed: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS discovery stop failed: error $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery: ${e.message}")
        }
    }

    /**
     * Stop browsing.
     */
    private fun stopDiscovery() {
        if (isDiscovering) {
            try {
                discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop mDNS discovery: ${e.message}")
            }
        }
        discoveryListener = null
    }

    /**
     * Resolve a discovered service to get its IP address, port, and TXT records.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS resolve failed for ${info.serviceName}: error $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host ?: return
                val port = info.port

                // Extract TXT record attributes
                val attributes = info.attributes
                val deviceId = attributes["id"]?.let { String(it, Charsets.UTF_8) }
                val deviceName = attributes["name"]?.let { String(it, Charsets.UTF_8) }
                val deviceTypeStr = attributes["type"]?.let { String(it, Charsets.UTF_8) }

                // Filter out our own service
                if (deviceId == ourIdentity.deviceId) {
                    Log.d(TAG, "Skipping own mDNS service")
                    return
                }

                if (deviceId == null || deviceName == null) {
                    Log.w(TAG, "mDNS service missing required attributes: ${info.serviceName}")
                    return
                }

                val deviceType = DeviceType.fromValue(deviceTypeStr ?: "desktop")

                Log.i(TAG, "mDNS resolved: $deviceName ($deviceId) at ${host.hostAddress}:$port")

                val device = DiscoveredDevice(
                    deviceId = deviceId,
                    name = deviceName,
                    deviceType = deviceType,
                    address = host,
                    tcpPort = port,
                    capabilities = listOf(
                        Capability.CLIPBOARD,
                        Capability.FILES,
                        Capability.NOTIFICATIONS,
                        Capability.FIND_DEVICE,
                        Capability.SHARE
                    )
                )

                onDeviceDiscovered(device)
            }
        })
    }
}
