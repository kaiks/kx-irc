package com.kx.irc

import org.junit.Assert.assertEquals
import org.junit.Test

class IrcColorsTest {
    @Test
    fun buildStyledMessageStripsControlCodes() {
        val message = "Hello \u0002bold\u0002 and \u00031,2colors\u000f!"
        val styled = buildStyledMessage(message)

        assertEquals("Hello bold and colors!", styled.text)
        assertEquals(true, styled.spanStyles.isNotEmpty())
    }

    @Test
    fun buildStyledMessageResetsColorsAndStripsReverseControl() {
        val styled = buildStyledMessage("\u000304red\u0003plain\u0016 reversed")

        assertEquals("redplain reversed", styled.text)
    }
}
