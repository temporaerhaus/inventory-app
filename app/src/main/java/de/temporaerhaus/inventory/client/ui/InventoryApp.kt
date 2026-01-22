package de.temporaerhaus.inventory.client.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.temporaerhaus.inventory.client.BuildConfig
import de.temporaerhaus.inventory.client.InventoryApi
import de.temporaerhaus.inventory.client.R
import de.temporaerhaus.inventory.client.model.InventoryItem
import de.temporaerhaus.inventory.client.model.LocationMode
import de.temporaerhaus.inventory.client.ui.components.ItemDataLines
import de.temporaerhaus.inventory.client.ui.components.MarkAsSeenButton
import de.temporaerhaus.inventory.client.ui.theme.TPHInventoryTheme
import de.temporaerhaus.inventory.client.util.INTERNAL_SSIDS
import de.temporaerhaus.inventory.client.util.SsidManager
import de.temporaerhaus.inventory.client.util.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import androidx.core.content.edit

class InventoryViewModel(
    private val inventoryApi: InventoryApi,
    private val ssidManager: SsidManager,
    application: Application
) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = application.getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)

    var inventoryNumber by mutableStateOf("")
    var item by mutableStateOf<InventoryItem?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var needsSaving by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var autoSave by mutableStateOf(true)
    var lastContainerItem by mutableStateOf<InventoryItem?>(null)
    var locationMode by mutableStateOf(LocationMode.Temporary)
    var lastItemWasScanned by mutableStateOf(false)
    val currentSsid = ssidManager.currentSsid
    var authToken by mutableStateOf(prefs.getString("auth_token", "") ?: "")
    var draftToken by mutableStateOf("")
    var showTokenDialog by mutableStateOf(false)
    var now: LocalDateTime by mutableStateOf(LocalDateTime.now())
        private set

    init {
        viewModelScope.launch {
            while (true) {
                now = LocalDateTime.now()
                delay(1000)
            }
        }
    }

    fun isInternalNetwork(): Boolean {
        val ssid = currentSsid.value
        return ssid != null && INTERNAL_SSIDS.contains(ssid)
    }

    fun saveAuthToken(token: String) {
        authToken = token
        val prefs = getApplication<Application>().getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("auth_token", token) }
    }

    fun search() {
        errorMessage = null
        item = null
        isSaving = false
        needsSaving = false
        saved = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetchedItem = withContext(Dispatchers.IO) {
                    inventoryApi.getInventoryData(inventoryNumber)
                }
                item = fetchedItem
                if (fetchedItem.data?.getOrDefault("container", false) == true) {
                    lastContainerItem = fetchedItem
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error getting data: ${e.message}")
                errorMessage = e.message
            } catch (e: Exception) {
                Log.e(TAG, "Error getting data: ${e.message}")
                errorMessage = "Error getting data: ${e.message}"
            }
        }
    }

    fun onBarcodeScanned(barcode: String) {
        if (barcode.isEmpty()) return

        if (showTokenDialog) {
            draftToken = barcode
        } else if (barcode != inventoryNumber) {
            inventoryNumber = barcode
            lastItemWasScanned = true
            search()
        }
    }

    fun markAsSeen(onFinished: () -> Unit = {}) {
        val currentItem = item ?: return
        if (!isSaving && !saved) {
            isSaving = true
            viewModelScope.launch {
                try {
                    val newItem = withContext(Dispatchers.IO) {
                        inventoryApi.writeAsSeen(currentItem, lastContainerItem, locationMode)
                    }
                    if (newItem != null) {
                        item = newItem
                        saved = true
                        needsSaving = false
                        onFinished()
                    }
                } catch (e: IOException) {
                    errorMessage = e.message
                } finally {
                    isSaving = false
                }
            }
        }
    }

    fun toggleAutoSave() {
        autoSave = !autoSave
    }

    fun forgetLastContainerItem() {
        lastContainerItem = null
    }

    fun openItemFromDescription(number: String) {
        inventoryNumber = number
        lastItemWasScanned = false
        search()
    }

    fun removeLocation(key: String) {
        item?.let { currentItem ->
            Log.d(TAG, "remove location: $key")
            val newItemData = currentItem.data?.toMutableMap() ?: mutableMapOf()
            when (key) {
                "temporary.location" -> newItemData["temporary"] = mapOf<String, Any>()
                "nominal.location" -> newItemData["nominal"] = mapOf<String, Any>()
                else -> Log.e(TAG, "key $key unknown")
            }
            item = currentItem.copy(data = newItemData)
            needsSaving = true
        }
    }

    fun toggleLocationMode() {
        locationMode = if (locationMode == LocationMode.Temporary) {
            LocationMode.Nominal
        } else {
            LocationMode.Temporary
        }
    }

    companion object {
        fun provideFactory(inventoryApi: InventoryApi, application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val ssidManager = SsidManager.getInstance(application)
                InventoryViewModel(inventoryApi, ssidManager, application)
            }
        }
    }
}

@Composable
fun InventoryApp(
    modifier: Modifier = Modifier,
    inventoryApi: InventoryApi,
    barcodeBroadcastState: MutableState<String>? = null,
    isHardwareScannerAvailable: Boolean = false,
    viewModel: InventoryViewModel = viewModel(factory = InventoryViewModel.provideFactory(
        inventoryApi, 
        LocalContext.current.applicationContext as Application
    ))
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val currentSsid by viewModel.currentSsid.collectAsState()
    val ssidManager = remember { SsidManager.getInstance(context.applicationContext as Application) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ssidManager.updateSsid()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(barcodeBroadcastState?.value) {
        barcodeBroadcastState?.value?.let { viewModel.onBarcodeScanned(it) }
    }

    if (!isHardwareScannerAvailable) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    if (viewModel.showTokenDialog) {
        TokenDialog(
            token = viewModel.draftToken,
            onTokenChange = { viewModel.draftToken = it },
            onDismiss = { viewModel.showTokenDialog = false },
            onSave = {
                viewModel.saveAuthToken(viewModel.draftToken)
                viewModel.showTokenDialog = false
            }
        )
    }

    fun scanBarcode() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { result ->
                    viewModel.onBarcodeScanned(result.rawValue ?: "")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting barcode scan: ${e.message}")
        }
    }

    fun openInBrowser() {
        viewModel.item?.let { item ->
            val fullUrl = "${inventoryApi.baseUrl}inventar/${item.number}"
            Log.d(TAG, "Open in browser: $fullUrl")
            val intent = Intent(Intent.ACTION_VIEW, fullUrl.toUri())
            context.startActivity(intent)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            InventorySearchBar(
                inventoryNumber = viewModel.inventoryNumber,
                onValueChange = {
                    viewModel.inventoryNumber = it
                    viewModel.errorMessage = null
                },
                onSearch = {
                    viewModel.lastItemWasScanned = false
                    viewModel.search()
                },
                onScan = ::scanBarcode,
                textFieldModifier = Modifier.focusRequester(focusRequester)
            )

            viewModel.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    softWrap = true
                )
            }
        }

        val currentItem = viewModel.item
        if (currentItem?.data != null) {
            ItemContent(
                item = currentItem,
                now = viewModel.now,
                onItemNumberClicked = { viewModel.openItemFromDescription(it) },
                onRemoveLocationClicked = { viewModel.removeLocation(it) },
                modifier = Modifier.weight(1f)
            )

            if (viewModel.lastContainerItem != null) {
                LocationBanner(
                    lastContainerItem = viewModel.lastContainerItem!!,
                    currentItem = currentItem,
                    locationMode = viewModel.locationMode,
                    onToggleLocationMode = { viewModel.toggleLocationMode() },
                    onOpenContainer = { viewModel.openItemFromDescription(it) },
                    onDismiss = { viewModel.forgetLastContainerItem() }
                )
            }

            InventoryActionBar(
                onOpenInBrowser = ::openInBrowser,
                needsSaving = viewModel.needsSaving,
                isSaving = viewModel.isSaving,
                saved = viewModel.saved,
                autoSave = viewModel.autoSave,
                onMarkAsSeen = {
                    viewModel.markAsSeen(onFinished = {
                        if (!viewModel.lastItemWasScanned) {
                            focusRequester.requestFocus()
                        }
                    })
                },
                onToggleAutoSave = { viewModel.toggleAutoSave() },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            EmptyState(
                baseUrl = inventoryApi.baseUrl,
                ssid = currentSsid,
                isInternal = viewModel.isInternalNetwork(),
                onEditToken = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    viewModel.draftToken = viewModel.authToken
                    viewModel.showTokenDialog = true
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TokenDialog(
    token: String,
    onTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Authentication Token") },
        text = {
            Column {
                Text("Enter the bearer token for DokuWiki API access.")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text("Bearer Token") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun InventorySearchBar(
    inventoryNumber: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    textFieldModifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier
                .weight(1f)
                .then(textFieldModifier),
            singleLine = true,
            value = inventoryNumber,
            onValueChange = onValueChange,
            label = { Text("Inventory number") },
            trailingIcon = {
                IconButton(onClick = onScan) {
                    Icon(
                        painter = painterResource(R.drawable.barcode_scanner_24),
                        contentDescription = "Scan barcode"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            modifier = Modifier.padding(top = 4.dp),
            onClick = onSearch
        ) {
            Icon(
                painter = painterResource(R.drawable.search_24),
                contentDescription = "Search"
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun InventorySearchBarPreview() {
    TPHInventoryTheme {
        InventorySearchBar(
            inventoryNumber = "V-XY12345",
            onValueChange = {},
            onSearch = {},
            onScan = {}
        )
    }
}

@Composable
fun ItemContent(
    item: InventoryItem,
    now: LocalDateTime,
    onItemNumberClicked: (String) -> Unit,
    onRemoveLocationClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "${item.name ?: "Unknown Item"}:",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
            softWrap = true
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ItemDataLines(
                    item = item,
                    now = now,
                    onItemNumberClicked = onItemNumberClicked,
                    onRemoveLocationClicked = onRemoveLocationClicked
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    baseUrl: String,
    ssid: String?,
    isInternal: Boolean,
    onEditToken: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val emptyStateText = "Inventory Client v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Instance: $baseUrl\n" +
                "WiFi: ${ssid ?: "<Disconnected>"}"
        
        Text(
            text = emptyStateText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            softWrap = true,
            modifier = Modifier.padding(16.dp)
        )

        if (!isInternal) {
            Button(onClick = onEditToken) {
                Text("Edit Authentication Token")
            }
        }
    }
}

@Composable
fun LocationBanner(
    lastContainerItem: InventoryItem,
    currentItem: InventoryItem,
    locationMode: LocationMode,
    onToggleLocationMode: () -> Unit,
    onOpenContainer: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
        val isSelf = lastContainerItem.number == currentItem.number

        val locationText = buildAnnotatedString {
            if (isSelf) {
                append("The next scans will be ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "mode",
                        styles = linkStyle,
                        linkInteractionListener = { onToggleLocationMode() }
                    )
                ) {
                    append(if (locationMode == LocationMode.Nominal) "permanently" else "temporarily")
                    append(" located")
                }
                append(" at ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "container",
                        styles = linkStyle,
                        linkInteractionListener = { onOpenContainer(lastContainerItem.number) }
                    )
                ) {
                    append(lastContainerItem.name)
                }
            } else {
                append("Set ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "mode",
                        styles = linkStyle,
                        linkInteractionListener = { onToggleLocationMode() }
                    )
                ) {
                    append(if (locationMode == LocationMode.Nominal) "permanent" else "temporary")
                    append(" location")
                }
                append(" to ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "container",
                        styles = linkStyle,
                        linkInteractionListener = { onOpenContainer(lastContainerItem.number) }
                    )
                ) {
                    append("${lastContainerItem.number} (${lastContainerItem.name})")
                }
            }
        }

        Text(
            text = locationText,
            softWrap = true,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onPrimary
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.close_24),
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LocationBannerItemPreview() {
    val container = InventoryItem(number = "V-XY100", name = "Toolbox", data = mapOf("container" to true))
    val item = InventoryItem(number = "V-XY101", name = "Hammer", data = emptyMap())
    TPHInventoryTheme {
        LocationBanner(
            lastContainerItem = container,
            currentItem = item,
            locationMode = LocationMode.Temporary,
            onToggleLocationMode = {},
            onOpenContainer = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LocationBannerContainerPreview() {
    val container = InventoryItem(number = "V-XY100", name = "Toolbox", data = mapOf("container" to true))
    TPHInventoryTheme {
        LocationBanner(
            lastContainerItem = container,
            currentItem = container,
            locationMode = LocationMode.Nominal,
            onToggleLocationMode = {},
            onOpenContainer = {},
            onDismiss = {}
        )
    }
}

@Composable
fun InventoryActionBar(
    onOpenInBrowser: () -> Unit,
    needsSaving: Boolean,
    isSaving: Boolean,
    saved: Boolean,
    autoSave: Boolean,
    onMarkAsSeen: () -> Unit,
    onToggleAutoSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenInBrowser) {
            Icon(
                painter = painterResource(R.drawable.open_in_new_24),
                contentDescription = "Open in Browser",
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        MarkAsSeenButton(
            needsSaving = needsSaving,
            isSaving = isSaving,
            saved = saved,
            onMarkAsSeen = onMarkAsSeen,
            autoSave = autoSave
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleAutoSave) {
            Icon(
                painter = painterResource(if (autoSave) R.drawable.time_auto_24 else R.drawable.timer_off_24),
                contentDescription = "Auto save",
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun InventoryActionBarPreview() {
    TPHInventoryTheme {
        InventoryActionBar(
            onOpenInBrowser = {},
            needsSaving = false,
            isSaving = false,
            saved = false,
            autoSave = true,
            onMarkAsSeen = {},
            onToggleAutoSave = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun InventoryAppPreview() {
    TPHInventoryTheme {
        InventoryApp(
            inventoryApi = InventoryApi("http://localhost")
        )
    }
}
