'use strict';

/* =====================================================================
   BATTLE ARENA — client
   ===================================================================== */

// For the Android (Capacitor) build, set your server host, e.g. 'battle.example.com'.
// Leave empty when the game is served by the same server (browser build).
const REMOTE_HOST = '';

const $ = (id) => document.getElementById(id);
const wsProtocol = (REMOTE_HOST || window.location.protocol === 'https:') ? 'wss://' : 'ws://';
const ws = new WebSocket(wsProtocol + (REMOTE_HOST || window.location.host) + '/game');
const pendingQueue = [];
let wsOpen = false;

// ---------- Loading screen ----------
function hideLoadingScreen() {
  const loader = $('loading-screen');
  if (!loader) return;
  loader.classList.add('hidden');
  setTimeout(() => loader.remove(), 400);
}

// Startup order: 1) studio splash  2) Battle Arena loading screen  3) main menu
// The loading bar only starts after the splash ends, and the menu appears
// only when the bar is full AND the server connection is open.
const LOADING_MIN_MS = 2500;
const loaderBar = $('loader-bar');
let splashDoneAt = 0;
window.addEventListener('splash-done', () => { splashDoneAt = Date.now(); });

const loaderInterval = setInterval(() => {
  if (!splashDoneAt) return;
  const elapsed = Date.now() - splashDoneAt;
  const pct = Math.min((elapsed / LOADING_MIN_MS) * 100, wsOpen ? 100 : 92);
  if (loaderBar) loaderBar.style.width = pct + '%';
  if (pct >= 100) {
    clearInterval(loaderInterval);
    setTimeout(hideLoadingScreen, 300);
  } else if (elapsed > 10000 && !wsOpen) {
    const t = document.querySelector('.loader-text');
    if (t) t.textContent = 'Cannot reach the server. Check your connection.';
  }
}, 100);

ws.addEventListener('open', () => {
  wsOpen = true;
  while (pendingQueue.length) ws.send(pendingQueue.shift());
});

ws.addEventListener('close', () => { wsOpen = false; });

function send(type, data) {
  const payload = JSON.stringify({ type, data: data || {} });
  if (wsOpen) ws.send(payload); else pendingQueue.push(payload);
}

ws.addEventListener('message', (event) => {
  let msg;
  try { msg = JSON.parse(event.data); } catch (e) { return; }
  handleServerMessage(msg.type, msg.data);
});

function handleServerMessage(type, data) {
  const handlers = {
    joined: onJoined, roomUpdate: onRoomUpdate, countdown: onCountdown,
    soloWait: onSoloWait, gameStart: onGameStart, state: onState,
    playerEliminated: onPlayerEliminated, playerLeft: onPlayerLeft, matchResult: onMatchResult
  };
  if (handlers[type]) handlers[type](data);
}

// ---------- Screen navigation ----------
function showScreen(id) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  const screen = $(id);
  if (screen) screen.classList.add('active');
}

document.querySelectorAll('[data-back]').forEach(el =>
  el.addEventListener('click', () => showScreen(el.dataset.back)));

$('btn-play').addEventListener('click', () => showScreen('screen-room-size'));
$('btn-howto').addEventListener('click', () => showScreen('screen-howto'));
$('btn-settings').addEventListener('click', () => showScreen('screen-settings'));
$('btn-about').addEventListener('click', () => showScreen('screen-about'));

// ---------- Room size -> character -> name ----------
let selectedRoomSize = 4;
document.querySelectorAll('.room-size-btn').forEach(btn =>
  btn.addEventListener('click', () => {
    selectedRoomSize = parseInt(btn.dataset.size, 10);
    showScreen('screen-character');
  }));

let selectedCharacter = 'penguin';
document.querySelectorAll('.character-card').forEach(card =>
  card.addEventListener('click', () => {
    if (card.dataset.available !== 'true') return;
    selectedCharacter = card.dataset.character;
    document.querySelectorAll('.character-card').forEach(c => c.classList.remove('selected'));
    card.classList.add('selected');
  }));

$('btn-character-continue').addEventListener('click', () => showScreen('screen-name'));
$('btn-join').addEventListener('click', joinGame);
$('input-name').addEventListener('input', () => {
  $('name-error').textContent = '';
  $('input-name').classList.remove('invalid');
});
$('input-name').addEventListener('keydown', (e) => { if (e.key === 'Enter') joinGame(); });
$('btn-play-again').addEventListener('click', () => showScreen('screen-name'));
$('btn-leave-match').addEventListener('click', leaveMatch);
$('btn-cancel-wait').addEventListener('click', leaveMatch);

let playerName = '';

function joinGame() {
  // A name is required: no anonymous players
  playerName = $('input-name').value.trim().replace(/[<>]/g, '');
  if (playerName.length < 2) {
    $('name-error').textContent = 'Enter a name (at least 2 characters) to join.';
    $('input-name').classList.add('invalid');
    $('input-name').focus();
    return;
  }
  $('name-error').textContent = '';
  $('input-name').classList.remove('invalid');
  showScreen('screen-waiting');
  $('waiting-title').textContent = 'Finding players…';
  lastRoom = null;
  $('waiting-list').innerHTML = '';
  $('wait-timer').classList.remove('show');
  send('joinGame', { name: playerName, roomSize: selectedRoomSize, characterType: selectedCharacter });
}

function leaveMatch() {
  send('leaveMatch');
  selfId = null;
  spectateId = null;
  gameStartTime = null;
  latestState = { players: [], bullets: [] };
  showScreen('screen-start');
}

// ---------- Game state ----------
let selfId = null;
let mapInfo = { width: 1600, height: 1200, walls: [] };
let latestState = { players: [], bullets: [] };
let gameStartTime = null;
let decorations = [];

// ---------- Spectator mode (after you are eliminated) ----------
let spectateId = null;
const selfPlayer = () => latestState.players.find(p => p.id === selfId);
const isSpectating = () => { const me = selfPlayer(); return !!me && !me.alive; };
const aliveOthers = () => latestState.players.filter(p => p.alive && p.id !== selfId);

// The player whose view we show: yourself while alive, otherwise a living player
function viewedPlayer() {
  const me = selfPlayer();
  if (!me || me.alive) return me;
  const others = aliveOthers();
  if (!others.length) return me;
  let target = others.find(p => p.id === spectateId);
  if (!target) { target = others[0]; spectateId = target.id; }
  return target;
}

function cycleSpectate(dir) {
  const others = aliveOthers();
  if (!others.length) return;
  const i = others.findIndex(p => p.id === spectateId);
  spectateId = others[(i + dir + others.length) % others.length].id;
  updateHUD();
}

function onJoined(data) {
  selfId = data.selfId;
  mapInfo = data.map;
  decorations = data.map.decorations || [];
  renderLobby();
}

const CHAR_EMOJI = { penguin: '🐧', bear: '🐻', fox: '🦊', wolf: '🐺', horse: '🐴' };
const SOLO_WAIT_TOTAL = 20; // must match SOLO_WAIT_SECONDS on the server
let lastRoom = null;

function onRoomUpdate(data) {
  lastRoom = data;
  renderLobby();
}

// One slot per seat in the room: filled seats show the player's animal and name
function renderLobby() {
  if (!lastRoom) return;
  const d = lastRoom;
  $('player-count').textContent = `Players: ${d.players.length}/${d.maxPlayers}`;
  const list = $('waiting-list');
  list.innerHTML = '';
  for (let i = 0; i < d.maxPlayers; i++) {
    const p = d.players[i];
    const li = document.createElement('li');
    li.className = 'slot' + (p ? ' filled' : '') + (p && p.id === selfId ? ' me' : '');
    const avatar = document.createElement('span');
    avatar.className = 'slot-avatar';
    avatar.textContent = p ? (CHAR_EMOJI[p.characterType] || '🐧') : '?';
    const name = document.createElement('span');
    name.className = 'slot-name';
    name.textContent = p ? p.name + (p.id === selfId ? ' (you)' : '') : 'Waiting…';
    li.append(avatar, name);
    list.appendChild(li);
  }
  if (d.players.length !== 1) $('wait-timer').classList.remove('show');
}

function onCountdown(value) {
  showScreen('screen-countdown');
  $('countdown-number').textContent = value > 0 ? value : 'GO!';
}

function onSoloWait(data) {
  const s = data.secondsRemaining;
  $('wait-timer').classList.add('show');
  $('wait-timer-fill').style.width = Math.max(0, (s / SOLO_WAIT_TOTAL) * 100) + '%';
  $('wait-timer-text').textContent = `No opponents yet. Bots join in ${s}s`;
}

function onGameStart(data) {
  gameStartTime = data.startTime;
  spectateId = null;
  lastHpTop = -1; // force the HP bar to re-position for this match
  showScreen('screen-game');
  resizeCanvas();
  requestAnimationFrame(renderLoop);
}

function onState(data) {
  latestState = data;
  updateHUD();
}

const killFeedEl = $('kill-feed');
function pushFeed(text) {
  const item = document.createElement('div');
  item.className = 'kill-feed-item';
  item.textContent = text;
  killFeedEl.appendChild(item);
  setTimeout(() => item.remove(), 3000);
}
function onPlayerEliminated(d) { pushFeed(`${d.name} was eliminated by ${d.by}`); }
function onPlayerLeft(d) { pushFeed(`${d.name} left the match`); }

const pad2 = (n) => String(n).padStart(2, '0');
const fmtTime = (ms) => { const s = Math.floor((ms || 0) / 1000); return `${pad2(Math.floor(s / 60))}:${pad2(s % 60)}`; };

function onMatchResult(data) {
  const isWinner = data.winner && data.winner.name === playerName;
  const title = $('result-title');
  title.textContent = isWinner ? 'VICTORY!' : 'DEFEATED';
  title.style.color = isWinner ? '#ffc83d' : '#ff5a47';
  $('result-sub').textContent = data.winner ? `Winner: ${data.winner.name}` : 'No survivors';

  const self = data.players.find(p => p.name === playerName);
  $('result-kills').textContent = `Kills: ${self ? self.kills : 0}`;
  $('result-time').textContent = `Survival Time: ${fmtTime(data.elapsed)}`;

  const listEl = $('result-list');
  listEl.innerHTML = '';
  [...data.players].sort((a, b) => b.kills - a.kills).forEach((p, i) => {
    // SECURITY: textContent only, never innerHTML, so player names cannot inject HTML
    const row = document.createElement('div');
    const a = document.createElement('span');
    const b = document.createElement('span');
    a.textContent = `${i + 1}. ${p.name}`;
    b.textContent = `${p.kills} Kills`;
    row.append(a, b);
    listEl.appendChild(row);
  });
  showScreen('screen-result');
}

// ---------- Canvas ----------
const canvas = $('game-canvas');
const ctx = canvas.getContext('2d');
function resizeCanvas() { canvas.width = window.innerWidth; canvas.height = window.innerHeight; }
window.addEventListener('resize', resizeCanvas);
resizeCanvas();

// ---------- Keyboard / mouse / touch ----------
const keys = { up: false, down: false, left: false, right: false };
const KEYMAP = { w: 'up', arrowup: 'up', s: 'down', arrowdown: 'down', a: 'left', arrowleft: 'left', d: 'right', arrowright: 'right' };

window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT') return;
  const k = e.key.toLowerCase();
  if (isSpectating()) {
    if (k === 'a' || k === 'arrowleft') cycleSpectate(-1);
    else if (k === 'd' || k === 'arrowright') cycleSpectate(1);
    return;
  }
  if (KEYMAP[k]) keys[KEYMAP[k]] = true;
  else if (k === 'r') send('reload');
});
window.addEventListener('keyup', (e) => {
  const k = e.key.toLowerCase();
  if (KEYMAP[k]) keys[KEYMAP[k]] = false;
});

let mouseX = 0, mouseY = 0, aimAngle = 0, mouseDown = false;
canvas.addEventListener('mousemove', (e) => { mouseX = e.clientX; mouseY = e.clientY; });
canvas.addEventListener('mousedown', () => {
  if (isSpectating()) cycleSpectate(1); else mouseDown = true;
});
window.addEventListener('mouseup', () => { mouseDown = false; });
$('btn-reload-mobile').addEventListener('click', () => send('reload'));
$('btn-spec-prev').addEventListener('click', () => cycleSpectate(-1));
$('btn-spec-next').addEventListener('click', () => cycleSpectate(1));

function setupJoystick(baseEl, knobEl, onMove, onEnd) {
  let active = false, baseRect = null, touchId = null;

  baseEl.addEventListener('touchstart', (e) => {
    e.preventDefault();
    touchId = e.changedTouches[0].identifier;
    active = true;
    baseRect = baseEl.getBoundingClientRect();
  }, { passive: false });

  window.addEventListener('touchmove', (e) => {
    if (!active) return;
    const t = [...e.changedTouches].find(x => x.identifier === touchId);
    if (!t) return;
    e.preventDefault();
    const dx = t.clientX - (baseRect.left + baseRect.width / 2);
    const dy = t.clientY - (baseRect.top + baseRect.height / 2);
    const max = baseRect.width / 2;
    const dist = Math.min(Math.hypot(dx, dy), max);
    const angle = Math.atan2(dy, dx);
    knobEl.style.transform = `translate(calc(-50% + ${Math.cos(angle) * dist}px), calc(-50% + ${Math.sin(angle) * dist}px))`;
    onMove(dx / max, dy / max, dist / max, angle);
  }, { passive: false });

  const end = (e) => {
    if (![...e.changedTouches].some(x => x.identifier === touchId)) return;
    active = false;
    touchId = null;
    knobEl.style.transform = 'translate(-50%, -50%)';
    onEnd();
  };
  window.addEventListener('touchend', end, { passive: false });
  window.addEventListener('touchcancel', end, { passive: false });
}

setupJoystick($('joystick-move'), $('joystick-move-knob'),
  (nx, ny) => { keys.up = ny < -0.3; keys.down = ny > 0.3; keys.left = nx < -0.3; keys.right = nx > 0.3; },
  () => { keys.up = keys.down = keys.left = keys.right = false; });

let touchAiming = false;
setupJoystick($('joystick-aim'), $('joystick-aim-knob'),
  (nx, ny, strength, angle) => { aimAngle = angle; touchAiming = strength > 0.2; },
  () => { touchAiming = false; });

// ---------- Input loop (30 Hz) ----------
const isTouch = window.matchMedia('(hover: none) and (pointer: coarse)').matches;

setInterval(() => {
  if (!selfId || isSpectating()) return;
  if (!isTouch) {
    const me = latestState.players.find(p => p.id === selfId);
    if (me) {
      const sp = worldToScreen(me.x, me.y);
      aimAngle = Math.atan2(mouseY - sp.y, mouseX - sp.x);
    }
  }
  send('input', { up: keys.up, down: keys.down, left: keys.left, right: keys.right, angle: aimAngle });
  if (mouseDown || touchAiming) send('shoot');
}, 1000 / 30);

// ---------- Camera ----------
let camX = 0, camY = 0;
function updateCamera() {
  const me = viewedPlayer();
  if (me) { camX = me.x - canvas.width / 2; camY = me.y - canvas.height / 2; }
}
function worldToScreen(x, y) { return { x: x - camX, y: y - camY }; }

// ---------- HUD ----------
function updateHUD() {
  const spec = isSpectating();
  $('screen-game').classList.toggle('spectating', spec);
  const me = viewedPlayer();
  if (spec) {
    $('spectate-name').textContent = me && me.id !== selfId ? 'Spectating ' + me.name : 'Waiting for result…';
  }
  if (me) {
    const hp = Math.max(0, me.health);
    $('hp-bar').style.width = hp + '%';
    $('hp-text').textContent = `${hp} / 100`;
    $('ammo-text').textContent = me.reloading ? 'Reloading…' : `${me.ammo} / ${me.reserveAmmo}`;
  }
  $('alive-count').textContent = `PLAYERS: ${latestState.players.filter(p => p.alive).length}`;
  $('timer').textContent = fmtTime(latestState.elapsed);

  const board = $('leaderboard');
  board.innerHTML = '';
  const head = document.createElement('div');
  head.style.cssText = 'font-weight:800;margin-bottom:4px;';
  head.textContent = 'Leaderboard';
  board.appendChild(head);
  // Compact: top 3 plus your own row (if you are not already in the top 3)
  const ranked = [...latestState.players].sort((a, b) => b.kills - a.kills);
  const rows = ranked.slice(0, 3).map((p, i) => ({ p, i }));
  const myIdx = ranked.findIndex(p => p.id === selfId);
  if (myIdx >= 3) rows.push({ p: ranked[myIdx], i: myIdx });
  rows.forEach(({ p, i }) => {
    const row = document.createElement('div');
    row.textContent = `${i + 1}. ${p.name} — ${p.kills} Kills${p.alive ? '' : ' 💀'}`;
    if (p.id === selfId) row.style.color = '#5ce1b9';
    board.appendChild(row);
  });
}

/* =====================================================================
   RENDERING — arena theme (matches the UI palette)
   ===================================================================== */
const INK = '#0a1f1e';
const ROOFS = [['#ff5a47', '#e24632'], ['#2f8f8b', '#23706c'], ['#ffc83d', '#e0a91f']];
let groundPattern = null;

function renderLoop() {
  updateCamera();
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  drawBackground();
  drawDecorations();
  mapInfo.walls.forEach(w => {
    const p = worldToScreen(w.x, w.y);
    drawWorldWall(p.x, p.y, w.w, w.h, w.type);
  });

  ctx.fillStyle = '#ffd23a';
  latestState.bullets.forEach(b => {
    const p = worldToScreen(b.x, b.y);
    ctx.beginPath(); ctx.arc(p.x, p.y, 4, 0, Math.PI * 2); ctx.fill();
  });

  latestState.players.forEach(pl => {
    if (!pl.alive) return;
    const p = worldToScreen(pl.x, pl.y);
    // You vs opponents: every player gets a colored ring; you also get a white ring and a marker
    const mine = pl.id === selfId;
    ctx.save();
    ctx.globalAlpha = 0.28; ctx.fillStyle = mine ? '#ffffff' : (pl.color || '#ff5a47');
    ctx.beginPath(); ctx.ellipse(p.x, p.y + 20, 26, 11, 0, 0, Math.PI * 2); ctx.fill();
    ctx.globalAlpha = 1; ctx.lineWidth = mine ? 4 : 3; ctx.strokeStyle = mine ? '#fff6e0' : (pl.color || '#ff5a47');
    ctx.stroke();
    ctx.restore();
    drawCharacter(p.x, p.y, pl.angle, pl.characterType);
    if (mine) tri([[p.x - 7, p.y - 66], [p.x + 7, p.y - 66], [p.x, p.y - 56]], '#5ce1b9');

    ctx.font = 'bold 13px "Barlow Semi Condensed", sans-serif';
    ctx.textAlign = 'center';
    ctx.shadowColor = 'rgba(0,0,0,0.8)'; ctx.shadowBlur = 4;
    ctx.fillStyle = mine ? '#5ce1b9' : '#ffb3a8';
    ctx.fillText(pl.name, p.x, p.y - 44);
    ctx.shadowBlur = 0;

    const barW = 42;
    ctx.fillStyle = INK; ctx.fillRect(p.x - barW / 2 - 1, p.y - 39, barW + 2, 8);
    ctx.fillStyle = mine ? '#5ce1b9' : '#ff5a47';
    ctx.fillRect(p.x - barW / 2, p.y - 38, barW * Math.max(0, pl.health) / 100, 6);
  });

  drawMinimap();
  if ($('screen-game').classList.contains('active')) requestAnimationFrame(renderLoop);
}

function makeGround() {
  const t = document.createElement('canvas');
  t.width = t.height = 160;
  const g = t.getContext('2d');
  g.fillStyle = '#74b45e'; g.fillRect(0, 0, 160, 160);
  g.fillStyle = '#6dac58'; g.fillRect(0, 0, 80, 80); g.fillRect(80, 80, 80, 80);
  g.strokeStyle = '#5b9a48'; g.lineWidth = 3; g.lineCap = 'round';
  [[30, 40], [120, 30], [55, 125], [135, 135], [100, 75]].forEach(([x, y]) => {
    g.beginPath(); g.moveTo(x, y); g.lineTo(x - 3, y - 9); g.moveTo(x, y); g.lineTo(x + 4, y - 8); g.stroke();
  });
  return ctx.createPattern(t, 'repeat');
}

function drawBackground() {
  if (!groundPattern) groundPattern = makeGround();
  ctx.save();
  ctx.translate(-camX, -camY);
  ctx.fillStyle = groundPattern;
  ctx.fillRect(camX, camY, canvas.width, canvas.height);
  ctx.restore();

  // dark void outside the arena so the edge is obvious
  ctx.save();
  ctx.fillStyle = INK;
  ctx.beginPath();
  ctx.rect(0, 0, canvas.width, canvas.height);
  ctx.rect(-camX, -camY, mapInfo.width, mapInfo.height);
  ctx.fill('evenodd');
  ctx.restore();
}

function drawDecorations() {
  decorations.forEach(d => {
    const p = worldToScreen(d.x, d.y);
    if (p.x < -100 || p.x > canvas.width + 100 || p.y < -100 || p.y > canvas.height + 100) return;
    if (d.type === 'tree') drawTree(p.x, p.y);
    else if (d.type === 'bush') drawBush(p.x, p.y);
    else if (d.type === 'grassPatch') drawGrassPatch(p.x, p.y);
  });
}

function drawTree(x, y) {
  ctx.fillStyle = 'rgba(10,31,30,0.3)';
  ctx.beginPath(); ctx.ellipse(x + 6, y + 22, 30, 10, 0, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = '#7a4e2a'; ctx.fillRect(x - 6, y - 8, 12, 28);
  ctx.lineWidth = 3; ctx.strokeStyle = INK;
  [[0, -30, 28, '#2f8a4a'], [-15, -16, 19, '#3a9d57'], [15, -16, 19, '#3a9d57']].forEach(([dx, dy, r, c]) => {
    ctx.fillStyle = c; ctx.beginPath(); ctx.arc(x + dx, y + dy, r, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  });
  ctx.fillStyle = 'rgba(255,255,255,0.18)';
  ctx.beginPath(); ctx.arc(x - 8, y - 38, 8, 0, Math.PI * 2); ctx.fill();
}

function drawBush(x, y) {
  ctx.fillStyle = '#3f9d5a'; ctx.strokeStyle = INK; ctx.lineWidth = 3;
  [[-11, 0, 14], [11, 0, 14], [0, -8, 16]].forEach(([dx, dy, r]) => {
    ctx.beginPath(); ctx.arc(x + dx, y + dy, r, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  });
}

function drawGrassPatch(x, y) {
  ctx.fillStyle = 'rgba(40,110,60,0.22)';
  ctx.beginPath(); ctx.ellipse(x, y, 90, 60, 0, 0, Math.PI * 2); ctx.fill();
}

function drawWorldWall(x, y, w, h, type) {
  if (x > canvas.width || y > canvas.height || x + w < 0 || y + h < 0) return;
  ctx.lineJoin = 'round';

  if (type === 'border') {
    ctx.fillStyle = '#145250'; ctx.fillRect(x, y, w, h);
    ctx.fillStyle = '#1d6f6b'; ctx.fillRect(x + 6, y + 6, Math.max(w - 12, 0), Math.max(h - 12, 0));
    return;
  }

  ctx.fillStyle = 'rgba(10,31,30,0.35)';
  ctx.fillRect(x + 8, y + 10, w, h);

  if (type === 'building') {
    const i = Math.abs(Math.floor((x + camX) / 10 + (y + camY) / 10)) % ROOFS.length;
    const [light, dark] = ROOFS[i];
    ctx.fillStyle = dark; ctx.fillRect(x, y, w, h);
    ctx.fillStyle = light; ctx.fillRect(x + 6, y + 6, w - 12, h - 12);
    ctx.strokeStyle = dark; ctx.lineWidth = 4;
    ctx.beginPath();
    if (w >= h) { ctx.moveTo(x + 6, y + h / 2); ctx.lineTo(x + w - 6, y + h / 2); }
    else { ctx.moveTo(x + w / 2, y + 6); ctx.lineTo(x + w / 2, y + h - 6); }
    ctx.stroke();
    ctx.strokeStyle = INK; ctx.lineWidth = 4; ctx.strokeRect(x, y, w, h);
  } else if (type === 'crate') {
    ctx.fillStyle = '#e0a64a'; ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = '#a9741f'; ctx.lineWidth = Math.min(4, w / 8);
    ctx.beginPath();
    ctx.moveTo(x + 3, y + 3); ctx.lineTo(x + w - 3, y + h - 3);
    ctx.moveTo(x + w - 3, y + 3); ctx.lineTo(x + 3, y + h - 3);
    ctx.stroke();
    ctx.strokeStyle = INK; ctx.lineWidth = 3; ctx.strokeRect(x, y, w, h);
  }
}

// The minimap sits directly under the Exit button, and the HP bar sits directly
// under the minimap, so nothing overlaps at any screen size or orientation.
let lastHpTop = -1;
function drawMinimap() {
  const short = window.innerHeight < 500;
  const size = short ? 90 : (isTouch ? 100 : 130);
  const ex = $('btn-leave-match').getBoundingClientRect();
  const mx = ex.left, my = ex.bottom + 8;
  const s = size / mapInfo.width, mh = mapInfo.height * s;

  // HP bar follows the minimap instead of using a magic number
  const hpTop = Math.round(my + mh + 10);
  if (hpTop !== lastHpTop) { $('hud-top-left').style.top = hpTop + 'px'; lastHpTop = hpTop; }

  ctx.fillStyle = 'rgba(10,31,30,0.85)'; ctx.fillRect(mx, my, size, mh);
  ctx.strokeStyle = '#fff6e0'; ctx.lineWidth = 2; ctx.strokeRect(mx, my, size, mh);

  mapInfo.walls.forEach(w => {
    ctx.fillStyle = w.type === 'building' ? '#ff5a47' : w.type === 'crate' ? '#e0a64a' : '#1d6f6b';
    ctx.fillRect(mx + w.x * s, my + w.y * s, Math.max(w.w * s, 1), Math.max(w.h * s, 1));
  });

  const vid = (viewedPlayer() || {}).id;
  latestState.players.forEach(p => {
    if (!p.alive) return;
    const me = p.id === vid;
    ctx.fillStyle = me ? '#ffc83d' : '#5ce1b9';
    ctx.strokeStyle = INK; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.arc(mx + p.x * s, my + p.y * s, me ? 4 : 3, 0, Math.PI * 2);
    ctx.fill(); ctx.stroke();
  });

  ctx.strokeStyle = 'rgba(255,246,224,0.6)'; ctx.lineWidth = 1;
  ctx.strokeRect(mx + camX * s, my + camY * s, canvas.width * s, canvas.height * s);
}

/* ---------------------------------------------------------------------
   Characters
   --------------------------------------------------------------------- */
function ell(x, y, rx, ry, color, rot) {
  ctx.fillStyle = color; ctx.beginPath(); ctx.ellipse(x, y, rx, ry, rot || 0, 0, Math.PI * 2); ctx.fill();
}
function dot(x, y, r, color) {
  ctx.fillStyle = color; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
}
function tri(pts, color) {
  ctx.fillStyle = color; ctx.beginPath();
  ctx.moveTo(pts[0][0], pts[0][1]); ctx.lineTo(pts[1][0], pts[1][1]); ctx.lineTo(pts[2][0], pts[2][1]);
  ctx.closePath(); ctx.fill();
}
function aimLine(angle, color) {
  ctx.save(); ctx.rotate(angle);
  ctx.strokeStyle = color; ctx.lineWidth = 3;
  ctx.beginPath(); ctx.moveTo(14, 0); ctx.lineTo(34, 0); ctx.stroke();
  ctx.restore();
}

function drawCharacter(x, y, angle, type) {
  ctx.save();
  ctx.translate(x, y);
  ell(0, 20, 18, 7, 'rgba(0,0,0,0.25)');
  switch (type) {
    case 'bear': drawBeast(angle, { aim: 'rgba(255,80,80,.4)', body: '#7a5233', rx: 17, chest: '#c99b6f', face: '#c99b6f', nose: '#2a1a10', eye: '#000', arms: true, roundEars: true, feet: true }); break;
    case 'fox': drawBeast(angle, { aim: 'rgba(255,180,80,.4)', body: '#d96b27', rx: 16, chest: '#f5d6b3', face: '#f5d6b3', nose: '#24150f', eye: '#000', ear: [-10, -14, -8, -28, -1, -18], tail: true }); break;
    case 'wolf': drawBeast(angle, { aim: 'rgba(180,200,220,.45)', body: '#65717d', rx: 17, chest: '#c7d0d8', face: '#d7dde2', nose: '#111', eye: '#ffd23a', ear: [-10, -19, -12, -30, -3, -21] }); break;
    case 'horse': drawHorse(angle); break;
    default: drawPenguin(angle);
  }
  ctx.restore();
}

function drawPenguin(a) {
  aimLine(a, 'rgba(255,255,255,.4)');
  ell(0, 2, 16, 20, '#1c2530'); ell(0, 6, 10, 14, '#f4f6f8');
  const flap = Math.sin(a * 2);
  ell(-14, 4 + flap, 6, 12, '#1c2530', -0.3); ell(14, 4 - flap, 6, 12, '#1c2530', 0.3);
  dot(0, -14, 11, '#1c2530');
  ell(Math.cos(a) * 4, -14 + Math.sin(a) * 1.2, 7, 8, '#f4f6f8');
  ctx.save(); ctx.translate(0, -14); ctx.rotate(a);
  tri([[6, -2], [16, 0], [6, 2]], '#ffa733');
  ctx.restore();
  dot(-3, -17, 1.6, '#000'); dot(3, -17, 1.6, '#000');
  ell(-6, 20, 5, 3, '#ffa733'); ell(6, 20, 5, 3, '#ffa733');
}

// Shared body plan for bear / fox / wolf
function drawBeast(a, o) {
  aimLine(a, o.aim);
  if (o.tail) ell(-18, 10, 10, 6, o.body, -0.5);
  ell(0, 5, o.rx, 19, o.body); ell(0, 9, 9, 12, o.chest);
  if (o.arms) { ell(-15, 6, 6, 11, o.body, -0.2); ell(15, 6, 6, 11, o.body, 0.2); }
  if (o.ear) {
    const e = o.ear;
    tri([[e[0], e[1]], [e[2], e[3]], [e[4], e[5]]], o.body);
    tri([[-e[0], e[1]], [-e[2], e[3]], [-e[4], e[5]]], o.body);
  }
  if (o.roundEars) { [-8, 8].forEach(ex => { dot(ex, -20, 5, o.body); dot(ex, -20, 2.5, '#5c3d26'); }); }
  dot(0, -13, 12, o.body);
  const fx = Math.cos(a) * 5, fy = Math.sin(a) * 5;
  ell(fx, -9 + fy * 0.3, 7, 5, o.face);
  dot(fx * 1.6, -9 + fy * 0.5, 2, o.nose);
  dot(-4, -16, 1.7, o.eye); dot(4, -16, 1.7, o.eye);
  if (o.feet) { ell(-7, 21, 6, 4, '#5c3d26'); ell(7, 21, 6, 4, '#5c3d26'); }
}

function drawHorse(a) {
  aimLine(a, 'rgba(210,160,100,.45)');
  ell(0, 6, 18, 17, '#8b5a36'); ell(0, -10, 10, 17, '#8b5a36'); ell(0, -23, 10, 12, '#8b5a36');
  tri([[-7, -31], [-10, -41], [-2, -34]], '#8b5a36'); tri([[7, -31], [10, -41], [2, -34]], '#8b5a36');
  const fx = Math.cos(a) * 5, fy = Math.sin(a) * 5;
  ell(fx, -20 + fy * 0.3, 7, 5, '#b9784a');
  dot(fx * 1.5, -20 + fy * 0.5, 2, '#24150f');
  dot(-4, -26, 1.7, '#111'); dot(4, -26, 1.7, '#111');
  ell(-9, -11, 5, 16, '#3b2417');
  ctx.fillStyle = '#704526'; ctx.fillRect(-12, 15, 6, 13); ctx.fillRect(6, 15, 6, 13);
}