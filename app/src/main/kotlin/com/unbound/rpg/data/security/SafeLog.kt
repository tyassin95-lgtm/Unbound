package com.unbound.rpg.data.security

import android.util.Log
import com.unbound.rpg.BuildConfig

/**
 * Logging that cannot leak a credential (§100).
 *
 * Every message passes through [redact] before it reaches logcat, so even a careless call site
 * that interpolates a request header produces `sk-•••REDACTED•••` rather than a usable key. Debug
 * logging is additionally compiled out of release builds.
 */
object SafeLog {

    private const val TAG = "UNBOUND"

    private val SECRET_PATTERNS = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{8,}"""),
        Regex("""(?i)"?authorization"?\s*[:=]\s*"?[^"\s,}]+"""),
        Regex("""(?i)"?api[_-]?key"?\s*[:=]\s*"?[^"\s,}]+"""),
    )

    fun redact(message: String): String =
        SECRET_PATTERNS.fold(message) { acc, pattern -> pattern.replace(acc, "•••REDACTED•••") }

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, redact(message))
    }

    fun i(message: String) = Log.i(TAG, redact(message)).let { }

    fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, redact(message)) else Log.w(TAG, redact(message), t)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, redact(message)) else Log.e(TAG, redact(message), t)
    }
}
