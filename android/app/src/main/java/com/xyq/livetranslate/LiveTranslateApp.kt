package com.xyq.livetranslate

import android.app.Application

class LiveTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
    }
}
