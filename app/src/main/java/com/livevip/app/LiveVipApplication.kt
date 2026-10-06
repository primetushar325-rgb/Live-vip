package com.livevip.app

import android.app.Application
import com.livevip.app.storage.SecureSettingsStore

class LiveVipApplication : Application() {
    lateinit var settings: SecureSettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettingsStore(this)
    }
}
