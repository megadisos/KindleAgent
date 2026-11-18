/*
 * Jarvis face: two glowing eyes and a mouth on a canvas.
 * Public API (called from Android): Face.setMode(m), Face.setLevel(x), Face.setTalking(bool),
 * Face.pulse(), Face.setStatus(text), Face.openSettings(), Face.configResult(ok, msg)
 */
(function () {
  'use strict';

  var COLOR = '#3fd8ff';
  var canvas = document.getElementById('face');
  var ctx = canvas.getContext('2d');
  var statusEl = document.getElementById('status');
  var W = 0, H = 0, dpr = 1;

  var state = {
    talking: false,
    mode: 'idle',        // idle | listening | thinking (talking is separate)
    level: 0,            // mic level while listening, 0..1
    wide: 0,             // eased 0..1, eyes widen while listening
    mouthOpen: 0,        // 0..1, eased toward mouthTarget
    mouthTarget: 0,
    nextSyllable: 0,     // time of next random mouth shape while talking
    blink: 0,            // 0 = open, 1 = closed
    nextBlink: performance.now() + 2500,
    blinkStart: -1,
    look: { x: 0, y: 0 },
    lookTarget: { x: 0, y: 0 },
    nextLook: performance.now() + 3000
  };

  function resize() {
    dpr = window.devicePixelRatio || 1;
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function roundRect(x, y, w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function update(now) {
    // Blinking: a quick close/open every few seconds.
    if (state.blinkStart < 0 && now > state.nextBlink) {
      state.blinkStart = now;
    }
    if (state.blinkStart >= 0) {
      var t = (now - state.blinkStart) / 180;
      state.blink = t < 1 ? t : Math.max(0, 2 - t);
      if (t >= 2) {
        state.blinkStart = -1;
        state.blink = 0;
        state.nextBlink = now + 2000 + Math.random() * 4000;
      }
    }

    // Eyes wander a little.
    if (now > state.nextLook) {
      state.lookTarget.x = (Math.random() - 0.5) * 0.6;
      state.lookTarget.y = (Math.random() - 0.5) * 0.4;
      state.nextLook = now + 1500 + Math.random() * 3500;
    }
    if (state.mode === 'thinking') {
      state.lookTarget.x = 0.5;
      state.lookTarget.y = -0.8;
    } else if (state.mode === 'listening') {
      state.lookTarget.x = 0;
      state.lookTarget.y = 0;
    }
    state.wide += ((state.mode === 'listening' ? 1 : 0) - state.wide) * 0.15;
    state.look.x += (state.lookTarget.x - state.look.x) * 0.06;
    state.look.y += (state.lookTarget.y - state.look.y) * 0.06;

    // Mouth: while talking, jump between syllable shapes.
    if (state.talking && now > state.nextSyllable) {
      state.mouthTarget = 0.25 + Math.random() * 0.75;
      state.nextSyllable = now + 70 + Math.random() * 110;
    } else if (!state.talking) {
      state.mouthTarget = 0;
    }
    state.mouthOpen += (state.mouthTarget - state.mouthOpen) * 0.35;
  }

  function draw(now) {
    ctx.clearRect(0, 0, W, H);
    var s = Math.min(W, H);
    var cx = W / 2;
    var cy = H * 0.45;

    ctx.fillStyle = COLOR;
    ctx.strokeStyle = COLOR;
    ctx.shadowColor = COLOR;
    ctx.shadowBlur = s * 0.04;

    // Eyes
    var eyeW = s * 0.16 * (1 + state.wide * 0.12);
    var eyeH = s * 0.22 * (1 + state.wide * 0.12) * (1 - state.blink * 0.92);
    var eyeGap = s * 0.2;
    var eyeY = cy - s * 0.12 + state.look.y * s * 0.03;
    var dx = state.look.x * s * 0.04;
    [-1, 1].forEach(function (side) {
      var ex = cx + side * eyeGap + dx;
      roundRect(ex - eyeW / 2, eyeY - eyeH / 2, eyeW, eyeH, eyeW * 0.45);
      ctx.fill();
    });

    // Mouth: a smile line when quiet, an opening shape when talking.
    var mouthW = s * 0.34;
    var mouthY = cy + s * 0.2;
    var open = state.mouthOpen;
    ctx.lineWidth = s * 0.022;
    ctx.lineCap = 'round';
    if (state.mode === 'listening' && open < 0.04) {
      var r = s * (0.03 + state.level * 0.05);
      ctx.beginPath();
      ctx.arc(cx, mouthY + s * 0.02, r, 0, Math.PI * 2);
      ctx.stroke();
    } else if (state.mode === 'thinking' && open < 0.04) {
      for (var i = 0; i < 3; i++) {
        var phase = (now / 400 + i * 0.33) % 1;
        var dotR = s * (0.015 + 0.01 * Math.sin(phase * Math.PI));
        ctx.beginPath();
        ctx.arc(cx + (i - 1) * s * 0.07, mouthY + s * 0.02, dotR, 0, Math.PI * 2);
        ctx.fill();
      }
    } else if (open < 0.04) {
      ctx.beginPath();
      ctx.moveTo(cx - mouthW / 2, mouthY);
      ctx.quadraticCurveTo(cx, mouthY + s * 0.07, cx + mouthW / 2, mouthY);
      ctx.stroke();
    } else {
      var h = s * 0.03 + open * s * 0.16;
      var w = mouthW * (0.75 + open * 0.2);
      roundRect(cx - w / 2, mouthY - s * 0.02, w, h, Math.min(h, w) * 0.5);
      ctx.fill();
    }
  }

  function frame(now) {
    update(now);
    draw(now);
    requestAnimationFrame(frame);
  }

  function setStatus(text) {
    statusEl.textContent = text || '';
    statusEl.style.opacity = text ? 0.55 : 0;
  }

  // Fallback for previewing in a normal browser: use the Web Speech API.
  function speak(text) {
    if (window.Android && window.Android.speak) {
      if (!window.Android.speak(text)) setStatus('La voz todavía está cargando…');
      return;
    }
    if (!('speechSynthesis' in window)) {
      // No voice at all: just animate the mouth for a few seconds.
      Face.setTalking(true);
      setTimeout(function () { Face.setTalking(false); }, 3000);
      return;
    }
    var u = new SpeechSynthesisUtterance(text);
    u.lang = 'es-ES';
    u.onstart = function () { Face.setTalking(true); };
    u.onend = u.onerror = function () { Face.setTalking(false); };
    u.onboundary = function () { Face.pulse(); };
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }

  var GREETINGS = [
    'Hola Jorge. Soy Jarvis. Así me veo cuando hablo.',
    'Todos los sistemas en línea. Esperando instrucciones.'
  ];
  var greetingIndex = 0;
  var onTablet = !!(window.Android && window.Android.listen);

  function onTap() {
    if (state.talking) {
      if (window.Android && window.Android.stop) window.Android.stop();
      else if ('speechSynthesis' in window) speechSynthesis.cancel();
      Face.setTalking(false);
      return;
    }
    if (!onTablet) {
      // Preview in a normal browser: just show the voice.
      setStatus('');
      speak(GREETINGS[greetingIndex++ % GREETINGS.length]);
      return;
    }
    if (state.mode === 'listening') window.Android.stopListening();
    else if (state.mode === 'idle') window.Android.listen();
  }

  // Tap to talk; press and hold (or the gear) for settings.
  var pressTimer = null, longPressed = false;
  canvas.addEventListener('pointerdown', function () {
    longPressed = false;
    pressTimer = setTimeout(function () { longPressed = true; openSettings(); }, 900);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) {
    canvas.addEventListener(ev, function () { clearTimeout(pressTimer); });
  });
  canvas.addEventListener('click', function () { if (!longPressed) onTap(); });

  // ---- Settings -----------------------------------------------------------
  var $ = function (id) { return document.getElementById(id); };
  var settingsEl = $('settings');
  var hostEl = $('host'), portEl = $('port'), codeEl = $('code');
  var modelEl = $('model'), editEl = $('edit'), shellEl = $('shell'), cameraEl = $('camera');
  var pitchEl = $('pitch'), rateEl = $('rate');
  var msgEl = $('settings-msg'), saveEl = $('save');
  var DEFAULT_PORT = '8737';

  function readConfig() {
    try { return JSON.parse(window.Android ? window.Android.getConfig() : '{}'); } catch (e) { return {}; }
  }
  function showVoiceValues() {
    $('pitch-val').textContent = '(' + Number(pitchEl.value).toFixed(2) + ')';
    $('rate-val').textContent = '(' + Number(rateEl.value).toFixed(2) + ')';
  }
  function setBadge(edit, shell) {
    var badge = $('badge');
    badge.hidden = !edit;
    badge.textContent = edit && shell ? '✎ Edición + terminal' : '✎ Modo edición';
    badge.className = edit && shell ? 'shell' : '';
  }
  function syncShell() {
    shellEl.disabled = !editEl.checked;
    if (!editEl.checked) shellEl.checked = false;
    $('shell-row').className = 'check' + (editEl.checked ? '' : ' disabled');
  }
  editEl.addEventListener('change', syncShell);

  function openSettings() {
    var cfg = readConfig();
    // Stored as "ip:port"; shown as two fields because the numeric
    // keyboard on the Fire has no ":" key.
    var parts = (cfg.host || '').split(':');
    hostEl.value = parts[0] || '';
    portEl.value = parts[1] || DEFAULT_PORT;
    codeEl.value = cfg.code || '';
    modelEl.value = cfg.model || 'sonnet';
    editEl.checked = !!cfg.edit;
    shellEl.checked = !!cfg.shell;
    cameraEl.value = cfg.camera === 'back' ? 'back' : 'front';
    syncShell();
    pitchEl.value = cfg.pitch || 1;
    rateEl.value = cfg.rate || 1;
    showVoiceValues();
    msgEl.textContent = '';
    msgEl.className = '';
    saveEl.disabled = false;
    settingsEl.hidden = false;
  }
  function closeSettings() { settingsEl.hidden = true; }

  function prefs() {
    return {
      model: modelEl.value,
      edit: editEl.checked,
      shell: editEl.checked && shellEl.checked,
      camera: cameraEl.value,
      pitch: Number(pitchEl.value),
      rate: Number(rateEl.value)
    };
  }

  pitchEl.addEventListener('input', showVoiceValues);
  rateEl.addEventListener('input', showVoiceValues);
  $('preview').addEventListener('click', function () {
    var p = prefs();
    var sample = 'Hola Jorge. Así sonaría mi voz.';
    if (window.Android && window.Android.previewVoice) {
      window.Android.previewVoice(sample, p.pitch, p.rate);
    } else if ('speechSynthesis' in window) {
      var u = new SpeechSynthesisUtterance(sample);
      u.lang = 'es-ES'; u.pitch = p.pitch; u.rate = p.rate;
      speechSynthesis.cancel(); speechSynthesis.speak(u);
    }
  });

  $('gear').addEventListener('click', openSettings);
  $('cancel').addEventListener('click', closeSettings);
  $('settings-form').addEventListener('submit', function (e) {
    e.preventDefault();
    if (!window.Android) {
      msgEl.textContent = 'Esto solo funciona en la tableta.';
      msgEl.className = 'error';
      return;
    }
    var p = prefs();
    window.Android.savePrefs(JSON.stringify(p));
    setBadge(p.edit, p.shell);
    if (!hostEl.value.trim() || !codeEl.value.trim()) {
      msgEl.textContent = 'Guardado. Falta la IP y el código para conectar con el PC.';
      msgEl.className = 'error';
      return;
    }
    msgEl.textContent = 'Probando conexión…';
    msgEl.className = '';
    saveEl.disabled = true;
    var host = hostEl.value.replace(/\s/g, '').replace(/,/g, '.');
    if (host.indexOf(':') < 0) host += ':' + (portEl.value.trim() || DEFAULT_PORT);
    window.Android.saveConfig(host, codeEl.value);
  });

  (function () { var c = readConfig(); setBadge(!!c.edit, !!c.shell); })();

  // ---- Keyboard and camera ------------------------------------------------
  var typebar = $('typebar'), typed = $('typed');
  function setTyping(on) {
    typebar.hidden = !on;
    document.body.classList.toggle('typing', on);
    if (window.Android && window.Android.setTyping) window.Android.setTyping(on);
    if (on) typed.focus();
  }
  $('kbd-btn').addEventListener('click', function () { setTyping(typebar.hidden); });
  typebar.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = typed.value.trim();
    if (!text && cameraOn) text = '¿Qué ves?';
    if (!text) return;
    typed.value = '';
    typed.blur();
    setTyping(false);
    setStatus('«' + text + '»');
    if (window.Android && window.Android.sendText) window.Android.sendText(text);
    else speak('Escribiste: ' + text);
  });
  var cameraOn = false;
  $('cam-btn').addEventListener('click', function () {
    if (window.Android && window.Android.toggleCamera) window.Android.toggleCamera();
    else setStatus('La cámara solo funciona en la tableta.');
  });

  window.Face = {
    setTalking: function (on) {
      state.talking = !!on;
      state.nextSyllable = 0;
    },
    setMode: function (mode) {
      state.mode = mode;
      state.level = 0;
      if (mode === 'idle') state.nextLook = 0;
    },
    setLevel: function (x) { state.level = x; },
    pulse: function () {
      // A word boundary from the voice engine: snap the mouth open.
      state.mouthTarget = 0.6 + Math.random() * 0.4;
      state.nextSyllable = performance.now() + 90;
    },
    setStatus: setStatus,
    openSettings: openSettings,
    setCameraOn: function (on) {
      cameraOn = !!on;
      $('cam-btn').classList.toggle('on', cameraOn);
    },
    configResult: function (ok, message) {
      msgEl.textContent = message;
      msgEl.className = ok ? 'ok' : 'error';
      saveEl.disabled = false;
      if (ok) {
        setTimeout(closeSettings, 1200);
        setStatus('Toca la cara para hablar');
      }
    }
  };

  window.addEventListener('resize', resize);
  resize();
  requestAnimationFrame(frame);
})();
