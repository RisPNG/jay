package com.bnyro.clock.social.data

import android.content.Context

object SocialTimerActions {
    fun adjust(context: Context, timerId: String, action: String) {
        SocialTimerWorker.adjust(context, timerId, action)
    }

    fun cancel(context: Context, timerId: String) {
        SocialTimerWorker.cancel(context, timerId)
    }

    fun dismissed(context: Context, timerId: String) {
        SocialTimerWorker.dismissed(context, timerId)
    }
}
