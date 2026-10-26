package io.github.ahdcamera

import android.app.Application
import io.github.ahdcamera.diagnostics.Diagnostics

class AhdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.restoreLegacy(this)
        Diagnostics.initialize(this)
    }
}
