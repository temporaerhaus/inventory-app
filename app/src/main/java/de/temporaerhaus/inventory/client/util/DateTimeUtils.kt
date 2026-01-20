package de.temporaerhaus.inventory.client.util

import android.util.Log
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
