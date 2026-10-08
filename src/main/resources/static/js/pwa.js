'use strict';
(function () {
  // Android only. Desktop and iOS are left untouched.
  if (!/android/i.test(navigator.userAgent)) return;

  const standalone =
    window.matchMedia('(display-mode: standalone)').matches ||
    window.matchMedia('(display-mode: fullscreen)').matches;

  // Opened as an installed app: lock landscape, no popup
  if (standalone) {
    const lockLandscape = () => {
      try { screen.orientation.lock('landscape').catch(() => {}); } catch (e) {}
    };
    lockLandscape();
    window.addEventListener('touchstart', lockLandscape, { once: true });
    return;
  }

  const popup = document.getElementById('install-popup');
  const text = document.getElementById('install-text');
  const installBtn = document.getElementById('install-btn');
  const laterBtn = document.getElementById('install-later');
  let deferredPrompt = null;
  let splashDone = !document.getElementById('splash');

  function maybeShow() {
    if (!deferredPrompt || !splashDone) return;
    if (sessionStorage.getItem('installDismissed')) return;
    popup.hidden = false;
  }

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    maybeShow();
  });

  window.addEventListener('splash-done', () => {
    splashDone = true;
    setTimeout(maybeShow, 600);
  });

  installBtn.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    deferredPrompt = null;
    if (choice.outcome === 'accepted') {
      text.textContent = 'Installed! Open Battle Arena from your home screen.';
      installBtn.style.display = 'none';
      laterBtn.textContent = 'OK';
    } else {
      popup.hidden = true;
    }
  });

  laterBtn.addEventListener('click', () => {
    sessionStorage.setItem('installDismissed', '1');
    popup.hidden = true;
  });

  window.addEventListener('appinstalled', () => { deferredPrompt = null; });
})();