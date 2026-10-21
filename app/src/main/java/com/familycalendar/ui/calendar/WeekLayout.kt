package com.familycalendar.ui.calendar

object WeekLayout {
    /**
     * Assigns each segment (a range of day columns, e.g. `2..4` for Wed–Fri) the lowest row ("lane") where
     * it doesn't overlap another, like the bars in Google Calendar's month view. Earlier and longer segments
     * get the upper lanes; ties keep the input order. Returns one lane per input, in input order.
     */
    fun lanes(segments: List<IntRange>): List<Int> {
        val order = segments.indices.sortedWith(
            compareBy<Int> { segments[it].first }.thenByDescending { segments[it].last - segments[it].first }
        )
        val occupied = mutableListOf<BooleanArray>() // per lane: which columns are taken
        val columns = (segments.maxOfOrNull { it.last } ?: -1) + 1
        val result = IntArray(segments.size)
        for (i in order) {
            val range = segments[i]
            var lane = occupied.indexOfFirst { taken -> range.none { taken[it] } }
            if (lane < 0) {
                occupied += BooleanArray(columns)
                lane = occupied.lastIndex
            }
            range.forEach { occupied[lane][it] = true }
            result[i] = lane
        }
        return result.toList()
    }
}
