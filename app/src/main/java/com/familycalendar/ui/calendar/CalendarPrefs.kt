package com.familycalendar.ui.calendar

import android.content.Context

/** Per-device display settings: the last used view and each calendar's chosen color. */
class CalendarPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("calendar_prefs", Context.MODE_PRIVATE)

    var viewMode: CalendarViewMode
        get() = prefs.getString(KEY_VIEW, null)
            ?.let { runCatching { CalendarViewMode.valueOf(it) }.getOrNull() }
            ?: CalendarViewMode.MONTH
        set(value) = prefs.edit().putString(KEY_VIEW, value.name).apply()

    /** ownerId -> palette index, only for calendars whose color the user picked. */
    fun colorOverrides(): Map<String, Int> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(COLOR_PREFIX) && value is Int) key.removePrefix(COLOR_PREFIX) to value else null
        }.toMap()

    fun setColor(ownerId: String, paletteIndex: Int) {
        prefs.edit().putInt(COLOR_PREFIX + ownerId, paletteIndex).apply()
    }

    private companion object {
        const val KEY_VIEW = "view_mode"
        const val COLOR_PREFIX = "color_"
    }
}
