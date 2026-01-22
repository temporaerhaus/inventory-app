package de.temporaerhaus.inventory.client.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SsidManager private constructor(private val application: Application) {

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                updateSsid()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _currentSsid.value = null
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "Network capabilities changed")
                updateSsid(networkCapabilities)
            }
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                updateSsid()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _currentSsid.value = null
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "Network capabilities changed")
                updateSsid(networkCapabilities)
            }
        }
    }

    init {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        updateSsid()
    }

    fun updateSsid(capabilities: NetworkCapabilities? = null) {
        if (ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing ACCESS_FINE_LOCATION for SSID detection")
            _currentSsid.value = null
            return
        }

        val wifiInfo: WifiInfo? = if (capabilities != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = capabilities.transportInfo as? WifiInfo
            Log.d(TAG, "WifiInfo from provided capabilities: $info")
            info
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            val info = caps?.transportInfo as? WifiInfo
            Log.d(TAG, "WifiInfo from active network caps (API 31+): $info")
            info
        } else {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            Log.d(TAG, "WifiInfo from wifiManager (Legacy): $info")
            info
        }

        if (wifiInfo != null) {
            var ssid = wifiInfo.ssid
            Log.d(TAG, "Extracted SSID: $ssid")
            if (ssid != null && ssid != "<unknown ssid>" && ssid != WifiManager.UNKNOWN_SSID) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                _currentSsid.value = ssid
                return
            }
        }
        
        // Fallback for cases where newer APIs fail to provide the SSID due to redaction or manufacturer quirks
        if (_currentSsid.value == null) {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            var ssid = info.ssid
            Log.d(TAG, "Fallback raw SSID: $ssid")
            if (ssid != null && ssid != "<unknown ssid>" && ssid != WifiManager.UNKNOWN_SSID) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                _currentSsid.value = ssid
            } else {
                _currentSsid.value = null
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SsidManager? = null

        fun getInstance(application: Application): SsidManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SsidManager(application).also { INSTANCE = it }
            }
        }
    }
}
