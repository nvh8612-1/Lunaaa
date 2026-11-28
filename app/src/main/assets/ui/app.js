// Cầu nối giao diện <-> Android. AndroidBridge do MainActivity cung cấp qua WebAppBridge.
let currentPreset = '780p';

function hasBridge(fn) {
    return !!(window.AndroidBridge && typeof window.AndroidBridge[fn] === 'function');
}

document.addEventListener("DOMContentLoaded", () => {
    const savedAvatar = localStorage.getItem("lunaa_avatar") ||
        "https://api.dicebear.com/7.x/avataaars/svg?seed=LunaaDefault";
    document.getElementById("userAvatar").src = savedAvatar;
    if (hasBridge('onUiReady')) window.AndroidBridge.onUiReady();
});

function changeAvatar() {
    const seed = Math.random().toString(36).substring(7);
    const newAvatarUrl = `https://api.dicebear.com/7.x/avataaars/svg?seed=${seed}`;
    document.getElementById("userAvatar").src = newAvatarUrl;
    localStorage.setItem("lunaa_avatar", newAvatarUrl);
    if (hasBridge('updateAvatar')) window.AndroidBridge.updateAvatar(newAvatarUrl);
}

function selectPreset(preset, element) {
    currentPreset = preset;
    document.querySelectorAll('.radio-card').forEach(card => {
        card.classList.remove('active');
        card.querySelector('input').checked = false;
    });
    element.classList.add('active');
    element.querySelector('input').checked = true;
    if (hasBridge('setPreset')) window.AndroidBridge.setPreset(preset);
}

function createRoom() {
    const input = document.getElementById("roomInput");
    const roomId = input.value.trim() || generateRoomId();
    input.value = roomId;
    if (hasBridge('createRoom')) {
        window.AndroidBridge.createRoom(roomId, currentPreset);
    } else {
        alert("Tạo phòng: " + roomId + " với preset " + currentPreset);
        showCallControls(roomId);
    }
}

function joinRoom() {
    const roomId = document.getElementById("roomInput").value.trim();
    if (!roomId) {
        alert("Vui lòng nhập mã phòng!");
        return;
    }
    if (hasBridge('joinRoom')) {
        window.AndroidBridge.joinRoom(roomId, currentPreset);
    } else {
        alert("Vào phòng: " + roomId);
        showCallControls(roomId);
    }
}

function leaveRoom() {
    if (hasBridge('leaveRoom')) window.AndroidBridge.leaveRoom();
    hideCallControls();
}

function toggleScreenShare() {
    if (hasBridge('toggleScreenShare')) {
        window.AndroidBridge.toggleScreenShare();
    } else {
        alert("Chia sẻ màn hình (chỉ có trên app Android)");
    }
}

function connectShizuku() {
    if (hasBridge('connectShizuku')) {
        window.AndroidBridge.connectShizuku();
    } else {
        alert("Shizuku chỉ dùng được trên app Android");
    }
}

function generateRoomId() {
    return 'lunaa-' + Math.random().toString(36).substring(2, 8);
}

let callTimerInterval = null;
let callSeconds = 0;

// Được gọi từ Kotlin qua evaluateJavascript
function showCallControls(roomId) {
    document.getElementById("roomStatusText").textContent = `RoomID: ${roomId}`;
    callSeconds = 0;
    document.getElementById("callTimerText").textContent = "Time: 0:00";
    if (callTimerInterval) clearInterval(callTimerInterval);
    callTimerInterval = setInterval(() => {
        callSeconds++;
        const mins = Math.floor(callSeconds / 60);
        const secs = callSeconds % 60;
        document.getElementById("callTimerText").textContent =
            `Time: ${mins}:${secs < 10 ? '0' : ''}${secs}`;
    }, 1000);
    document.getElementById("callCard").style.display = "flex";
}

function hideCallControls() {
    if (callTimerInterval) clearInterval(callTimerInterval);
    callTimerInterval = null;
    document.getElementById("callCard").style.display = "none";
}
