package com.vibe.notiflow

import android.app.Application
import com.vibe.notiflow.di.ServiceLocator

class NotiFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
