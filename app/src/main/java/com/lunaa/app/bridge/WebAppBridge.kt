package com.lunaa.app.bridge

import android.webkit.JavascriptInterface

class WebAppBridge(private val callback: BridgeCallback) {

    interface BridgeCallback {
        fun onRoomCreate(roomId: String, preset: String)
        fun onRoomJoin(roomId: String, preset: String)
        fun onLeaveRoom()
        fun onToggleScreenShare()
        fun onUpdateAvatar(avatarUrl: String)
        fun onSetPreset(preset: String)
        fun onUiReady()
        fun onConnectShizuku()
    }

    @JavascriptInterface
    fun createRoom(roomId: String, preset: String) { callback.onRoomCreate(roomId, preset) }

    @JavascriptInterface
    fun joinRoom(roomId: String, preset: String) { callback.onRoomJoin(roomId, preset) }

    @JavascriptInterface
    fun leaveRoom() { callback.onLeaveRoom() }

    @JavascriptInterface
    fun toggleScreenShare() { callback.onToggleScreenShare() }

    @JavascriptInterface
    fun updateAvatar(avatarUrl: String) { callback.onUpdateAvatar(avatarUrl) }

    @JavascriptInterface
    fun setPreset(preset: String) { callback.onSetPreset(preset) }

    @JavascriptInterface
    fun onUiReady() { callback.onUiReady() }

    @JavascriptInterface
    fun connectShizuku() { callback.onConnectShizuku() }
}
