package online.whoisthis.plugin.microcontroller

import android.util.Log

/**
 * Millisecond timing marks for speed debugging. One logcat line per mark under tag "wit.timing":
 * `<event> k=v k=v`; logcat supplies the wall-clock time with milliseconds. `ms=` is a duration,
 * `age_ms=` is now minus the frame's capture time, `t=` is that capture time in epoch ms so the same
 * frame can be found in the WhoIsThis app's log. Per-frame marks are throttled with [every].
 */
object Timing {
    const val TAG = "wit.timing"

    fun mark(event: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder(event)
        for ((k, v) in fields) if (v != null) sb.append(' ').append(k).append('=').append(v)
        Log.d(TAG, sb.toString())
    }

    /** Runs [block] and logs `event ms=<duration> fields...`. */
    inline fun <T> timed(event: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            mark(event, "ms" to (System.nanoTime() - t0) / 1_000_000, *fields)
        }
    }

    /** True on the first call and then every [n]th: `if (Timing.every(count, 100)) mark(...)`. */
    fun every(count: Long, n: Int = 100): Boolean = count == 1L || count % n == 0L

    /** Milliseconds elapsed since a `System.nanoTime()` stamp. */
    fun sinceNanos(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000
}
