package com.bnyro.clock.social.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialLinkTest {
    @Test
    fun invitationsPreserveServerAndToken() {
        val link = SocialLink.parse(
            "${SocialLink.BASE_URL}/join?server=https%3A%2F%2Fclock.example%2Fapi&token=invitation-token"
        )!!
        assertEquals("join", link.destination)
        assertEquals("https://clock.example/api", link.parameters["server"])
        assertEquals("invitation-token", link.parameters["token"])
    }

    @Test
    fun profilesReadCredentialsFromFragments() {
        val link = SocialLink.parse("${SocialLink.BASE_URL}/profile#name=Quiet+Mango%2B&key=example%3D")!!
        assertEquals("profile", link.destination)
        assertEquals("Quiet Mango+", link.parameters["name"])
        assertEquals("example=", link.parameters["key"])
    }

    @Test
    fun profileQueriesCannotOverridePrivateFragment() {
        val link = SocialLink.parse(
            "${SocialLink.BASE_URL}/profile?key=public#name=Quiet&key=private"
        )!!
        assertEquals("private", link.parameters["key"])
        assertNull(SocialLink.parse("${SocialLink.BASE_URL}/profile?key=public")!!.parameters["key"])
    }

    @Test
    fun unrelatedAndMalformedLinksAreRejected() {
        for (value in listOf(
            "https://example.com/join?token=example",
            "https://jay.poppybit.com.example.com/join?token=example",
            "https://someone@jay.poppybit.com/join?token=example",
            "http://jay.poppybit.com/join?token=example",
            "${SocialLink.BASE_URL}/join/other?token=example",
            "${SocialLink.BASE_URL}/settings",
            "jay://profile?name=Quiet&key=example",
            "jay://join?token=example",
            "${SocialLink.BASE_URL}/join?token=%GG",
            "not a link"
        )) {
            assertNull(value, SocialLink.parse(value))
        }
    }
}
