package ru.savefood.app.feature.needy.find

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeInputTest {
    @Test
    fun `single digit gets an automatic colon`() {
        assertEquals("0:9", formatTimeDigits("9"))
    }

    @Test
    fun `three digits become a zero-padded time`() {
        assertEquals("09:30", formatTimeDigits("930"))
    }

    @Test
    fun `four digits become hours and minutes`() {
        assertEquals("12:30", formatTimeDigits("1230"))
    }

    @Test
    fun `non-digits are removed and input is limited`() {
        assertEquals("12:34", formatTimeDigits("1a2:345"))
    }
}
