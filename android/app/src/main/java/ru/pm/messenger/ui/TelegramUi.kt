package ru.pm.messenger.ui

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Tg {
    val bg = Color(0xFF0E1621)
    val bars = Color(0xFF17212B)
    val incoming = Color(0xFF182533)
    val outgoing = Color(0xFF2B5278)
    val accent = Color(0xFF5288C1)
    val accentBright = Color(0xFF6AB3F3)
    val text = Color(0xFFF5F5F5)
    val secondary = Color(0xFF7D8E9E)
    val unread = Color(0xFF3E88F7)
    val danger = Color(0xFFE36A6A)
    val input = Color(0xFF242F3D)
    val chip = Color(0xFF2B5278)

    private val avatars = listOf(
        Color(0xFFE17076),
        Color(0xFF7BC862),
        Color(0xFF65AADD),
        Color(0xFFEE7AAE),
        Color(0xFFFAA774),
        Color(0xFF6EC9CB),
        Color(0xFFAD86E1),
    )

    fun avatar(key: String): Color {
        val id = key.fold(0L) { acc, char -> acc * 31 + char.code }
        val size = avatars.size.toLong()
        val index = ((id % size) + size) % size
        return avatars[index.toInt()]
    }
}

private val zone: ZoneId = ZoneId.systemDefault()
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
private val fullDay: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))
private val weekDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EE", Locale("ru"))

fun formatListTime(iso: String?): String {
    val date = localDate(iso) ?: return ""
    val today = LocalDate.now(zone)
    return when {
        date == today -> instant(iso)?.atZone(zone)?.format(timeFormat).orEmpty()
        date == today.minusDays(1) -> "вчера"
        date.isAfter(today.minusDays(7)) -> date.format(weekDay)
        else -> date.format(dayMonth)
    }
}

fun formatBubbleTime(iso: String?): String = instant(iso)?.atZone(zone)?.format(timeFormat).orEmpty()

fun formatDayLabel(iso: String?): String {
    val date = localDate(iso) ?: return ""
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date.format(fullDay)
    }
}

fun sameDay(left: String?, right: String?): Boolean {
    val first = localDate(left) ?: return false
    val second = localDate(right) ?: return false
    return first == second
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val raw = when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
        parts.size == 1 -> parts[0].take(2)
        else -> "?"
    }
    return raw.uppercase(Locale("ru"))
}

private fun instant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}

private fun localDate(iso: String?): LocalDate? = instant(iso)?.atZone(zone)?.toLocalDate()
