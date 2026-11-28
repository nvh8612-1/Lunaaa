package com.lunaa.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ghi log ra file: Android/data/com.lunaa.app/files/lunaa_log.txt
object DiagLog {
    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null), "lunaa_log.txt")
    }

    fun write(tag: String, msg: String) {
        Log.d(tag, msg)
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file?.appendText("$time [$tag] $msg\n")
        } catch (_: Exception) {
        }
    }
}
