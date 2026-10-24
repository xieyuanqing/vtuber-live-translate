package com.xyq.livetranslate

import android.app.Application

class LiveTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
        // 进程被杀时 close() 来不及写 endedAt。这里把不是本进程正在写的未结束会话补上结束时间。
        HistoryStore.repairOrphans(this)
    }
}
