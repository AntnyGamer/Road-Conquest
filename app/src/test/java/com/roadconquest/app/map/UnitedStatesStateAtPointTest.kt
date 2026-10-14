package com.roadconquest.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class UnitedStatesStateAtPointTest {
    @Test fun verifiesStateNameAgainstOfficialStateCode() {
        assertEquals("New Jersey", UnitedStatesStateAtPoint.parseResponse(
            """{"features":[{"attributes":{"STATE":"34","BASENAME":"New Jersey"}}]}"""
        ))
        assertNull(UnitedStatesStateAtPoint.parseResponse(
            """{"features":[{"attributes":{"STATE":"42","BASENAME":"New Jersey"}}]}"""
        ))
        assertNull(UnitedStatesStateAtPoint.parseResponse("""{"features":[]}"""))
    }

    @Test fun invalidProviderPayloadIsRetryable() {
        assertThrows(IOException::class.java) {
            UnitedStatesStateAtPoint.parseResponse("""{"error":{"message":"Unavailable"}}""")
        }
    }
}
