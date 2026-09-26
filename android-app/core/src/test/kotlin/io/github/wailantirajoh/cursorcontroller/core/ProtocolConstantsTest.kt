package io.github.wailantirajoh.cursorcontroller.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolConstantsTest {
    @Test
    fun constantsMatchProtocolDoc() {
        assertEquals(1, ProtocolConstants.PROTOCOL_VERSION)
        assertEquals(47810, ProtocolConstants.DEFAULT_PORT)
        assertEquals("_cursorctl._tcp", ProtocolConstants.SERVICE_TYPE)
    }
}
