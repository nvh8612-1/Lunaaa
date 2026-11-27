package com.lunaa.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URL

object AvatarHelper {

    fun compressAvatar(urlStr: String, maxWidth: Int = 128, maxHeight: Int = 128): Bitmap? {
        return try {
            val url = URL(urlStr)
            val inputStream = url.openStream()
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (originalBitmap == null) return null

            // Scale down to 128x128px to save RAM
            Bitmap.createScaledBitmap(originalBitmap, maxWidth, maxHeight, true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
}
