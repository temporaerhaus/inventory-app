package de.temporaerhaus.inventory.client.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.temporaerhaus.inventory.client.R
import de.temporaerhaus.inventory.client.model.InventoryItem
import de.temporaerhaus.inventory.client.util.testForDate
import de.temporaerhaus.inventory.client.util.testForDateTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period

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
                                painter = painterResource(R.drawable.delete_outline_24),
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
