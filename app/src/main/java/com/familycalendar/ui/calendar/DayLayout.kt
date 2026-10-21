package com.familycalendar.ui.calendar

/** Where an event block sits in a day column: column [column] of [columns] side-by-side columns. */
data class Slot(val column: Int, val columns: Int)

object DayLayout {
    /**
     * Lays out time ranges (in minutes since midnight, `start < end`) like Google Calendar's day view:
     * overlapping events share the width; events that only overlap transitively share the same column count.
     * Returns one [Slot] per input, in input order.
     */
    fun layout(ranges: List<IntRange>): List<Slot> {
        val order = ranges.indices.sortedWith(compareBy({ ranges[it].first }, { -ranges[it].last }))
        val column = IntArray(ranges.size)
        val columns = IntArray(ranges.size)

        var cluster = mutableListOf<Int>()
        val columnEnds = mutableListOf<Int>() // end minute of the last event in each column
        var clusterEnd = Int.MIN_VALUE

        fun closeCluster() {
            cluster.forEach { columns[it] = columnEnds.size }
            cluster = mutableListOf()
            columnEnds.clear()
        }

        for (i in order) {
            val r = ranges[i]
            if (cluster.isNotEmpty() && r.first >= clusterEnd) closeCluster()
            val free = columnEnds.indexOfFirst { it <= r.first }
            column[i] = if (free >= 0) free else columnEnds.size
            if (free >= 0) columnEnds[free] = r.last + 1 else columnEnds += r.last + 1
            clusterEnd = if (cluster.isEmpty()) r.last + 1 else maxOf(clusterEnd, r.last + 1)
            cluster += i
        }
        closeCluster()
        return ranges.indices.map { Slot(column[it], columns[it]) }
    }
}
