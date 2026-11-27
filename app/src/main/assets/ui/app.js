// JavaScript interface wrapper for Android WebAppBridge
let currentPreset = '780p';

document.addEventListener("DOMContentLoaded", () => {
    // Load saved avatar or default
    const savedAvatar = localStorage.getItem("lunaa_avatar") || "https://api.dicebear.com/7.x/avataaars/svg?seed=LunaaDefault";
    document.getElementById("userAvatar").src = savedAvatar;
    
    // Notify native bridge if ready
    if (window.AndroidBridge && window.AndroidBridge.onUiReady) {
        window.AndroidBridge.onUiReady();
    }
});

function changeAvatar() {
    // Generate random avatar seed
    const seed = Math.random().toString(36.substring(7));
    const newAvatarUrl = `https://api.dicebear.com/7.x/avataaars/svg?seed=${seed}`;
    document.getElementById("userAvatar").src = newAvatarUrl;
    localStorage.setItem("lunaa_avatar", newAvatarUrl);

    if (window.AndroidBridge && window.AndroidBridge.updateAvatar) {
        window.AndroidBridge.updateAvatar(newAvatarUrl);
    }
}

function selectPreset(preset, element) {
    currentPreset = preset;
    document.querySelectorAll('.radio-card').forEach(card => {
        card.classList.remove('active');
        card.querySelector('input').checked = false;
    });
    element.classList.add('active');
    element.querySelector('input').checked = true;

    if (window.AndroidBridge && window.AndroidBridge.setPreset) {
        window.AndroidBridge.setPreset(preset);
    }
}

function createRoom() {
    const roomId = document.getElementById("roomInput").value.trim() || generateRoomId();
    document.getElementById("roomInput").value = roomId;

    if (window.AndroidBridge) {
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

    if (window.AndroidBridge) {
        window.AndroidBridge.joinRoom(roomId, currentPreset);
    } else {
        alert("Vào phòng: " + roomId);
        showCallControls(roomId);
    }
}

function leaveRoom() {
    if (window.AndroidBridge) {
        window.AndroidBridge.leaveRoom();
    }
    hideCallControls();
}

function toggleScreenShare() {
    if (window.AndroidBridge && window.AndroidBridge.toggleScreenShare) {
        window.AndroidBridge.toggleScreenShare();
    }
}

function connectShizuku() {
    if (window.AndroidBridge && window.AndroidBridge.connectShizuku) {
        window.AndroidBridge.connectShizuku();
    } else {
        alert("Shizuku kết nối (Môi trường Web thuần)");
    }
}

function generateRoomId() {
    return 'lunaa-' + Math.random().toString(36).substring(2, 8);
}

let callTimerInterval = null;
let callSeconds = 0;

// Callbacks from Native Kotlin
function showCallControls(roomId) {
    document.getElementById("roomStatusText").textContent = `RoomID: ${roomId}`;
    callSeconds = 0;
    document.getElementById("callTimerText").textContent = `Time: 0:00`;
    if (callTimerInterval) clearInterval(callTimerInterval);
    callTimerInterval = setInterval(() => {
        callSeconds++;
        const mins = Math.floor(callSeconds / 60);
        const secs = callSeconds % 60;
        document.getElementById("callTimerText").textContent = `Time: ${mins}:${secs < 10 ? '0' : ''}${secs}`;
    }, 1000);
    document.getElementById("callCard").style.display = "flex";
}

function hideCallControls() {
    if (callTimerInterval) clearInterval(callTimerInterval);
    document.getElementById("callCard").style.display = "none";
}
