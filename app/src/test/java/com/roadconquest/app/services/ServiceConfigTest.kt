package com.roadconquest.app.services

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class ServiceConfigTest {
    @Test fun missingOrExpiredConfigurationUsesDefaultsWithoutDecoding() {
        val valid = """{"version":1,"providers":{"openfreemap":false}}"""
        assertTrue(ServiceConfig.cachedValues(null, 0L).isEmpty())
        assertTrue(ServiceConfig.cachedValues("", 0L).isEmpty())
        assertTrue(ServiceConfig.cachedValues(null, 86_400_000L).isEmpty())
        assertTrue(ServiceConfig.cachedValues(valid, -1L).isEmpty())
        assertTrue(ServiceConfig.cachedValues(valid, 86_400_001L).isEmpty())
        assertEquals(false, ServiceConfig.cachedValues(valid, 86_400_000L)["openfreemap"])
        assertEquals(false, ServiceConfig.cachedValues(valid, 0L)["openfreemap"])
    }

    @Test fun numericVersionMatchesTheGatewayContractWithoutCoercion() {
        val normal = ServiceConfig.parse("""{"version":1,"providers":{"openfreemap":false}}""")
        assertEquals(false, normal["openfreemap"])
        assertEquals(true, normal["geoapify-tiles"])
        // JSON's numeric 1.0 is equivalent to numeric 1 on the gateway.
        assertEquals(normal, ServiceConfig.parse("""{"version":1.0,"providers":{"openfreemap":false}}"""))
        for (version in listOf("\"1\"", "1.5", "true", "null")) {
            val malformed = """{"version":$version,"providers":{"openfreemap":false}}"""
            assertTrue("Must reject malformed version: $version",
                runCatching { ServiceConfig.parse(malformed) }.isFailure)
            assertTrue("Invalid cache data falls back to local defaults",
                ServiceConfig.cachedValues(malformed, 0).isEmpty())
        }
    }
}
