package org.visualaid.obstacleguide

import android.app.Application

class GuideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StabilityLog.install(this)
    }
}
