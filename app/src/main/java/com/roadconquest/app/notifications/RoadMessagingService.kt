package com.roadconquest.app.notifications

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// FCM installation-ID registration uses onRegistered; this app does not request legacy tokens.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class RoadMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) { OptionalNotifications.register(this, installationId) }
    override fun onMessageReceived(message: RemoteMessage) {
        OptionalNotifications.friend(this, message.data["kind"], message.data["binding"])
    }
}
