package com.jisr.transfer

import android.app.Application

class JisrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        TransferHub.init(this)
    }
}
