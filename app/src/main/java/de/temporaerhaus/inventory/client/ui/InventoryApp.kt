package de.temporaerhaus.inventory.client.ui

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.net.toUri
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
import de.temporaerhaus.inventory.client.util.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryApp(
    modifier: Modifier = Modifier,
    inventoryApi: InventoryApi,
    barcodeBroadcastState: MutableState<String>? = null,
    isHardwareScannerAvailable: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var inventoryNumber by rememberSaveable { mutableStateOf("") }
    var item by remember { mutableStateOf<InventoryItem?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var needsSaving by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var autoSave by rememberSaveable {  mutableStateOf(true) }
    var lastContainerItem by remember { mutableStateOf<InventoryItem?>(null) }
    var locationMode by remember { mutableStateOf(LocationMode.Temporary) }
    var lastItemWasScanned by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while(true) {
            now.value = LocalDateTime.now()
            kotlinx.coroutines.delay(1000) // Update every second
            yield()
        }
    }

    fun search() {
        errorMessage = null
        item = null
        isSaving = false
        needsSaving = false
        saved = false
        coroutineScope.launch(Dispatchers.IO) {
            try {
                item = inventoryApi.getInventoryData(inventoryNumber)
                if (item?.data?.getOrDefault("container", false) == true) {
                    lastContainerItem = item
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

    LaunchedEffect(barcodeBroadcastState?.value) {
        barcodeBroadcastState?.value?.let { barcode ->
            if (barcode.isNotEmpty() && barcode != inventoryNumber) {
                inventoryNumber = barcode
                lastItemWasScanned = true
                search()
            }
        }
    }

    fun scanBarcode() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { result ->
                    inventoryNumber = result.rawValue ?: ""
                    lastItemWasScanned = true
                    search()
                }
                .addOnCanceledListener {
                    // User canceled the scan
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting barcode scan: ${e.message}")
        }
    }

    fun markAsSeen() {
        if (!isSaving && !saved) {
            isSaving = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val newItem = inventoryApi.writeAsSeen(item!!, lastContainerItem, locationMode)
                    if (newItem != null) {
                        item = newItem
                        saved = true
                        needsSaving = false
                        if (!lastItemWasScanned) {
                            // after saving, focus the text field again
                            focusRequester.requestFocus()
                        }
                    }
                } catch (e: IOException) {
                    errorMessage = e.message
                } finally {
                    isSaving = false
                }
            }
        }
    }

    fun openInBrowser() {
        val fullUrl = "${inventoryApi.baseUrl}inventar/${item?.number}"
        Log.d(TAG, "Open in browser: ${fullUrl}")
        val intent = Intent(Intent.ACTION_VIEW, fullUrl.toUri())
        context.startActivity(intent)
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

    val keyboardVisible = WindowInsets.isImeVisible

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        value = inventoryNumber,
                        onValueChange = {
                            inventoryNumber = it
                            errorMessage = null
                        },
                        label = { Text("Inventory number") },
                        trailingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.barcode_scanner_24),
                                contentDescription = "Scan barcode",
                                modifier = Modifier.clickable {
                                    scanBarcode()
                                }
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                lastItemWasScanned = false
                                search()
                            }
                        ),
                    )
                    if (!isHardwareScannerAvailable) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    Button(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(top = 4.dp),
                        onClick = {
                            lastItemWasScanned = false
                            search()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search_24),
                            contentDescription = "Search"
                        )
                    }
                }

                if (errorMessage != null) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                            softWrap = true
                        )
                    }
                }
            }

            if (item?.data != null) {
                Text(
                    text = "${item?.name ?: "Unknown Item"}:",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
                    softWrap = true
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (item != null) ItemDataLines(
                            item = item!!,
                            now = now,
                            onItemNumberClicked = { openItemFromDescription(it) },
                            onRemoveLocationClicked = { key ->
                                Log.d(TAG, "remove location: $key")
                                val newItemData = item!!.data!!.toMutableMap()
                                when (key) {
                                    "temporary.location" -> newItemData["temporary"] = mapOf<String, Any>()
                                    "nominal.location" -> newItemData["nominal"] = mapOf<String, Any>()
                                    else -> Log.e(TAG, "key $key unknown")
                                }
                                item = item!!.copy(data = newItemData)
                                needsSaving = true
                            }
                        )
                    }
                }
            } else {
                val emptyStateText = "Inventory Client v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Instance: ${inventoryApi.baseUrl}"
                Spacer(modifier = Modifier.weight(0.5f))
                Text(
                    text = emptyStateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.weight(1f))
            }


            if (item?.data != null) {
                // Add padding at the bottom when keyboard is visible
                val buttonBottomPadding = if (keyboardVisible)
                    256.dp // Window.ime didn't work for some reason
                else
                    8.dp

                if (lastContainerItem != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val locationText =
                            if (lastContainerItem!!.number == item!!.number) {
                                buildAnnotatedString {
                                    append("The next scans will be ")
                                    withLink(
                                        link = LinkAnnotation.Clickable(
                                            tag = "TAG",
                                            styles = TextLinkStyles(
                                                style = SpanStyle(
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ),
                                            linkInteractionListener = {
                                                if (locationMode == LocationMode.Temporary) {
                                                    locationMode = LocationMode.Nominal
                                                } else {
                                                    locationMode = LocationMode.Temporary
                                                }
                                            },
                                        ),
                                    ) {
                                        if (locationMode == LocationMode.Nominal) {
                                            append("permanently")
                                        } else {
                                            append("temporarily")
                                        }
                                        append(" located")
                                    }
                                    append(" at ")
                                    withLink(
                                        link = LinkAnnotation.Clickable(
                                            tag = "TAG",
                                            styles = TextLinkStyles(
                                                style = SpanStyle(
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ),
                                            linkInteractionListener = {
                                                openItemFromDescription(lastContainerItem!!.number)
                                            },
                                        ),
                                    ) {
                                        append(lastContainerItem!!.name)
                                    }
                                }
                            } else {
                                buildAnnotatedString {
                                    append("Set ")
                                    withLink(
                                        link = LinkAnnotation.Clickable(
                                            tag = "TAG",
                                            styles = TextLinkStyles(
                                                style = SpanStyle(
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ),
                                            linkInteractionListener = {
                                                if (locationMode == LocationMode.Temporary) {
                                                    locationMode = LocationMode.Nominal
                                                } else {
                                                    locationMode = LocationMode.Temporary
                                                }
                                            },
                                        ),
                                    ) {
                                        if (locationMode == LocationMode.Nominal) {
                                            append("permanent")
                                        } else {
                                            append("temporary")
                                        }
                                        append(" location")
                                    }
                                    append(" to ")
                                    withLink(
                                        link = LinkAnnotation.Clickable(
                                            tag = "TAG",
                                            styles = TextLinkStyles(
                                                style = SpanStyle(
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ),
                                            linkInteractionListener = {
                                                openItemFromDescription(lastContainerItem!!.number)
                                            },
                                        ),
                                    ) {
                                        append("${lastContainerItem!!.number} (${lastContainerItem!!.name})")
                                    }
                                }
                            }

                        Text(
                            text = locationText,
                            softWrap = true,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        IconButton(
                            onClick = ::forgetLastContainerItem,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.close_24),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                contentDescription = "Remove",
                                modifier = Modifier
                                    .size(24.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 3.dp,
                            bottom = buttonBottomPadding,
                            start = 16.dp,
                            end = 16.dp
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::openInBrowser) {
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
                        onMarkAsSeen = ::markAsSeen,
                        autoSave = autoSave
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = ::toggleAutoSave) {
                        Icon(
                            painter = painterResource(if (autoSave) R.drawable.time_auto_24 else R.drawable.timer_off_24),
                            contentDescription = "Auto save",
                        )
                    }
                }
            }
        }
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
