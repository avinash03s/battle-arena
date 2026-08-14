const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
const ws = new WebSocket(wsProtocol + window.location.host + '/game');

const pendingQueue = [];
let wsOpen = false;

// FRONT LOADING SCREEN

function hideLoadingScreen() {
  const loader = document.getElementById('loading-screen');

  if (loader) {
    loader.classList.add('hidden');

    setTimeout(() => {
      loader.remove();
    }, 400);
  }
}

// Fake progress fill while WebSocket connects
const loaderBar = document.getElementById('loader-bar');

let loaderProgress = 0;

const loaderInterval = setInterval(() => {
  loaderProgress = Math.min(
    loaderProgress + Math.random() * 12,
    92
  );

  if (loaderBar) {
    loaderBar.style.width = loaderProgress + '%';
  }
});

ws.addEventListener('open', () => {

  console.log('WebSocket connected');

  wsOpen = true;

  // Send messages queued before WebSocket opened
  while (pendingQueue.length > 0) {
    ws.send(pendingQueue.shift());
  }

  // Finish loading screen
  clearInterval(loaderInterval);

  if (loaderBar) {
    loaderBar.style.width = '100%';
  }

  setTimeout(() => {
    hideLoadingScreen();
  }, 2000);
});

function send(type, data) {

  const payload = JSON.stringify({
    type,
    data: data || {}
  });

   console.log('SEND:', type, data);

  if (wsOpen) {
    ws.send(payload);
  } else {
    pendingQueue.push(payload);
     console.log('QUEUED:', type);
  }
}

// WEBSOCKET EVENTS

ws.addEventListener('message', (event) => {
 console.log('RECEIVED:', event.data);
  let msg;

  try {
    msg = JSON.parse(event.data);
  } catch (e) {
   console.error('Invalid server message:', event.data);
    return;
  }

  handleServerMessage(msg.type, msg.data);
});

ws.addEventListener('close', () => {

  wsOpen = false;

  console.warn('WebSocket connection closed.');
});

// SERVER MESSAGES

function handleServerMessage(type, data) {

  switch (type) {

    case 'joined':
      onJoined(data);
      break;

    case 'roomUpdate':
      onRoomUpdate(data);
      break;

    case 'countdown':
      onCountdown(data);
      break;

    case 'soloWait':
      onSoloWait(data);
      break;

    case 'gameStart':
      onGameStart(data);
      break;

    case 'state':
      onState(data);
      break;

    case 'playerEliminated':
      onPlayerEliminated(data);
      break;

    case 'playerLeft':
      onPlayerLeft(data);
      break;

    case 'matchResult':
      onMatchResult(data);
      break;

    default:
      break;
  }
}

// SCREEN NAVIGATION

function showScreen(id) {

  document
    .querySelectorAll('.screen')
    .forEach(screen => {
      screen.classList.remove('active');
    });

  const screen = document.getElementById(id);

  if (screen) {
    screen.classList.add('active');
  }
}

// Back buttons
document
  .querySelectorAll('[data-back]')
  .forEach(element => {

    element.addEventListener('click', () => {

      showScreen(element.dataset.back);

    });

  });

// MAIN MENU

document
  .getElementById('btn-play')
  .addEventListener('click', () => {

    showScreen('screen-room-size');

  });

document
  .getElementById('btn-howto')
  .addEventListener('click', () => {

    showScreen('screen-howto');

  });

document
  .getElementById('btn-settings')
  .addEventListener('click', () => {

    showScreen('screen-settings');

  });

document
  .getElementById('btn-about')
  .addEventListener('click', () => {

    showScreen('screen-about');

  });

// ROOM SIZE
let selectedRoomSize = 4;

document
  .querySelectorAll('.room-size-btn')
  .forEach(button => {

    button.addEventListener('click', () => {

      selectedRoomSize =
        parseInt(button.dataset.size, 10);

      /*
       * Previously:
       * room size -> name
       *
       * Now:
       * room size -> character
       */

      showScreen('screen-character');

    });

  });

// CHARACTER SELECTION
let selectedCharacter = 'penguin';

/*
 * Select character cards.
 *
 * Available:
 * penguin
 * bear
 * fox
 * wolf
 * horse
 *
 * Locked:
 * panda
 * lion
 * tiger
 * eagle
 * dragon
 */

document
  .querySelectorAll('.character-card')
  .forEach(card => {

    card.addEventListener('click', () => {
      if (card.dataset.available !== 'true') {
        return;
      }
      selectedCharacter =
        card.dataset.character;
      document
        .querySelectorAll('.character-card')
        .forEach(item => {

          item.classList.remove('selected');

        });
      card.classList.add('selected');

    });

  });

// Character Continue button
const characterContinueButton =
  document.getElementById('btn-character-continue');

if (characterContinueButton) {

  characterContinueButton.addEventListener(
    'click',
    () => {
      showScreen('screen-name');

    }
  );

}

// NAME / JOIN GAME
document
  .getElementById('btn-join')
  .addEventListener('click', joinGame);

document
  .getElementById('input-name')
  .addEventListener('keydown', (event) => {

    if (event.key === 'Enter') {
      joinGame();
    }

  });

// PLAY AGAIN
document
  .getElementById('btn-play-again')
  .addEventListener('click', () => {

    /*
     * Keep selected character.
     *
     * Example:
     * Player selected Horse.
     * Match ends.
     * PLAY AGAIN
     * Horse remains selected.
     */

    showScreen('screen-name');

  });

// LEAVE MATCH
const leaveMatchBtn =
  document.getElementById('btn-leave-match');

if (leaveMatchBtn) {

  leaveMatchBtn.addEventListener(
    'click',
    leaveMatch
  );

}

function leaveMatch() {

  send('leaveMatch');

  resetLocalMatchState();

  showScreen('screen-start');

}

function resetLocalMatchState() {

  selfId = null;

  gameStartTime = null;

  latestState = {
    players: [],
    bullets: []
  };

}

// PLAYER JOIN
let playerName = '';

function joinGame() {

  const input =
    document.getElementById('input-name');

  playerName =
    input.value.trim();

  if (!playerName) {

    playerName =
      'Player' +
      Math.floor(Math.random() * 1000);

  }

  /*
   * Go to waiting screen.
   */
  showScreen('screen-waiting');

  document
    .getElementById('waiting-title')
    .textContent =
      'FINDING PLAYERS...';

  /*
   * Character is now sent to Java backend.
   */
  send('joinGame', {

    name: playerName,

    roomSize: selectedRoomSize,

    characterType: selectedCharacter

  });

}

// GAME STATE
let selfId = null;

let mapInfo = {
  width: 1600,
  height: 1200,
  walls: []
};

let latestState = {
  players: [],
  bullets: []
};

let gameStartTime = null;

let decorations = [];

// JOINED
function onJoined(data) {

  selfId = data.selfId;

  mapInfo = data.map;

  decorations =
    data.map.decorations || [];

}


// ROOM UPDATE
function onRoomUpdate(data) {

  document
    .getElementById('player-count')
    .textContent =
      `Players: ${data.players.length}/${data.maxPlayers}`;

  const list =
    document.getElementById('waiting-list');

  list.innerHTML = '';

  data.players.forEach(player => {

    const li =
      document.createElement('li');

    li.textContent = player.name;

    list.appendChild(li);

  });

  if (data.players.length < data.minPlayers) {

    document
      .getElementById('waiting-title')
      .textContent =
        'WAITING FOR PLAYERS';

  }

}

// COUNTDOWN
function onCountdown(value) {

  showScreen('screen-countdown');

  document
    .getElementById('countdown-number')
    .textContent =
      value > 0 ? value : 'GO!';

}

// SOLO WAIT
function onSoloWait(data) {

  const secondsRemaining =
    data.secondsRemaining;

  document
    .getElementById('waiting-title')
    .textContent =
      `NO OPPONENTS FOUND — STARTING WITH BOTS IN ${secondsRemaining}s`;

}

// GAME START
function onGameStart(data) {

  gameStartTime =
    data.startTime;

  showScreen('screen-game');

  resizeCanvas();

  requestAnimationFrame(renderLoop);

}

// GAME STATE
function onState(data) {

  latestState = data;

  updateHUD();

}


// PLAYER ELIMINATED
const killFeedEl = document.getElementById('kill-feed');

function onPlayerEliminated(data) {

  const item = document.createElement('div');

  item.className ='kill-feed-item';

  item.textContent =`${data.name} was eliminated by ${data.by}`;

  killFeedEl.appendChild(item);

  setTimeout(() => {

    item.remove();

  }, 3000);

}

// PLAYER LEFT
function onPlayerLeft(data) {

  const item = document.createElement('div');

  item.className ='kill-feed-item';

  item.textContent =`${data.name} left the match`;

  killFeedEl.appendChild(item);

  setTimeout(() => {

    item.remove();

  }, 3000);

}

// MATCH RESULT
function onMatchResult(data) {

  const isWinner = data.winner && data.winner.name === playerName;

  const title = document.getElementById('result-title');

  const sub = document.getElementById('result-sub');

  if (isWinner) {

    title.textContent ='VICTORY!';
    title.style.color ='#ffd23a';
    sub.textContent =`Winner: ${data.winner.name}`;
  } else {
    title.textContent ='DEFEATED';

    title.style.color ='#ff4d4d';

    sub.textContent = data.winner ? `Winner: ${data.winner.name}` : 'No survivors';
  }

  const self =
    data.players.find(
      player => player.name === playerName
    );

  document
    .getElementById('result-kills')
    .textContent =
      `Kills: ${self ? self.kills : 0}`;

  const totalSeconds =
    Math.floor(data.elapsed / 1000);

  const mm =
    String(
      Math.floor(totalSeconds / 60)
    ).padStart(2, '0');

  const ss =
    String(
      totalSeconds % 60
    ).padStart(2, '0');

  document
    .getElementById('result-time')
    .textContent =
      `Survival Time: ${mm}:${ss}`;

  const sortedPlayers =
    [...data.players]
      .sort(
        (a, b) =>
          b.kills - a.kills
      );

  const listEl =
    document.getElementById('result-list');

  listEl.innerHTML = '';

  sortedPlayers.forEach((player, index) => {

    const row =
      document.createElement('div');

    row.innerHTML =
      `<span>${index + 1}. ${player.name}</span>` +
      `<span>${player.kills} Kills</span>`;

    listEl.appendChild(row);

  });

  showScreen('screen-result');

}


// CANVAS
const canvas =
  document.getElementById('game-canvas');

const ctx =
  canvas.getContext('2d');

function resizeCanvas() {

  canvas.width =
    window.innerWidth;

  canvas.height =
    window.innerHeight;

}

window.addEventListener(
  'resize',
  resizeCanvas
);

resizeCanvas();


// KEYBOARD
const keys = {
  up: false,
  down: false,
  left: false,
  right: false
};

window.addEventListener(
  'keydown',
  (event) => {

    switch (event.key.toLowerCase()) {

      case 'w':
      case 'arrowup':
        keys.up = true;
        break;

      case 's':
      case 'arrowdown':
        keys.down = true;
        break;

      case 'a':
      case 'arrowleft':
        keys.left = true;
        break;

      case 'd':
      case 'arrowright':
        keys.right = true;
        break;

      case 'r':
        send('reload');
        break;

    }

  }
);

window.addEventListener(
  'keyup',
  (event) => {

    switch (event.key.toLowerCase()) {

      case 'w':
      case 'arrowup':
        keys.up = false;
        break;

      case 's':
      case 'arrowdown':
        keys.down = false;
        break;

      case 'a':
      case 'arrowleft':
        keys.left = false;
        break;

      case 'd':
      case 'arrowright':
        keys.right = false;
        break;

    }

  }
);


// MOUSE AIM
let mouseX = 0;

let mouseY = 0;

let aimAngle = 0;

let mouseDown = false;

canvas.addEventListener(
  'mousemove',
  (event) => {

    mouseX = event.clientX;

    mouseY = event.clientY;

  }
);

canvas.addEventListener(
  'mousedown',
  () => {

    mouseDown = true;

  }
);

window.addEventListener(
  'mouseup',
  () => {

    mouseDown = false;

  }
);

// MOBILE RELOAD
document
  .getElementById('btn-reload-mobile')
  .addEventListener(
    'click',
    () => {

      send('reload');

    }
  );


// JOYSTICK
function setupJoystick(
  baseEl,
  knobEl,
  onMove,
  onEnd
) {

  let active = false;

  let baseRect = null;

  let touchId = null;

  function start(event) {

    event.preventDefault();

    const touch =
      event.changedTouches[0];

    touchId =
      touch.identifier;

    active = true;

    baseRect =
      baseEl.getBoundingClientRect();

  }

  function move(event) {

    if (!active) {
      return;
    }

    let touch = null;

    for (const currentTouch of event.changedTouches) {

      if (
        currentTouch.identifier ===
        touchId
      ) {

        touch = currentTouch;

        break;

      }

    }

    if (!touch) {
      return;
    }

    event.preventDefault();

    const cx =
      baseRect.left +
      baseRect.width / 2;

    const cy =
      baseRect.top +
      baseRect.height / 2;

    let dx =
      touch.clientX - cx;

    let dy =
      touch.clientY - cy;

    const maxDist =
      baseRect.width / 2;

    const dist =
      Math.min(
        Math.hypot(dx, dy),
        maxDist
      );

    const angle =
      Math.atan2(dy, dx);

    const kx =
      Math.cos(angle) * dist;

    const ky =
      Math.sin(angle) * dist;

    knobEl.style.transform =
      `translate(calc(-50% + ${kx}px), calc(-50% + ${ky}px))`;

    onMove(
      dx / maxDist,
      dy / maxDist,
      dist / maxDist,
      angle
    );

  }

  function end(event) {

    let found = false;

    for (const touch of event.changedTouches) {

      if (
        touch.identifier ===
        touchId
      ) {

        found = true;

        break;

      }

    }

    if (!found) {
      return;
    }

    active = false;

    touchId = null;

    knobEl.style.transform =
      'translate(-50%, -50%)';

    onEnd();

  }

  baseEl.addEventListener(
    'touchstart',
    start,
    { passive: false }
  );

  window.addEventListener(
    'touchmove',
    move,
    { passive: false }
  );

  window.addEventListener(
    'touchend',
    end,
    { passive: false }
  );

  window.addEventListener(
    'touchcancel',
    end,
    { passive: false }
  );

}

// Move joystick
setupJoystick(

  document.getElementById(
    'joystick-move'
  ),

  document.getElementById(
    'joystick-move-knob'
  ),

  (nx, ny) => {

    keys.up =
      ny < -0.3;

    keys.down =
      ny > 0.3;

    keys.left =
      nx < -0.3;

    keys.right =
      nx > 0.3;

  },

  () => {

    keys.up =
      keys.down =
      keys.left =
      keys.right =
      false;

  }

);

// Aim joystick
let touchAiming = false;

setupJoystick(

  document.getElementById(
    'joystick-aim'
  ),

  document.getElementById(
    'joystick-aim-knob'
  ),

  (nx, ny, strength, angle) => {

    aimAngle = angle;

    touchAiming =
      strength > 0.2;

  },

  () => {

    touchAiming = false;

  }

);


// GAME INPUT LOOP
setInterval(() => {

  if (!selfId) {
    return;
  }

  const isTouchDevice =
    window.matchMedia(
      '(hover: none) and (pointer: coarse)'
    ).matches;

  if (!isTouchDevice) {

    const selfPlayer =
      latestState.players.find(
        player =>
          player.id === selfId
      );

    if (selfPlayer) {

      const screenPos =
        worldToScreen(
          selfPlayer.x,
          selfPlayer.y
        );

      aimAngle =
        Math.atan2(
          mouseY - screenPos.y,
          mouseX - screenPos.x
        );

    }

  }

  send('input', {

    up: keys.up,

    down: keys.down,

    left: keys.left,

    right: keys.right,

    angle: aimAngle

  });

  if (mouseDown || touchAiming) {

    send('shoot');

  }

}, 1000 / 30);

// CAMERA
let camX = 0;

let camY = 0;

function updateCamera() {

  const selfPlayer =
    latestState.players.find(
      player =>
        player.id === selfId
    );

  if (selfPlayer) {

    camX =
      selfPlayer.x -
      canvas.width / 2;

    camY =
      selfPlayer.y -
      canvas.height / 2;

  }

}

function worldToScreen(x, y) {

  return {

    x: x - camX,

    y: y - camY

  };

}

// HUD
function updateHUD() {

  const selfPlayer =
    latestState.players.find(
      player =>
        player.id === selfId
    );

  if (selfPlayer) {

    const pct =
      Math.max(
        0,
        selfPlayer.health
      ) + '%';

    document
      .getElementById('hp-bar')
      .style.width = pct;

    document
      .getElementById('hp-text')
      .textContent =
        `${Math.max(0, selfPlayer.health)} / 100`;

    document
      .getElementById('ammo-text')
      .textContent =
        selfPlayer.reloading
          ? 'Reloading...'
          : `${selfPlayer.ammo} / ${selfPlayer.reserveAmmo}`;

  }

  const aliveCount =
    latestState.players.filter(
      player =>
        player.alive
    ).length;

  document
    .getElementById('alive-count')
    .textContent =
      `PLAYERS: ${aliveCount}`;

  const totalSeconds =
    Math.floor(
      (latestState.elapsed || 0) /
      1000
    );

  const mm =
    String(
      Math.floor(
        totalSeconds / 60
      )
    ).padStart(2, '0');

  const ss =
    String(
      totalSeconds % 60
    ).padStart(2, '0');

  document
    .getElementById('timer')
    .textContent =
      `${mm}:${ss}`;

  const sorted =
    [...latestState.players]
      .sort(
        (a, b) =>
          b.kills - a.kills
      );

  const board =
    document.getElementById(
      'leaderboard'
    );

  board.innerHTML =
    '<div style="font-weight:800;margin-bottom:4px;">LEADERBOARD</div>';

  sorted.forEach((player, index) => {

    const row =
      document.createElement('div');

    row.textContent =
      `${index + 1}. ${player.name} — ${player.kills} Kills${player.alive ? '' : ' 💀'}`;

    board.appendChild(row);

  });

}


// RENDER LOOP
function renderLoop() {

  updateCamera();

  ctx.clearRect(
    0,
    0,
    canvas.width,
    canvas.height
  );

  drawBackground();

  drawDecorations();

  mapInfo.walls.forEach(wall => {

    if (
      wall.type !== 'building' &&
      wall.type !== 'crate' &&
      wall.type !== 'border'
    ) {
      return;
    }

    const pos =
      worldToScreen(
        wall.x,
        wall.y
      );

    drawWorldWall(
      pos.x,
      pos.y,
      wall.w,
      wall.h,
      wall.type
    );

  });

  // Bullets
  ctx.fillStyle =
    '#ffd23a';

  latestState.bullets.forEach(bullet => {

    const pos =
      worldToScreen(
        bullet.x,
        bullet.y
      );

    ctx.beginPath();

    ctx.arc(
      pos.x,
      pos.y,
      4,
      0,
      Math.PI * 2
    );

    ctx.fill();

  });

  // Players
  latestState.players.forEach(player => {

    if (!player.alive) {
      return;
    }

    const pos =
      worldToScreen(
        player.x,
        player.y
      );

    drawCharacter(
      pos.x,
      pos.y,
      player.angle,
      player.characterType,
      player.health
    );

    // Name
    ctx.fillStyle =
      '#fff';

    ctx.font =
      'bold 13px sans-serif';

    ctx.textAlign =
      'center';

    ctx.shadowColor =
      'rgba(0,0,0,0.8)';

    ctx.shadowBlur =
      4;

    ctx.fillText(
      player.name,
      pos.x,
      pos.y - 42
    );

    ctx.shadowBlur =
      0;

    // Health bar
    const barW = 40;

    ctx.fillStyle =
      '#2a1010';

    ctx.fillRect(
      pos.x - barW / 2,
      pos.y - 36,
      barW,
      5
    );

    ctx.fillStyle =
      '#3aff8a';

    ctx.fillRect(
      pos.x - barW / 2,
      pos.y - 36,
      barW *
      (
        Math.max(
          0,
          player.health
        ) / 100
      ),
      5
    );

  });

  drawMinimap();

  if (
    document
      .getElementById('screen-game')
      .classList
      .contains('active')
  ) {

    requestAnimationFrame(
      renderLoop
    );

  }

}


// BACKGROUND
function drawBackground() {

  ctx.fillStyle =
    '#4a8c3f';

  ctx.fillRect(
    0,
    0,
    canvas.width,
    canvas.height
  );

  const tile = 80;

  const startCol =
    Math.floor(
      camX / tile
    );

  const startRow =
    Math.floor(
      camY / tile
    );

  const cols =
    Math.ceil(
      canvas.width / tile
    ) + 2;

  const rows =
    Math.ceil(
      canvas.height / tile
    ) + 2;

  for (
    let r = 0;
    r < rows;
    r++
  ) {

    for (
      let c = 0;
      c < cols;
      c++
    ) {

      const worldCol =
        startCol + c;

      const worldRow =
        startRow + r;

      const sx =
        worldCol * tile -
        camX;

      const sy =
        worldRow * tile -
        camY;

      if (
        (worldCol + worldRow) % 2 === 0
      ) {

        ctx.fillStyle =
          'rgba(0,0,0,0.06)';

        ctx.fillRect(
          sx,
          sy,
          tile,
          tile
        );

      } else {

        ctx.fillStyle =
          'rgba(255,255,255,0.03)';

        ctx.fillRect(
          sx,
          sy,
          tile,
          tile
        );

      }

    }

  }

  ctx.strokeStyle =
    'rgba(30, 80, 25, 0.35)';

  ctx.lineWidth = 2;

  for (
    let r = 0;
    r < rows;
    r++
  ) {

    for (
      let c = 0;
      c < cols;
      c++
    ) {

      const worldCol =
        startCol + c;

      const worldRow =
        startRow + r;

      const seed =
        (
          worldCol * 31 +
          worldRow * 17
        ) % 7;

      if (seed === 0) {

        const sx =
          worldCol * tile -
          camX +
          20;

        const sy =
          worldRow * tile -
          camY +
          20;

        for (
          let i = 0;
          i < 3;
          i++
        ) {

          const bx =
            sx + i * 10;

          ctx.beginPath();

          ctx.moveTo(
            bx,
            sy + 10
          );

          ctx.lineTo(
            bx + 2,
            sy
          );

          ctx.stroke();

        }

      }

    }

  }

}


// DECORATIONS
function drawDecorations() {

  decorations.forEach(decoration => {

    const pos =
      worldToScreen(
        decoration.x,
        decoration.y
      );

    if (
      pos.x < -80 ||
      pos.x > canvas.width + 80 ||
      pos.y < -80 ||
      pos.y > canvas.height + 80
    ) {
      return;
    }

    if (
      decoration.type === 'tree'
    ) {

      drawTree(
        pos.x,
        pos.y
      );

    } else if (
      decoration.type === 'bush'
    ) {

      drawBush(
        pos.x,
        pos.y
      );

    } else if (
      decoration.type === 'grassPatch'
    ) {

      drawGrassPatch(
        pos.x,
        pos.y
      );

    }

  });

}


// TREE
function drawTree(x, y) {

  ctx.fillStyle =
    '#6b4423';

  ctx.fillRect(
    x - 6,
    y - 10,
    12,
    30
  );

  ctx.fillStyle =
    '#2d6a3e';

  ctx.beginPath();

  ctx.arc(
    x,
    y - 30,
    26,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#347a48';

  ctx.beginPath();

  ctx.arc(
    x - 14,
    y - 18,
    18,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.beginPath();

  ctx.arc(
    x + 14,
    y - 18,
    18,
    0,
    Math.PI * 2
  );

  ctx.fill();

}


// BUSH
function drawBush(x, y) {

  ctx.fillStyle =
    '#3a8a52';

  ctx.beginPath();

  ctx.arc(
    x - 10,
    y,
    14,
    0,
    Math.PI * 2
  );

  ctx.arc(
    x + 10,
    y,
    14,
    0,
    Math.PI * 2
  );

  ctx.arc(
    x,
    y - 8,
    16,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.strokeStyle =
    'rgba(0,0,0,0.15)';

  ctx.lineWidth = 2;

  ctx.stroke();

}


// GRASS PATCH
function drawGrassPatch(x, y) {

  ctx.fillStyle =
    'rgba(58, 138, 82, 0.35)';

  ctx.beginPath();

  ctx.ellipse(
    x,
    y,
    90,
    60,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

}


// CHARACTER
function drawCharacter(
  x,
  y,
  angle,
  characterType,
  health
) {

  ctx.save();

  ctx.translate(
    x,
    y
  );

  // Shadow
  ctx.fillStyle =
    'rgba(0,0,0,0.25)';

  ctx.beginPath();

  ctx.ellipse(
    0,
    20,
    18,
    7,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  /*
   * Select character renderer.
   *
   * Backend sends:
   * penguin
   * bear
   * fox
   * wolf
   * horse
   */

  switch (characterType) {

    case 'bear':
      drawBear(angle);
      break;

    case 'fox':
      drawFox(angle);
      break;

    case 'wolf':
      drawWolf(angle);
      break;

    case 'horse':
      drawHorse(angle);
      break;

    case 'penguin':
    default:
      drawPenguin(angle);
      break;

  }

  ctx.restore();

}


// PENGUIN
function drawPenguin(angle) {

  ctx.save();

  ctx.rotate(angle);

  ctx.strokeStyle =
    'rgba(255,255,255,0.4)';

  ctx.lineWidth = 3;

  ctx.beginPath();

  ctx.moveTo(
    14,
    0
  );

  ctx.lineTo(
    34,
    0
  );

  ctx.stroke();

  ctx.restore();

  ctx.fillStyle =
    '#1c2530';

  ctx.beginPath();

  ctx.ellipse(
    0,
    2,
    16,
    20,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#f4f6f8';

  ctx.beginPath();

  ctx.ellipse(
    0,
    6,
    10,
    14,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  const flap =
    Math.sin(angle * 2) * 0.1;

  ctx.fillStyle =
    '#1c2530';

  ctx.beginPath();

  ctx.ellipse(
    -14,
    4 + flap * 10,
    6,
    12,
    -0.3,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.beginPath();

  ctx.ellipse(
    14,
    4 - flap * 10,
    6,
    12,
    0.3,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#1c2530';

  ctx.beginPath();

  ctx.arc(
    0,
    -14,
    11,
    0,
    Math.PI * 2
  );

  ctx.fill();

  const fx =
    Math.cos(angle) * 4;

  const fy =
    Math.sin(angle) * 4;

  ctx.fillStyle =
    '#f4f6f8';

  ctx.beginPath();

  ctx.ellipse(
    fx,
    -14 + fy * 0.3,
    7,
    8,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#ffa733';

  ctx.save();

  ctx.translate(
    0,
    -14
  );

  ctx.rotate(angle);

  ctx.beginPath();

  ctx.moveTo(
    6,
    -2
  );

  ctx.lineTo(
    16,
    0
  );

  ctx.lineTo(
    6,
    2
  );

  ctx.closePath();

  ctx.fill();

  ctx.restore();

  ctx.fillStyle =
    '#000';

  ctx.beginPath();

  ctx.arc(
    -3,
    -17,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.arc(
    3,
    -17,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#ffa733';

  ctx.beginPath();

  ctx.ellipse(
    -6,
    20,
    5,
    3,
    0,
    0,
    Math.PI * 2
  );

  ctx.ellipse(
    6,
    20,
    5,
    3,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

}


// BEAR
function drawBear(angle) {

  ctx.save();

  ctx.rotate(angle);

  ctx.strokeStyle =
    'rgba(255,80,80,0.4)';

  ctx.lineWidth = 3;

  ctx.beginPath();

  ctx.moveTo(
    14,
    0
  );

  ctx.lineTo(
    34,
    0
  );

  ctx.stroke();

  ctx.restore();

  ctx.fillStyle =
    '#7a5233';

  ctx.beginPath();

  ctx.ellipse(
    0,
    4,
    17,
    19,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#c99b6f';

  ctx.beginPath();

  ctx.ellipse(
    0,
    8,
    9,
    12,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#7a5233';

  ctx.beginPath();

  ctx.ellipse(
    -15,
    6,
    6,
    11,
    -0.2,
    0,
    Math.PI * 2
  );

  ctx.ellipse(
    15,
    6,
    6,
    11,
    0.2,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#7a5233';

  ctx.beginPath();

  ctx.arc(
    -8,
    -20,
    5,
    0,
    Math.PI * 2
  );

  ctx.arc(
    8,
    -20,
    5,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#5c3d26';

  ctx.beginPath();

  ctx.arc(
    -8,
    -20,
    2.5,
    0,
    Math.PI * 2
  );

  ctx.arc(
    8,
    -20,
    2.5,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#7a5233';

  ctx.beginPath();

  ctx.arc(
    0,
    -13,
    12,
    0,
    Math.PI * 2
  );

  ctx.fill();

  const fx =
    Math.cos(angle) * 5;

  const fy =
    Math.sin(angle) * 5;

  ctx.fillStyle =
    '#c99b6f';

  ctx.beginPath();

  ctx.ellipse(
    fx,
    -9 + fy * 0.3,
    6,
    5,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#2a1a10';

  ctx.beginPath();

  ctx.arc(
    fx * 1.6,
    -9 + fy * 0.5,
    2,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#000';

  ctx.beginPath();

  ctx.arc(
    -4,
    -16,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.arc(
    4,
    -16,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.fill();

  ctx.fillStyle =
    '#5c3d26';

  ctx.beginPath();

  ctx.ellipse(
    -7,
    21,
    6,
    4,
    0,
    0,
    Math.PI * 2
  );

  ctx.ellipse(
    7,
    21,
    6,
    4,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

}


// FOX
function drawFox(angle) {

  ctx.save();

  ctx.rotate(angle);

  // Weapon / aim line
  ctx.strokeStyle =
    'rgba(255,180,80,0.4)';

  ctx.lineWidth = 3;

  ctx.beginPath();

  ctx.moveTo(
    14,
    0
  );

  ctx.lineTo(
    34,
    0
  );

  ctx.stroke();

  ctx.restore();

  // Body
  ctx.fillStyle =
    '#d96b27';

  ctx.beginPath();

  ctx.ellipse(
    0,
    5,
    16,
    19,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Chest
  ctx.fillStyle =
    '#f5d6b3';

  ctx.beginPath();

  ctx.ellipse(
    0,
    9,
    9,
    12,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Ears
  ctx.fillStyle =
    '#d96b27';

  ctx.beginPath();

  ctx.moveTo(
    -10,
    -14
  );

  ctx.lineTo(
    -8,
    -28
  );

  ctx.lineTo(
    -1,
    -18
  );

  ctx.closePath();

  ctx.fill();

  ctx.beginPath();

  ctx.moveTo(
    10,
    -14
  );

  ctx.lineTo(
    8,
    -28
  );

  ctx.lineTo(
    1,
    -18
  );

  ctx.closePath();

  ctx.fill();

  // Head
  ctx.beginPath();

  ctx.arc(
    0,
    -13,
    12,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Face
  const fx =
    Math.cos(angle) * 5;

  const fy =
    Math.sin(angle) * 5;

  ctx.fillStyle =
    '#f5d6b3';

  ctx.beginPath();

  ctx.ellipse(
    fx,
    -9 + fy * 0.3,
    7,
    5,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Nose
  ctx.fillStyle =
    '#24150f';

  ctx.beginPath();

  ctx.arc(
    fx * 1.6,
    -9 + fy * 0.5,
    2,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Eyes
  ctx.fillStyle =
    '#000';

  ctx.beginPath();

  ctx.arc(
    -4,
    -16,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.arc(
    4,
    -16,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Tail
  ctx.fillStyle =
    '#d96b27';

  ctx.beginPath();

  ctx.ellipse(
    -18,
    10,
    10,
    6,
    -0.5,
    0,
    Math.PI * 2
  );

  ctx.fill();

}


// WOLF
function drawWolf(angle) {

  ctx.save();

  ctx.rotate(angle);

  ctx.strokeStyle =
    'rgba(180,200,220,0.45)';

  ctx.lineWidth = 3;

  ctx.beginPath();

  ctx.moveTo(
    14,
    0
  );

  ctx.lineTo(
    34,
    0
  );

  ctx.stroke();

  ctx.restore();

  // Body
  ctx.fillStyle =
    '#65717d';

  ctx.beginPath();

  ctx.ellipse(
    0,
    5,
    17,
    19,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Chest
  ctx.fillStyle =
    '#c7d0d8';

  ctx.beginPath();

  ctx.ellipse(
    0,
    9,
    9,
    12,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Head
  ctx.fillStyle =
    '#65717d';

  ctx.beginPath();

  ctx.arc(
    0,
    -13,
    12,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Ears
  ctx.beginPath();

  ctx.moveTo(
    -10,
    -19
  );

  ctx.lineTo(
    -12,
    -30
  );

  ctx.lineTo(
    -3,
    -21
  );

  ctx.closePath();

  ctx.fill();

  ctx.beginPath();

  ctx.moveTo(
    10,
    -19
  );

  ctx.lineTo(
    12,
    -30
  );

  ctx.lineTo(
    3,
    -21
  );

  ctx.closePath();

  ctx.fill();

  // Muzzle
  const fx =
    Math.cos(angle) * 5;

  const fy =
    Math.sin(angle) * 5;

  ctx.fillStyle =
    '#d7dde2';

  ctx.beginPath();

  ctx.ellipse(
    fx,
    -9 + fy * 0.3,
    7,
    5,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Nose
  ctx.fillStyle =
    '#111';

  ctx.beginPath();

  ctx.arc(
    fx * 1.6,
    -9 + fy * 0.5,
    2,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Eyes
  ctx.fillStyle =
    '#ffd23a';

  ctx.beginPath();

  ctx.arc(
    -4,
    -16,
    1.8,
    0,
    Math.PI * 2
  );

  ctx.arc(
    4,
    -16,
    1.8,
    0,
    Math.PI * 2
  );

  ctx.fill();

}

// HORSE
function drawHorse(angle) {

  ctx.save();

  ctx.rotate(angle);

  ctx.strokeStyle =
    'rgba(210,160,100,0.45)';

  ctx.lineWidth = 3;

  ctx.beginPath();

  ctx.moveTo(
    14,
    0
  );

  ctx.lineTo(
    34,
    0
  );

  ctx.stroke();

  ctx.restore();

  // Body
  ctx.fillStyle =
    '#8b5a36';

  ctx.beginPath();

  ctx.ellipse(
    0,
    6,
    18,
    17,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Neck
  ctx.fillStyle =
    '#8b5a36';

  ctx.beginPath();

  ctx.ellipse(
    0,
    -10,
    10,
    17,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Head
  ctx.beginPath();

  ctx.ellipse(
    0,
    -23,
    10,
    12,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Ear
  ctx.beginPath();

  ctx.moveTo(
    -7,
    -31
  );

  ctx.lineTo(
    -10,
    -41
  );

  ctx.lineTo(
    -2,
    -34
  );

  ctx.closePath();

  ctx.fill();

  ctx.beginPath();

  ctx.moveTo(
    7,
    -31
  );

  ctx.lineTo(
    10,
    -41
  );

  ctx.lineTo(
    2,
    -34
  );

  ctx.closePath();

  ctx.fill();

  // Muzzle
  const fx =
    Math.cos(angle) * 5;

  const fy =
    Math.sin(angle) * 5;

  ctx.fillStyle =
    '#b9784a';

  ctx.beginPath();

  ctx.ellipse(
    fx,
    -20 + fy * 0.3,
    7,
    5,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Nose
  ctx.fillStyle =
    '#24150f';

  ctx.beginPath();

  ctx.arc(
    fx * 1.5,
    -20 + fy * 0.5,
    2,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Eyes
  ctx.fillStyle =
    '#111';

  ctx.beginPath();

  ctx.arc(
    -4,
    -26,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.arc(
    4,
    -26,
    1.6,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Mane
  ctx.fillStyle =
    '#3b2417';

  ctx.beginPath();

  ctx.ellipse(
    -9,
    -11,
    5,
    16,
    0,
    0,
    Math.PI * 2
  );

  ctx.fill();

  // Legs
  ctx.fillStyle =
    '#704526';

  ctx.fillRect(
    -12,
    15,
    6,
    13
  );

  ctx.fillRect(
    6,
    15,
    6,
    13
  );

}

// WORLD WALLS
function drawWorldWall(
  x,
  y,
  w,
  h,
  type
) {

  if (type === 'building') {

    ctx.fillStyle =
      '#8a7862';

    ctx.fillRect(
      x,
      y,
      w,
      h
    );

    ctx.fillStyle =
      '#5c4d3c';

    ctx.fillRect(
      x,
      y,
      w,
      14
    );

    ctx.fillStyle =
      'rgba(255, 230, 150, 0.6)';

    const winSize = 12;

    for (
      let wx = x + 20;
      wx < x + w - 20;
      wx += 45
    ) {

      for (
        let wy = y + 30;
        wy < y + h - 20;
        wy += 45
      ) {

        ctx.fillRect(
          wx,
          wy,
          winSize,
          winSize
        );

      }

    }

    ctx.strokeStyle =
      '#3d3226';

    ctx.lineWidth = 2;

    ctx.strokeRect(
      x,
      y,
      w,
      h
    );

  } else if (type === 'crate') {

    ctx.fillStyle =
      '#c98a3f';

    ctx.fillRect(
      x,
      y,
      w,
      h
    );

    ctx.strokeStyle =
      '#7a5220';

    ctx.lineWidth = 3;

    ctx.strokeRect(
      x,
      y,
      w,
      h
    );

    ctx.beginPath();

    ctx.moveTo(
      x,
      y
    );

    ctx.lineTo(
      x + w,
      y + h
    );

    ctx.moveTo(
      x + w,
      y
    );

    ctx.lineTo(
      x,
      y + h
    );

    ctx.stroke();

  } else if (type === 'border') {

    ctx.fillStyle =
      '#3a4150';

    ctx.fillRect(
      x,
      y,
      w,
      h
    );

  }

}


// MINIMAP
function drawMinimap() {

  const isMobile =
    window.matchMedia(
      '(hover: none) and (pointer: coarse)'
    ).matches;

  const mmSize =
    isMobile ? 100 : 120;

  const mmX = 16;

  const mmY =
    isMobile ? 55 : 70;

  const scale =
    mmSize / mapInfo.width;

  const mmHeight =
    mapInfo.height * scale;

  ctx.fillStyle =
    'rgba(43, 32, 22, 0.85)';

  ctx.fillRect(
    mmX,
    mmY,
    mmSize,
    mmHeight
  );

  ctx.strokeStyle =
    'rgba(240, 223, 192, 0.4)';

  ctx.lineWidth = 2;

  ctx.strokeRect(
    mmX,
    mmY,
    mmSize,
    mmHeight
  );

  mapInfo.walls.forEach(wall => {

    if (
      wall.type !== 'building' &&
      wall.type !== 'crate' &&
      wall.type !== 'border'
    ) {
      return;
    }

    const wx =
      mmX +
      wall.x * scale;

    const wy =
      mmY +
      wall.y * scale;

    const ww =
      wall.w * scale;

    const wh =
      wall.h * scale;

    if (
      wall.type === 'building'
    ) {

      ctx.fillStyle =
        '#8a7862';

    } else if (
      wall.type === 'crate'
    ) {

      ctx.fillStyle =
        '#c98a3f';

    } else {

      ctx.fillStyle =
        '#3a4150';

    }

    ctx.fillRect(
      wx,
      wy,
      Math.max(ww, 1),
      Math.max(wh, 1)
    );

  });

  latestState.players.forEach(player => {

    if (!player.alive) {
      return;
    }

    const px =
      mmX +
      player.x * scale;

    const py =
      mmY +
      player.y * scale;

    ctx.fillStyle =
      player.id === selfId
        ? '#3aa0ff'
        : '#c9722f';

    ctx.beginPath();

    ctx.arc(
      px,
      py,
      player.id === selfId ? 4 : 3,
      0,
      Math.PI * 2
    );

    ctx.fill();

    ctx.strokeStyle =
      '#fff';

    ctx.lineWidth = 1;

    ctx.stroke();

  });

  ctx.strokeStyle =
    'rgba(255,255,255,0.5)';

  ctx.lineWidth = 1;

  ctx.strokeRect(

    mmX +
      camX * scale,

    mmY +
      camY * scale,

    canvas.width * scale,

    canvas.height * scale

  );

}
