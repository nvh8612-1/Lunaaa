// Giao diện <-> Android. AndroidBridge do MainActivity cung cấp qua WebAppBridge.
const DEFAULT_AVATAR = "https://api.dicebear.com/7.x/avataaars/svg?seed=LunaaDefault";

const ICON = {
    micOn: '<svg class="ico"><use href="#i-mic"/></svg>',
    micOff: '<svg class="ico"><use href="#i-mic-off"/></svg>',
    speakerOn: '<svg class="ico"><use href="#i-speaker"/></svg>',
    speakerOff: '<svg class="ico"><use href="#i-speaker-off"/></svg>'
};

let currentPreset = '780p';
let currentRoomId = '';
let inCall = false;
let callExpanded = false;
let micOn = true;
let speakerOn = true;
let screenOn = false;
let callSeconds = 0;
let timerId = null;
let peerList = [];

function $(id) { return document.getElementById(id); }

const SPEAK_THRESHOLD = 0.02;
let micSince = 0;
let micSignal = false;
let lastSelf = 0;

// Báo trạng thái mic ngay trên màn hình để biết mic có nhận tiếng hay không
function updateMicStatus(v) {
    const el = $('micStatus');
    if (!el) return;
    if (v > 0.001) micSignal = true;
    if (!micOn) {
        el.textContent = 'Mic đang tắt';
    } else if (micSignal) {
        el.textContent = 'Mic đang hoạt động';
    } else if (micSince && Date.now() - micSince > 4000) {
        el.textContent = 'Chưa nhận tiếng từ micro. Kiểm tra quyền micro của Lunaa trong Cài đặt điện thoại.';
    } else {
        el.textContent = 'Đang kiểm tra micro...';
    }
}

// Kotlin gọi 4 lần/giây với JSON {"self":0.1,"<peerId>":0.0}
function onAudioLevels(json) {
    const lv = JSON.parse(json);
    lastSelf = lv.self || 0;
    $('selfTile').classList.toggle('speaking', micOn && (lv.self || 0) > SPEAK_THRESHOLD);
    document.querySelectorAll('.tile.peer').forEach(t => {
        t.classList.toggle('speaking', (lv[t.dataset.id] || 0) > SPEAK_THRESHOLD);
    });
}

function hasBridge(fn) {
    return !!(window.AndroidBridge && typeof window.AndroidBridge[fn] === 'function');
}

function avatarOf(seed) {
    return `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(seed)}`;
}

document.addEventListener('DOMContentLoaded', () => {
    setAvatar(localStorage.getItem('lunaa_avatar') || DEFAULT_AVATAR);
    renderMic();
    setSpeakerState(true);
    renderTiles();
    refreshViews();
    if (hasBridge('onUiReady')) window.AndroidBridge.onUiReady();
});

window.addEventListener('resize', () => reportVideoRect());

function setAvatar(url) {
    $('userAvatar').src = url;
    $('callAvatar').src = url;
}

function changeAvatar() {
    const seed = Math.random().toString(36).substring(7);
    const url = avatarOf(seed);
    setAvatar(url);
    localStorage.setItem('lunaa_avatar', url);
    if (hasBridge('updateAvatar')) window.AndroidBridge.updateAvatar(url);
}

function selectPreset(preset, el) {
    currentPreset = preset;
    document.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    if (hasBridge('setPreset')) window.AndroidBridge.setPreset(preset);
}

function generateRoomId() {
    return 'lunaa-' + Math.random().toString(36).substring(2, 8);
}

function createRoom() {
    const input = $('roomInput');
    const roomId = input.value.trim() || generateRoomId();
    input.value = roomId;
    if (hasBridge('createRoom')) window.AndroidBridge.createRoom(roomId, currentPreset);
    else showCallControls(roomId);
}

function joinRoom() {
    const roomId = $('roomInput').value.trim();
    if (!roomId) {
        alert('Vui lòng nhập mã phòng!');
        return;
    }
    if (hasBridge('joinRoom')) window.AndroidBridge.joinRoom(roomId, currentPreset);
    else showCallControls(roomId);
}

function leaveRoom(e) {
    if (e) e.stopPropagation();
    if (hasBridge('leaveRoom')) window.AndroidBridge.leaveRoom();
    hideCallControls();
}

// Kotlin quyết định bật/tắt: đang chia sẻ thì dừng, chưa thì mở hộp chọn màn hình
function toggleScreenShare() {
    if (hasBridge('toggleScreenShare')) window.AndroidBridge.toggleScreenShare();
    else alert('Chia sẻ màn hình chỉ có trên app Android');
}

// Kotlin gọi khi trạng thái chia sẻ đổi
function setScreenState(on) {
    screenOn = on;
    $('screenBtn').classList.toggle('active', on);
}

function connectShizuku() {
    if (hasBridge('connectShizuku')) window.AndroidBridge.connectShizuku();
    else alert('Shizuku chỉ dùng được trên app Android');
}

function copyRoomId(e) {
    if (e) e.stopPropagation();
    if (!currentRoomId) return;
    try { navigator.clipboard.writeText(currentRoomId); } catch (_) {}
    alert('Mã phòng: ' + currentRoomId);
}

// ---- Mic: mở khi vào phòng, người dùng có thể tắt ----
function toggleMic(e) {
    if (e) e.stopPropagation();
    setMic(!micOn);
}

function setMicFromSwitch(el) {
    setMic(!el.checked);
}

function setMic(on) {
    micOn = on;
    renderMic();
    if (inCall && hasBridge('onMicToggle')) window.AndroidBridge.onMicToggle(on);
}

function renderMic() {
    const icon = micOn ? ICON.micOn : ICON.micOff;
    $('micBtn').innerHTML = icon;
    $('micBtn').classList.toggle('off', !micOn);
    $('miniMicBtn').innerHTML = icon;
    $('tileMicState').innerHTML = icon;
    $('swMute').checked = !micOn;
}

// ---- Loa ----
function toggleSpeaker() {
    const next = !speakerOn;
    if (hasBridge('onSpeakerToggle')) window.AndroidBridge.onSpeakerToggle(next);
    setSpeakerState(next);
}

// Kotlin gọi lại khi trạng thái loa thay đổi
function setSpeakerState(on) {
    speakerOn = on;
    const btn = $('speakerBtn');
    btn.innerHTML = on ? ICON.speakerOn : ICON.speakerOff;
    btn.classList.toggle('speaker-off', !on);
}

// ---- Người tham gia và ô màn hình chia sẻ ----
// Kotlin gọi với chuỗi JSON: [{"id":"...","name":"...","sharing":false}]
function onParticipants(json) {
    peerList = JSON.parse(json);
    renderTiles();
}

function renderTiles() {
    const wrap = $('tiles');
    wrap.querySelectorAll('.tile.peer, .tile.screen').forEach(n => n.remove());

    const sharer = peerList.find(p => p.sharing);
    if (sharer) {
        const t = document.createElement('div');
        t.className = 'tile screen';
        const slot = document.createElement('div');
        slot.className = 'video-slot';
        const pill = document.createElement('div');
        pill.className = 'name-pill';
        pill.textContent = `${sharer.name} đang chia sẻ màn hình`;
        t.append(slot, pill);
        wrap.prepend(t);
    }

    peerList.forEach(p => {
        const tile = document.createElement('div');
        tile.className = 'tile peer';
        tile.dataset.id = p.id;
        const img = document.createElement('img');
        img.className = 'tile-avatar';
        img.src = avatarOf(p.id);
        img.alt = '';
        const pill = document.createElement('div');
        pill.className = 'name-pill';
        pill.textContent = p.name;
        tile.append(img, pill);
        wrap.appendChild(tile);
    });

    wrap.classList.toggle('has-screen', !!sharer);
    wrap.classList.toggle('two', !sharer && peerList.length >= 1);
    requestAnimationFrame(reportVideoRect);
}

// Báo cho Android vị trí ô video để đặt khung hình đúng chỗ
function reportVideoRect() {
    if (!hasBridge('setVideoRect')) return;
    const slot = document.querySelector('.video-slot');
    if (!slot || !callExpanded) {
        window.AndroidBridge.setVideoRect(0, 0, 0, 0);
        return;
    }
    const r = slot.getBoundingClientRect();
    window.AndroidBridge.setVideoRect(r.left, r.top, r.width, r.height);
}

// ---- Chat ----
function toggleChat() {
    $('chatPanel').classList.toggle('open');
}

// Kotlin gọi với chuỗi JSON: {"name":"...","text":"...","self":true}
function onChatMessage(json) {
    const m = JSON.parse(json);
    const div = document.createElement('div');
    div.className = 'msg' + (m.self ? ' me' : '');
    const who = document.createElement('b');
    who.textContent = m.name;
    const txt = document.createElement('div');
    txt.textContent = m.text;
    div.append(who, txt);
    const list = $('chatList');
    list.appendChild(div);
    list.scrollTop = list.scrollHeight;
}

function sendChatMessage() {
    const input = $('chatInput');
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    if (hasBridge('sendChat')) window.AndroidBridge.sendChat(text);
}

// ---- Thu nhỏ / mở rộng ----
function toggleSheet() {
    $('sheet').classList.toggle('open');
}

function minimizeCall() {
    callExpanded = false;
    refreshViews();
}

function expandCall() {
    if (!inCall) return;
    callExpanded = true;
    refreshViews();
}

// Nút Back từ Android: trả true nếu đã xử lý, false để Android thoát
function handleBack() {
    if ($('chatPanel').classList.contains('open')) {
        $('chatPanel').classList.remove('open');
        return true;
    }
    if (callExpanded) {
        minimizeCall();
        return true;
    }
    return false;
}

function refreshViews() {
    $('callScreen').style.display = callExpanded ? 'flex' : 'none';
    $('lobby').style.display = callExpanded ? 'none' : 'flex';
    $('miniCall').style.display = (inCall && !callExpanded) ? 'flex' : 'none';
    reportVideoRect();
}

// Kotlin gọi khi vào/tạo phòng thành công
function showCallControls(roomId) {
    currentRoomId = roomId;
    inCall = true;
    callExpanded = true;
    $('topbarRoom').textContent = roomId;
    $('miniRoom').textContent = roomId;
    $('inviteRoom').textContent = roomId;
    peerList = [];
    renderTiles();

    callSeconds = 0;
    updateTimer();
    if (timerId) clearInterval(timerId);
    timerId = setInterval(() => { callSeconds++; updateTimer(); }, 1000);

    micSince = Date.now();
    micSignal = false;
    setMic(true);
    setSpeakerState(true);
    if (hasBridge('onSpeakerToggle')) window.AndroidBridge.onSpeakerToggle(true);
    refreshViews();
}

// Kotlin gọi khi rời phòng
function hideCallControls() {
    inCall = false;
    callExpanded = false;
    currentRoomId = '';
    if (timerId) clearInterval(timerId);
    timerId = null;
    peerList = [];
    renderTiles();
    setScreenState(false);
    $('micStatus').textContent = '';
    micSince = 0;
    $('sheet').classList.remove('open');
    $('chatPanel').classList.remove('open');
    $('chatList').innerHTML = '';
    $('topbarRoom').textContent = 'Phòng';
    refreshViews();
}

function updateTimer() {
    const m = Math.floor(callSeconds / 60);
    const s = callSeconds % 60;
    const t = `${m}:${s < 10 ? '0' : ''}${s}`;
    $('callTimerText').textContent = 'Time: ' + t;
    $('miniTime').textContent = t;
    updateMicStatus(lastSelf);
}
