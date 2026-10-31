package dev.buddy.helper

// ActivityRecordInputSink is a bufferless platform input layer, not a foreign app window.
internal class SystemInputSinkPolicy(
    private val consumerType: Int,
    noChannel: Int,
    notFocusable: Int,
    notTouchable: Int
) {
    private val configs = setOf(noChannel or notFocusable, noChannel or notFocusable or notTouchable)
    init {
        require(consumerType >= 2000)
        val bits = listOf(noChannel, notFocusable, notTouchable)
        require(bits.distinct().size == 3 && bits.all { it > 0 && Integer.bitCount(it) == 1 })
    }
    fun matches(uid: Int, type: Int, config: Int, flags: Int, hasToken: Boolean): Boolean =
        uid == 1000 && type == consumerType && config in configs && flags == 0 && !hasToken
}
