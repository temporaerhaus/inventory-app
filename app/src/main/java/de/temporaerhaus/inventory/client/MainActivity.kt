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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.POST
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
                        putString("keystroke_output_enabled", "false");
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
    var now = remember { mutableStateOf(LocalDateTime.now()) }
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
                item = getInventoryData(inventoryNumber)
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
                    val newItem = writeAsSeen(item!!, lastContainerItem)
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
        val fullUrl = "https://wiki.temporaerhaus.de/inventar/${item?.number}"
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
                        )
                    }
                }
            } else {
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
fun ItemDataLines(item: InventoryItem, now: MutableState<LocalDateTime>) {

    @Composable
    fun renderNestedData(data: Map<String, Any?>, indent: Int = 0) {
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
                renderNestedData(value as Map<String, Any?>, indent + 1)
                Text(
                    text = "}",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$key: $value",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = (indent * INDENT_SIZE).dp)
                    )
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
            }
        }
    }

    renderNestedData(item.data ?: emptyMap())
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
                return format.parse(value).query<LocalDateTime>(LocalDateTime::from)
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
        return DateTimeFormatter.ISO_LOCAL_DATE.parse(value).query<LocalDate>(LocalDate::from)
    } catch (_: DateTimeParseException) {
        return null
    } catch (_: DateTimeException) {
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Date parsing error: ${e.message}")
        return null
    }
    return null
}

@Composable
fun DateLine(value: String, now: MutableState<LocalDateTime>, modifier: Modifier = Modifier) {
    var dateTime: LocalDateTime? = testForDateTime(value)
    if (dateTime != null) {
        RelativeDateTimeRow(now.value, dateTime, modifier = modifier)
    }

    var date: LocalDate? = testForDate(value)
    if (date != null) {
        RelativeDateRow(now.value, date, modifier = modifier)
    }
}

private val dokuwikiApi: DokuwikiApi by lazy {
    val retrofit = Retrofit.Builder()
        .baseUrl("https://wiki.temporaerhaus.de/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    retrofit.create(DokuwikiApi::class.java)
}

suspend fun getInventoryData(
    inventoryNumber: String,
): InventoryItem? {
    try {
        val response = dokuwikiApi.getPageContent(DokuwikiApi.PageRequest("inventar/${inventoryNumber}"))
        Log.d(TAG, "Response: $response")
        val name = extractHeadingFromMarkdown(response.result)
        val yamlBlock = extractYamlBlockFromMarkdown(response.result)
        Log.d(TAG, "Extracted Name: $name")
        Log.d(TAG, "Extracted Data: $yamlBlock")
        if (yamlBlock != null) {
            return InventoryItem(
                number = inventoryNumber,
                name = name,
                data = yamlBlock
            )
        } else {
            throw IOException("No YAML block found. Is this a existing inventory item?")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Network error: ${e.message}")
        throw IOException("Network error: ${e.message}")
    }
}

suspend fun writeAsSeen(
    item: InventoryItem,
    lastContainerItem: InventoryItem? = null
): InventoryItem? {
    try {
        val response = dokuwikiApi.getPageContent(DokuwikiApi.PageRequest("inventar/${item.number}"))
        val yamlBlock = extractYamlBlockFromMarkdown(response.result)
        if (yamlBlock != null) {
            val updatedYamlBlock = yamlBlock.toMutableMap()

            val nowIso = LocalDateTime
                .now()
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

            updatedYamlBlock["lastSeenAt"] = nowIso

            if (lastContainerItem != null && lastContainerItem.number != item.number) {
                updatedYamlBlock["temporary"] = mapOf(
                    "description" to "",
                    "location" to lastContainerItem.number,
                    "timestamp" to nowIso
                )
            }

            val updatedContent = replaceYamlBlockInMarkdown(response.result, updatedYamlBlock)

            dokuwikiApi.writePageContent(DokuwikiApi.SavePageRequest("inventar/${item.number}", updatedContent))

            Log.d(TAG, "Updated content: $updatedContent")
            return item.copy(data = updatedYamlBlock)
        }
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Network error: ${e.message}")
        throw IOException("Network error: ${e.message}")
    }
}

interface DokuwikiApi {
    data class PageRequest(val page: String)
    data class RpcResponse(val result: String, val error: Any?)

    @POST("/lib/exe/jsonrpc.php/core.getPage")
    @retrofit2.http.Headers("Content-Type: application/json")
    suspend fun getPageContent(@retrofit2.http.Body request: PageRequest): RpcResponse

    data class SavePageRequest(
        val page: String,
        val text: String,
        val summary: String = "Updated via inventory app",
        val isminor: Boolean = false
    )

    @POST("/lib/exe/jsonrpc.php/core.savePage")
    @retrofit2.http.Headers("Content-Type: application/json")
    suspend fun writePageContent(
        @retrofit2.http.Body request: SavePageRequest,
    ): RpcResponse
}

fun extractHeadingFromMarkdown(markdown: String): String? {
    val lines = markdown.split("\n")
    for (line in lines) {
        if (line.startsWith("#")) {
            return line.substringAfter("#").trim()
        }
    }
    return null
}

class NoTimestampSafeConstructor : SafeConstructor {
    constructor(loaderOptions: LoaderOptions) : super(loaderOptions) {
        this.yamlConstructors.put(Tag.TIMESTAMP, ConstructYamlStr())
    }
}

fun extractYamlBlockFromMarkdown(markdown: String): Map<String, Any>? {
   val yaml = Yaml(NoTimestampSafeConstructor(LoaderOptions()))
    val codeBlocks = markdown.split("```yaml").drop(1)
    for (block in codeBlocks) {
        val code = block.split("```").firstOrNull() ?: continue
        if (code.isEmpty()) {
            continue
        }
        try {
            val data: Map<String, Any>? = yaml.load(code)
            if (data == null) {
                continue
            }
            if (data.containsKey("inventory")) {
                return data
            }
        } catch (e: Exception) {
            Log.e(TAG, "YAML parsing error: ${e.message}")
            continue
        }
    }
    return null
}

fun replaceYamlBlockInMarkdown(markdown: String, data: Map<String, Any>): String {
    val dumperOptions = DumperOptions().apply {
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        isPrettyFlow = true
        defaultFlowStyle = DumperOptions.FlowStyle.FLOW
    }
    val yaml = Yaml(dumperOptions)
    var code = yaml.dump(data)

    // a bit cleaner yaml: empty {\n  } objects collapse to a single line {}
    code = code.replace(Regex("\\{\\s*\\}"), "{}")

    return markdown.replace(Regex("```yaml.*?```", RegexOption.DOT_MATCHES_ALL), "```yaml\n$code\n```")
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
            context.resources.getQuantityString(R.plurals.years, duration.years.toInt(), duration.years)
        }
        duration.months > 0 -> {
            context.resources.getQuantityString(R.plurals.months, duration.months.toInt(), duration.months)
        }
        else -> {
            context.resources.getQuantityString(R.plurals.days, duration.days.toInt(), duration.days)
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
    var enabled = !isSaving && !saved
    var buttonColor: Color = Color.Black
    var progressColor: Color = Color.DarkGray

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
        InventoryApp()
    }
}