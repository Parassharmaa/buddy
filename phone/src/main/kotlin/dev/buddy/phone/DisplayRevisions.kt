package dev.buddy.phone

internal class DisplayRevisions {
    private var sequence = 0L
    private var global = 0L
    private val displays = mutableMapOf<Int, Long>()

    @Synchronized fun changed(display: Int? = null) {
        val stamp = ++sequence
        if (display == null || display < 0) {
            global = stamp
            displays.clear()
        } else {
            if (display !in displays && displays.size >= 16) {
                global = stamp
                displays.clear()
            }
            displays[display] = stamp
        }
    }

    @Synchronized fun current(display: Int): Long = maxOf(global, displays[display] ?: 0)
}
