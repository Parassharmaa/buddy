package dev.buddy.helper

// GestureMonitorSpyWindow is a container with an input channel and no graphical buffer.
// SurfaceFlinger's RequestedLayerState::getBufferSize returns INVALID_RECT without a texture;
// LayerSnapshotBuilder projects its -1 x -1 dimensions into contentSize. A failed query is null.
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
            ((width == 0 && height == 0) || (width == -1 && height == -1))
}
