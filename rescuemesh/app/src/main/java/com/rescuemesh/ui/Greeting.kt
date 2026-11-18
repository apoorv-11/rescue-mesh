package com.rescuemesh.ui

/**
 * The Account landing hero's time-based greeting (preview: "Good
 * morning,"). Pure so the hour boundaries are JVM-unit-tested
 * (GreetingTest); the screen feeds it the device's local hour.
 */
fun greetingForHour(hourOfDay: Int): String = when (hourOfDay) {
    in 5..11 -> "Good morning,"
    in 12..16 -> "Good afternoon,"
    in 17..21 -> "Good evening,"
    else -> "Good night,"
}
