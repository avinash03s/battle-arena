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
  const title = popup.querySelector('.install-title');
  const text = document.getElementById('install-text');
  const installBtn = document.getElementById('install-btn');
  const laterBtn = document.getElementById('install-later');
  let deferredPrompt = null;
  let splashDone = !document.getElementById('splash');
  let installing = false;

  function maybeShow() {
    if (!deferredPrompt || !splashDone || installing) return;
    if (sessionStorage.getItem('installDismissed')) return;
    popup.hidden = false;
  }

  function showInstalling() {
    installing = true;
    title.textContent = 'Installing…';
    text.innerHTML = '<span class="spinner"></span> Adding Battle Arena to your phone. This takes a few seconds.';
    installBtn.style.display = 'none';
    laterBtn.style.display = 'none';
  }

  function showInstalled() {
    installing = false;
    title.textContent = 'Installed!';
    text.textContent = 'Battle Arena is ready. Tap "Open app" (or find it on your home screen). You can close this browser tab.';
    installBtn.style.display = '';
    installBtn.textContent = 'Open app';
    installBtn.onclick = () => {
      // Browsers cannot close a tab or launch an installed app from script.
      // Best effort: try to close; otherwise leave the user on a clear message.
      window.close();
      setTimeout(() => {
        text.textContent = 'Press Home, then tap the Battle Arena icon. You can close this tab.';
        installBtn.style.display = 'none';
      }, 300);
    };
    laterBtn.style.display = '';
    laterBtn.textContent = 'Close';
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
    showInstalling();
    deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    deferredPrompt = null;
    if (choice.outcome !== 'accepted') {
      installing = false;
      popup.hidden = true;
    }
    // If accepted, stay on "Installing…" until the appinstalled event fires
  });

  laterBtn.addEventListener('click', () => {
    sessionStorage.setItem('installDismissed', '1');
    popup.hidden = true;
  });

  window.addEventListener('appinstalled', () => {
    deferredPrompt = null;
    showInstalled();
  });
})();