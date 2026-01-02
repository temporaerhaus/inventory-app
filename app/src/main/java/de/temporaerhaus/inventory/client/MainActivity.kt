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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.temporaerhaus.inventory.client.ui.theme.TPHInventoryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

const val TAG = "TPHInventoryClient"

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

data class InventoryItem(
    val number: String,
    val name: String?,
    val data: Map<String, Any>?
)

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
    var isSaving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var autoSave by rememberSaveable {  mutableStateOf(false) }
    var lastContainerItem by remember { mutableStateOf<InventoryItem?>(null) }
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
                    val newItem = inventoryApi.writeAsSeen(item!!, lastContainerItem)
                    if (newItem != null) {
                        item = newItem
                        saved = true
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
        val fullUrl = "${inventoryApi.baseUrl}/inventar/${item?.number}"
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
                            imageVector = Icons.Filled.Search,
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
                                "The next scans will be located at ${lastContainerItem!!.name}"
                            } else {
                                "Set current location to ${lastContainerItem!!.number} (${lastContainerItem!!.name})"
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
                                imageVector = Icons.Filled.Close,
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
                            painter = painterResource(R.drawable.open_in_browser_24),
                            contentDescription = "Open in Browser",
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    MarkAsSeenButton(
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


@Composable
fun ItemDataLines(item: InventoryItem,
                  now: MutableState<LocalDateTime>,
                  onItemNumberClicked: (number: String) -> Unit,
                  onRemoveLocationClicked: (key: String) -> Unit) {

    @Composable
    fun renderNestedData(data: Map<String, Any?>, indent: Int = 0, onRemoveLocationClicked: (key: String) -> Unit) {
        val INDENT_SIZE = 12
        data.forEach { (key, value) ->
            if (key in listOf("inventory") || value == null || value.toString().isBlank()) {
                return@forEach
            }

            if (value is Map<*, *>) {
                if (value.isEmpty()) {
                    Text(
                        text = "$key: {}",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                    )
                    return@forEach
                }
                Text(
                    text = "$key: { ",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                )
                @Suppress("UNCHECKED_CAST")
                renderNestedData(value as Map<String, Any?>, indent + 1, { k -> onRemoveLocationClicked("$key.$k") })
                Text(
                    text = "}",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (key == "location") {
                        val text = buildAnnotatedString {
                            append("$key: ")
                            withLink(
                                link = LinkAnnotation.Clickable(
                                    tag = "TAG",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            textDecoration = TextDecoration.Underline
                                        )
                                    ),
                                    linkInteractionListener = {
                                        onItemNumberClicked(value.toString())
                                    },
                                ),
                            ) {
                                append(value.toString())
                            }
                        }
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(start = (indent * INDENT_SIZE).dp)
                        )
                        IconButton(
                            onClick = {
                                onRemoveLocationClicked(key)
                            },
                            modifier = Modifier.padding(start = 8.dp).size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove Location",
                                modifier = Modifier
                                    .size(24.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "$key: $value",
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                        )
                    }
                    if (key == "container") {
                        Icon(
                            painter = painterResource(R.drawable.package_variant_24),
                            contentDescription = "Package",
                            modifier = Modifier
                                .size(20.dp)
                                .padding(start = 4.dp)
                                .alpha(0.6f)
                        )
                    }
                }
                if (key in listOf("date", "lastSeenAt", "timestamp")) {
                    DateLine(
                        value.toString(),
                        now,
                        modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                    )
                }
                // FIXME: show title of location item
            }
        }
    }

    renderNestedData(item.data ?: emptyMap(), 0, onRemoveLocationClicked)
}

fun testForDateTime(value: String): LocalDateTime? {
    try {
        val formats = listOf<DateTimeFormatter>(
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy"),
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        )
        for (format in formats) {
            try {
                return format.parse(value).query(LocalDateTime::from)
            } catch (_: DateTimeParseException) {
                continue
            } catch (_: DateTimeException) {
                continue
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "DateTime parsing error: ${e.message}")
        return null
    }
    return null
}

fun testForDate(value: String): LocalDate? {
    try {
        return DateTimeFormatter.ISO_LOCAL_DATE.parse(value).query(LocalDate::from)
    } catch (_: DateTimeParseException) {
        return null
    } catch (_: DateTimeException) {
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Date parsing error: ${e.message}")
        return null
    }
}

@Composable
fun DateLine(value: String, now: MutableState<LocalDateTime>, modifier: Modifier = Modifier) {
    val dateTime: LocalDateTime? = testForDateTime(value)
    if (dateTime != null) {
        RelativeDateTimeRow(now.value, dateTime, modifier = modifier)
    }

    val date: LocalDate? = testForDate(value)
    if (date != null) {
        RelativeDateRow(now.value, date, modifier = modifier)
    }
}

@Composable
fun RelativeDateTimeRow(
    now: LocalDateTime,
    date: LocalDateTime,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    val duration = Duration.between(date, now)

    var timeText = when {
        duration.toDays() > 365 -> {
            val years = duration.toDays() / 365
            context.resources.getQuantityString(R.plurals.years, years.toInt(), years)
        }
        duration.toDays() > 30 -> {
            val months = duration.toDays() / 30
            context.resources.getQuantityString(R.plurals.months, months.toInt(), months)
        }
        duration.toDays() > 0 -> {
            val days = duration.toDays()
            context.resources.getQuantityString(R.plurals.days, days.toInt(), days)
        }
        duration.toHours() > 0 -> {
            val hours = duration.toHours()
            context.resources.getQuantityString(R.plurals.hours, hours.toInt(), hours)
        }
        duration.toMinutes() > 0 -> {
            val minutes = duration.toMinutes()
            context.resources.getQuantityString(R.plurals.minutes, minutes.toInt(), minutes)
        }
        else -> {
            val seconds = duration.seconds
            context.resources.getQuantityString(R.plurals.seconds, seconds.toInt(), seconds)
        }

    }
    timeText = if (duration.isNegative) "in $timeText" else "$timeText ago"
    ClockTextRow(timeText, modifier)
}


@Composable
fun RelativeDateRow(
    now: LocalDateTime,
    date: LocalDate,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    val duration = Period.between(date, now.toLocalDate()).normalized()

    var timeText = when {
        duration.years > 0 -> {
            context.resources.getQuantityString(R.plurals.years, duration.years, duration.years)
        }
        duration.months > 0 -> {
            context.resources.getQuantityString(R.plurals.months, duration.months, duration.months)
        }
        else -> {
            context.resources.getQuantityString(R.plurals.days, duration.days, duration.days)
        }
    }

    timeText = if (duration.isNegative) "in $timeText" else "$timeText ago"
    ClockTextRow(timeText, modifier)
}

@Composable
fun ClockTextRow(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(R.drawable.clock_24),
            contentDescription = "Clock",
            modifier = Modifier
                .size(12.dp)
                .alpha(0.6f)
        )
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun MarkAsSeenButton(
    isSaving: Boolean,
    saved: Boolean,
    onMarkAsSeen: () -> Unit,
    autoSave: Boolean,
    modifier: Modifier = Modifier,
) {
    val animationProgress = remember { Animatable(0f) }
    var launched by remember { mutableStateOf(false) }
    val enabled = !isSaving && !saved
    val buttonColor: Color = Color.Black
    val progressColor: Color = Color.DarkGray

    LaunchedEffect(autoSave) {
        if (autoSave && !launched) {
            launched = true
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 5_000,
                    easing = LinearEasing
                )
            )
            onMarkAsSeen()
            animationProgress.snapTo(0f)
            launched = false
        } else if (!autoSave && launched) {
            animationProgress.stop()
            animationProgress.snapTo(0f)
            launched = false
        }
    }

    val brush = if (enabled) Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to progressColor,
            animationProgress.value to progressColor,
            animationProgress.value to buttonColor,
            1.0f to buttonColor
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    ) else Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.Gray,
                1.0f to Color.Gray
            ),
            start = Offset.Zero,
            end = Offset(Float.POSITIVE_INFINITY, 0f)
        )

    Box(
        modifier = modifier
            .clip(ButtonDefaults.shape)
            .background(brush = brush)
    ) {
        Button(
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color.DarkGray.copy(alpha = 0.5f)
            ),
            enabled = enabled && !launched,
            onClick = { onMarkAsSeen() },
            modifier = Modifier.wrapContentWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                when {
                    isSaving -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    saved -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Checked",
                        tint = Color.White
                    )
                    else -> Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Check mark",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    text = when {
                        isSaving -> "Saving..."
                        saved -> "Marked as seen"
                        else -> "Mark as seen"
                    },
                    fontSize = 16.sp,
                    color = Color.White
                )
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
