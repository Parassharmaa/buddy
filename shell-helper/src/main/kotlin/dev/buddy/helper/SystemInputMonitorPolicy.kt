package dev.buddy.helper

// Android 37.2 GestureMonitorSpyWindow has an input channel but no graphical buffer.
internal class SystemInputMonitorPolicy(
    private val overlayType: Int,
    notFocusable: Int,
    spy: Int,
    trusted: Int,
    doNotPilfer: Int,
    topologyAware: Int
) {
    private val base = notFocusable or spy or trusted
    private val configs = setOf(base, base or doNotPilfer, base or topologyAware, base or doNotPilfer or topologyAware)
    init {
        require(overlayType >= 2000)
        val bits = listOf(notFocusable, spy, trusted, doNotPilfer, topologyAware)
        require(bits.distinct().size == 5 && bits.all { it > 0 && Integer.bitCount(it) == 1 })
    }
    fun matches(uid: Int, type: Int, config: Int, flags: Int, hasToken: Boolean, width: Int?, height: Int?): Boolean =
        uid == 1000 && type == overlayType && config in configs && flags == 0 && hasToken &&
            width == 0 && height == 0
}
