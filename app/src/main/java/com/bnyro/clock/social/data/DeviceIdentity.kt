package com.bnyro.clock.social.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.bnyro.clock.social.domain.DeviceNameWords
import com.bnyro.clock.util.Preferences
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class DeviceIdentity(
    val id: String,
    val name: String,
    val token: String,
    val secret: String
)

object DeviceIdentityStore {
    fun loadOrCreate(context: Context, serverUrl: String): DeviceIdentity {
        val identityPreferences = context.getSharedPreferences("jay_identity", Context.MODE_PRIVATE)
        val encodedSecret = identityPreferences.getString(SocialPreferences.deviceSecretKey, null)
            ?: ByteArray(32).also(SecureRandom()::nextBytes).let {
                Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE)
            }.also { generated ->
                identityPreferences.edit {
                    putString(SocialPreferences.deviceSecretKey, generated)
                }
            }
        val secret = Base64.decode(encodedSecret, Base64.NO_WRAP or Base64.URL_SAFE)
        val name = Preferences.instance.getString(SocialPreferences.deviceNameKey, null)
            ?: context.assets.open("device_alias_words.json").bufferedReader().use {
                Json.decodeFromString<DeviceNameWords>(it.readText())
            }.let { words ->
                "${words.adjectives.random()} ${words.nouns.random()}"
            }.also { generated ->
                Preferences.edit { putString(SocialPreferences.deviceNameKey, generated) }
            }
        val id = MessageDigest.getInstance("SHA-256").digest(secret).joinToString("") {
            "%02x".format(it)
        }
        val normalizedServer = URI(serverUrl).normalize().toString().trimEnd('/')
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val token = Base64.encodeToString(
            mac.doFinal(normalizedServer.toByteArray()),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return DeviceIdentity(id, name, token, encodedSecret)
    }
}
