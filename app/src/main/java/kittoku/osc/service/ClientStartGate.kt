package kittoku.osc.service

/**
 * Publishes a client only when its connect generation is still current.
 * Superseding a connect and starting the client share one lock, so a cancelled
 * attempt cannot leave a running client after the newer attempt has moved on.
 */
internal class ClientStartGate<T> {
    private val lock = Any()
    private var generationValue = 0
    private var client: T? = null

    val generation: Int
        get() = synchronized(lock) { generationValue }

    fun invalidate(onClient: (T) -> Unit): Int {
        return synchronized(lock) {
            val next = ++generationValue
            val previous = client
            client = null
            if (previous != null) onClient(previous)
            next
        }
    }

    fun tryStart(generation: Int, create: () -> T): Boolean {
        synchronized(lock) {
            if (generation != generationValue) return false
            client = create()
            return true
        }
    }

    fun release(): T? {
        return synchronized(lock) {
            val current = client
            client = null
            current
        }
    }
}
