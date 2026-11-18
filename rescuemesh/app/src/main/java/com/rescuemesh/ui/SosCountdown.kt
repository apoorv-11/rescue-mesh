package com.rescuemesh.ui

/**
 * The Account-landing swipe-right SOS countdown (UI redesign spec):
 * swiping right starts a 3-second countdown whose only job is to be a
 * cancellable confirm — when it elapses, the existing quick-SOS path
 * fires (exactly Home's `sendSos()`) and the app lands on Home.
 *
 * This object is just the numeral math, pure and JVM-tested; the
 * deadline/job lives in AppViewModel and the rendering is the dark
 * in-page countdown card on the Account landing (layout rebuild,
 * 15 Nov — the old full-screen overlay is gone).
 */
object SosCountdown {

    /** Countdown length: three seconds, numerals 3 → 2 → 1. */
    const val DURATION_MS: Long = 3_000L

    /**
     * The big numeral to show [elapsedMs] after the countdown started:
     * 3 for the first second, 2 for the second, 1 for the third, and
     * null once the countdown has elapsed (the SOS should fire). A
     * negative elapsed (clock skew) still shows 3.
     */
    fun secondsRemaining(elapsedMs: Long): Int? = when {
        elapsedMs < 0L -> 3
        elapsedMs >= DURATION_MS -> null
        else -> 3 - (elapsedMs / 1_000L).toInt()
    }

    /** True once [elapsedMs] reaches the full countdown length. */
    fun isFinished(elapsedMs: Long): Boolean = elapsedMs >= DURATION_MS
}
