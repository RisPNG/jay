package com.bnyro.clock.social.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SocialPushMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] == "sync") {
            SocialSyncWorker.enqueue(this)
        }
    }

    override fun onNewToken(token: String) {
        PushRegistrationWorker.enqueue(this, token)
    }
}
