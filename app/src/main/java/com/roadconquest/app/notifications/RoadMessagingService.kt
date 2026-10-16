package com.roadconquest.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RoadMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) { OptionalNotifications.register(this, installationId) }
    override fun onMessageReceived(message: RemoteMessage) {
        OptionalNotifications.friend(this, message.data["kind"], message.data["binding"])
    }
}
