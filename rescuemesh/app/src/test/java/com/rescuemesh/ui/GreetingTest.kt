package com.rescuemesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun morningAfternoonEveningNight() {
        assertEquals("Good night,", greetingForHour(0))
        assertEquals("Good night,", greetingForHour(4))
        assertEquals("Good morning,", greetingForHour(5))
        assertEquals("Good morning,", greetingForHour(11))
        assertEquals("Good afternoon,", greetingForHour(12))
        assertEquals("Good afternoon,", greetingForHour(16))
        assertEquals("Good evening,", greetingForHour(17))
        assertEquals("Good evening,", greetingForHour(21))
        assertEquals("Good night,", greetingForHour(22))
        assertEquals("Good night,", greetingForHour(23))
    }
}
