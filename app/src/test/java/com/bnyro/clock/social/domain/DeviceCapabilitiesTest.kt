package com.bnyro.clock.social.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeviceCapabilitiesTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")

    @Test
    fun selfHostedAccessDoesNotRequireAnExpiry() {
        val capabilities = Json.decodeFromString<DeviceCapabilities>(
            """{"shared_sound_upload":true,"requires_play_entitlement":false,"expires_at":null}"""
        )
        assertTrue(capabilities.canUploadSharedSounds(now))
    }

    @Test
    fun playAccessRequiresAnUnexpiredGrant() {
        assertFalse(DeviceCapabilities().canUploadSharedSounds(now))
        assertFalse(DeviceCapabilities(true, true).canUploadSharedSounds(now))
        assertFalse(DeviceCapabilities(true, true, now.toString()).canUploadSharedSounds(now))
        assertTrue(DeviceCapabilities(true, true, now.plusSeconds(1).toString()).canUploadSharedSounds(now))
        assertFalse(DeviceCapabilities(false, true, now.plusSeconds(1).toString()).canUploadSharedSounds(now))
    }

}
