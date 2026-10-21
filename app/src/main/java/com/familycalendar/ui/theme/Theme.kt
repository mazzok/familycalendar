package com.familycalendar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF039BE5)

@Composable
fun FamilyCalendarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(primary = Brand)
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

data class CalendarColor(val name: String, val color: Color) {
    /** Text color with enough contrast on top of [color]. */
    val onColor: Color get() = if (color.luminance() > 0.5f) Color(0xFF202124) else Color.White
}

/** The event colors Google Calendar offers; calendars are assigned these in order so they stay distinct. */
val CalendarPalette = listOf(
    CalendarColor("Peacock", Color(0xFF039BE5)),
    CalendarColor("Tomato", Color(0xFFD50000)),
    CalendarColor("Basil", Color(0xFF0B8043)),
    CalendarColor("Tangerine", Color(0xFFF4511E)),
    CalendarColor("Grape", Color(0xFF8E24AA)),
    CalendarColor("Banana", Color(0xFFF6BF26)),
    CalendarColor("Flamingo", Color(0xFFE67C73)),
    CalendarColor("Blueberry", Color(0xFF3F51B5)),
    CalendarColor("Sage", Color(0xFF33B679)),
    CalendarColor("Lavender", Color(0xFF7986CB)),
    CalendarColor("Graphite", Color(0xFF616161)),
)

fun paletteColor(index: Int?): CalendarColor = CalendarPalette[(index ?: 0).mod(CalendarPalette.size)]
