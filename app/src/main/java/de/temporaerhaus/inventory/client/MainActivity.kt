package de.temporaerhaus.inventory.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import de.temporaerhaus.inventory.client.ui.InventoryApp
import de.temporaerhaus.inventory.client.ui.theme.TPHInventoryTheme
import de.temporaerhaus.inventory.client.util.TAG

class MainActivity : ComponentActivity() {
    companion object {
        const val SCAN_INTENT = "de.temporaerhaus.inventory.client.SCAN"
    }

    private val _barcodeState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var baseUrl = "http://localhost"
        try {
            val metaData = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
            baseUrl = metaData.getString("de.temporaerhaus.inventory.WIKI_BASE_URL", baseUrl)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }

        // Check if the app is running on a device with installed zebra datawedge:
        val isDataWedgeInstalled = try {
            packageManager.getPackageInfo("com.symbol.datawedge", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        setContent {
            TPHInventoryTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    InventoryApp(
                        modifier = Modifier.padding(innerPadding),
                        inventoryApi = InventoryApi(baseUrl),
                        barcodeBroadcastState = _barcodeState,
                        isHardwareScannerAvailable = isDataWedgeInstalled
                    )
                }
            }
        }

        if (isDataWedgeInstalled) {
            configureDataWedge()
        } else {
            Log.d(TAG, "Not a Zebra device, not enabling data wedge.")
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SCAN_INTENT) {
                val barcode = intent.getStringExtra("com.symbol.datawedge.data_string") ?: ""
                Log.d(TAG, "Received barcode: $barcode")
                _barcodeState.value = barcode
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val intentFilter = IntentFilter(SCAN_INTENT).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        ContextCompat.registerReceiver(this, barcodeReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        unregisterReceiver(barcodeReceiver)
        super.onPause()
    }

    fun configureDataWedge() {
        val USE_BROADCAST = 2

        val config = Bundle().apply {
            putString("PROFILE_NAME", "tph-inventory-client")
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf<Bundle?>(
                    Bundle().apply {
                        putString("PACKAGE_NAME", packageName)
                        putStringArray("ACTIVITY_LIST", arrayOf<String>("*"))
                    }
                )
            )
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(
                Bundle().apply {
                    putString("PLUGIN_NAME", "INTENT")
                    putString("RESET_CONFIG", "false")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("intent_output_enabled", "true")
                        putString("intent_action", SCAN_INTENT)
                        putString("intent_category", "android.intent.category.DEFAULT")
                        putInt("intent_delivery", USE_BROADCAST)
                    })
                },
                Bundle().apply {
                    putString("PLUGIN_NAME", "KEYSTROKE")
                    putString("RESET_CONFIG", "false")
                    putBundle("PARAM_LIST", Bundle().apply {
                        putString("keystroke_output_enabled", "false")
                    })
                }
            ))
        }

        val i = Intent().apply {
            setAction("com.symbol.datawedge.api.ACTION")
            putExtra("com.symbol.datawedge.api.SET_CONFIG", config)
        }
        this.sendBroadcast(i)
    }
}
