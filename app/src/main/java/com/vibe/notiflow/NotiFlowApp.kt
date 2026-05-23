package com.vibe.notiflow

import android.app.Application
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.notification.NotiFlowPushNotifier

class NotiFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        NotiFlowPushNotifier(this).ensureChannel()
    }
}
