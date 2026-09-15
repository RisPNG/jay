package com.bnyro.clock.social.data

import android.content.Context
import android.os.Build
import android.util.Base64
import com.bnyro.clock.BuildConfig
import java.io.File
import java.security.SecureRandom
import java.security.MessageDigest
import java.net.URI

object PlayInstallationStore {
    @Synchronized
    fun credential(context: Context, serverUrl: String): String? {
        if (!BuildConfig.JAY_PLAY_ENTITLEMENT_ELIGIBLE || BuildConfig.DEBUG) return null
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        if (installer != "com.android.vending") return null
        val server = URI(serverUrl).normalize().toString().trimEnd('/')
        val serverKey = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.noBackupFilesDir, "jay-play-installation-$serverKey")
        if (file.exists()) return file.readText()
        val credential = Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        file.writeText(credential)
        return credential
    }
}
