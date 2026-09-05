package com.bnyro.clock.social.data

import java.net.URI
import java.net.URLDecoder

data class SocialLink(val destination: String, val parameters: Map<String, String>) {
    companion object {
        const val BASE_URL = "https://jay.poppybit.com"

        fun parse(value: String): SocialLink? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && uri.rawAuthority == "jay.poppybit.com")
            val destination = uri.path.removePrefix("/")
            require(destination == "join" || destination == "profile")
            val payload = if (destination == "profile") {
                uri.rawFragment
            } else {
                uri.rawQuery
            }
            val parameters = payload.orEmpty().split('&').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(
                    parts[1], Charsets.UTF_8.name()
                ) else null
            }.toMap()
            SocialLink(destination, parameters)
        }.getOrNull()
    }
}
