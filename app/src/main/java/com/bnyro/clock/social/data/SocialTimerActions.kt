package com.bnyro.clock.social.data

import android.content.Context

object SocialTimerActions {
    fun adjust(context: Context, timerId: String, expiresAt: Long) {
        SocialTimerWorker.adjust(context, timerId, expiresAt)
    }

    fun cancel(context: Context, timerId: String) {
        SocialTimerWorker.cancel(context, timerId)
    }

    fun dismissed(context: Context, timerId: String, expiresAt: Long) {
        SocialTimerWorker.dismissed(context, timerId, expiresAt)
    }
}
