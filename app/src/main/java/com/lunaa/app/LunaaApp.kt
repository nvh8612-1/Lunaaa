package com.lunaa.app

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

class LunaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            DiagLog.write("CRASH", "luồng ${thread.name}:\n$sw")
            previous?.uncaughtException(thread, error)
        }
    }
}
