package dev.bugobliterator.itermbuddy.ui.chats

import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

private const val DAY_MILLIS = 24.0 * 60 * 60 * 1000
private const val WEEK_MILLIS = 7 * DAY_MILLIS

/**
 * A chat row's last-activity stamp, iOS's rules (`IOS/Screens/HomeView.swift:134-145`): the
 * time of day when [lastModifiedMillis] (the Mac's time, ms since 1970) is less than 24 hours
 * before [nowMillis], the weekday when less than a week, else a short numeric date with
 * unpadded day and month and a two-digit year in [locale]'s order (iOS's
 * `.month(.defaultDigits).day(.defaultDigits).year(.twoDigits)`). Ages are elapsed time, not
 * calendar days, as iOS's; a time ahead of the phone's clock reads as today.
 *
 * [use24Hour] is the device's clock setting (Android's `DateFormat.is24HourFormat`); null uses
 * [locale]'s own. A time that is not finite gives an empty stamp.
 */
fun chatTimestamp(lastModifiedMillis: Double, nowMillis: Long, zone: ZoneId, locale: Locale, use24Hour: Boolean? = null): String {
    if (!lastModifiedMillis.isFinite()) return ""
    val at = Instant.ofEpochMilli(lastModifiedMillis.toLong()).atZone(zone)
    val age = nowMillis - lastModifiedMillis
    val pattern = when {
        age < DAY_MILLIS -> timePattern(locale, use24Hour)
        age < WEEK_MILLIS -> "cccc"
        else -> shortDatePattern(locale)
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(at)
}

/** [locale]'s short time pattern, switched to 24 or 12 hours when [use24Hour] says so. */
private fun timePattern(locale: Locale, use24Hour: Boolean?): String {
    val base = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
    val localeIs24 = unquoted(base).any { it == 'H' || it == 'k' }
    return when (use24Hour) {
        null, localeIs24 -> base
        true -> "HH:mm"
        false -> "h:mm a"
    }
}

/** [locale]'s short date pattern with the day and month unpadded and the year two digits. */
private fun shortDatePattern(locale: Locale): String {
    val base = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
    val out = StringBuilder()
    var quoted = false
    var i = 0
    while (i < base.length) {
        val c = base[i]
        if (c == '\'') {
            quoted = !quoted
            out.append(c)
            i++
            continue
        }
        var run = 1
        while (!quoted && i + run < base.length && base[i + run] == c) run++
        out.append(
            when {
                quoted -> c.toString()
                c == 'y' || c == 'u' -> "yy"
                c == 'd' -> "d"
                c == 'M' || c == 'L' -> "M"
                else -> c.toString().repeat(run)
            },
        )
        i += run
    }
    return out.toString()
}

/** [pattern]'s letters outside quoted literals. */
private fun unquoted(pattern: String): String = pattern.split('\'').filterIndexed { index, _ -> index % 2 == 0 }.joinToString("")
