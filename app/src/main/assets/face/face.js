/*
 * Jarvis face: two glowing eyes and a mouth on a canvas.
 * Public API (called from Android): Face.setTalking(bool), Face.pulse(), Face.setStatus(text)
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
    var eyeW = s * 0.16;
    var eyeH = s * 0.22 * (1 - state.blink * 0.92);
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
    if (open < 0.04) {
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
    'Hola Jorge. Soy Jarvis. Pronto podré escucharte y conversar contigo.',
    'Todos los sistemas en línea. Esperando instrucciones.',
    'Por ahora solo sé mover la boca. En el siguiente paso aprenderé a escucharte.'
  ];
  var greetingIndex = 0;

  canvas.addEventListener('click', function () {
    if (state.talking) {
      if (window.Android && window.Android.stop) window.Android.stop();
      else if ('speechSynthesis' in window) speechSynthesis.cancel();
      Face.setTalking(false);
      return;
    }
    setStatus('');
    speak(GREETINGS[greetingIndex++ % GREETINGS.length]);
  });

  window.Face = {
    setTalking: function (on) {
      state.talking = !!on;
      state.nextSyllable = 0;
    },
    pulse: function () {
      // A word boundary from the voice engine: snap the mouth open.
      state.mouthTarget = 0.6 + Math.random() * 0.4;
      state.nextSyllable = performance.now() + 90;
    },
    setStatus: setStatus
  };

  window.addEventListener('resize', resize);
  resize();
  requestAnimationFrame(frame);
})();
