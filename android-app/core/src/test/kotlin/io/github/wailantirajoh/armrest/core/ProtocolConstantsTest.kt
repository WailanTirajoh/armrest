package io.github.wailantirajoh.armrest.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolConstantsTest {
    @Test
    fun constantsMatchProtocolDoc() {
        assertEquals(1, ProtocolConstants.PROTOCOL_VERSION)
        assertEquals(47810, ProtocolConstants.DEFAULT_PORT)
        assertEquals("_armrest._tcp", ProtocolConstants.SERVICE_TYPE)
        assertEquals("screen", ProtocolConstants.FEATURE_SCREEN)
        assertEquals("power", ProtocolConstants.FEATURE_POWER)
    }
}
