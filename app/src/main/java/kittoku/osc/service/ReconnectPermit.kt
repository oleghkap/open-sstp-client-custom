package kittoku.osc.service

/**
 * Decides whether a tunnel shutdown may schedule another connection attempt.
 *
 * Disconnect and a new connect both move the epoch under the same lock that
 * publishes the reconnect job. A shutdown that already read an older epoch
 * cannot publish afterwards. Disconnect also suppresses the new epoch, so a
 * shutdown that starts only after the user disconnects cannot publish either.
 * The next connect arms the permit again, and errors in that session may reconnect.
 */
internal class ReconnectPermit {
    private val lock = Any()
    private var epochValue = 0
    private var suppressed = false

    fun capture(): Int = synchronized(lock) { epochValue }

    fun isCurrent(epoch: Int): Boolean = synchronized(lock) {
        !suppressed && epoch == epochValue
    }

    fun <T> suppress(block: () -> T): T = synchronized(lock) {
        suppressed = true
        epochValue++
        block()
    }

    fun <T> arm(block: () -> T): T = synchronized(lock) {
        suppressed = false
        epochValue++
        block()
    }

    fun <T> runIfCurrent(epoch: Int, block: () -> T): T? = synchronized(lock) {
        if (suppressed || epoch != epochValue) {
            null
        } else {
            block()
        }
    }
}
