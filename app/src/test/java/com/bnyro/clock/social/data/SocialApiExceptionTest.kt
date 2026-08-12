package com.bnyro.clock.social.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialApiExceptionTest {
    @Test
    fun stringDetailBecomesMessage() {
        val exception = SocialApiException(
            409,
            """{"detail":"Promote another leader before leaving the group"}"""
        )

        assertEquals("Promote another leader before leaving the group", exception.message)
    }

    @Test
    fun validationDetailsBecomeMessages() {
        val exception = SocialApiException(
            422,
            """{"detail":[{"type":"string_too_short","loc":["body","token"],"msg":"String should have at least 32 characters"},{"type":"value_error","loc":["body","token"],"msg":"Invitation has expired"}]}"""
        )

        assertEquals(
            "String should have at least 32 characters\nInvitation has expired",
            exception.message
        )
    }

    @Test
    fun nonJsonResponseRemainsMessage() {
        val exception = SocialApiException(502, "Bad Gateway")

        assertEquals("Bad Gateway", exception.message)
    }

    @Test
    fun emptyResponseUsesStatusMessage() {
        val exception = SocialApiException(503, "")

        assertEquals("Request failed with status 503", exception.message)
    }
}
