// Gesture-driven selection layer, injected into CIBAR by the Android shell.
//
// Deliberately injected rather than committed into CIBAR: the experience is still being built,
// and keeping this out of its source means the two can evolve without colliding. It also has to
// work on any page it lands on, so it discovers what is interactive instead of being told.
//
// The gesture -> action mapping below is a starting point. The SDK documents no motion for any
// of its gestures, so the real mapping has to be established on the device; the HUD prints the
// raw code for exactly that reason, and window.__jjsdk.map can be reassigned at runtime.
(function () {
  'use strict';

  if (window.__jjsdk) {
    window.__jjsdk.rescan();
    return;
  }

  var FOCUS_CLASS = '__jjsdk-focus';
  var SELECTOR = [
    '[data-gesture-focusable]',
    'a[href]',
    'button:not([disabled])',
    '[role="button"]',
    'input:not([type="hidden"]):not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])'
  ].join(',');

  var style = document.createElement('style');
  style.textContent =
    '.' + FOCUS_CLASS + '{outline:3px solid #66D9FF !important;' +
    'outline-offset:2px !important;border-radius:4px;}' +
    '#__jjsdk_hud{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;' +
    'font:12px/1.5 monospace;background:rgba(8,10,15,.86);color:#8FA6B8;' +
    'padding:6px 10px;pointer-events:none;white-space:pre-line;}' +
    '#__jjsdk_hud b{color:#66D9FF;}' +
    '#__jjsdk_dwell{position:fixed;z-index:2147483646;pointer-events:none;' +
    'border-radius:4px;background:rgba(102,217,255,.28);' +
    'box-shadow:inset 0 0 0 2px rgba(102,217,255,.9);transition:width .08s linear;}';
  document.documentElement.appendChild(style);

  var hud = document.createElement('div');
  hud.id = '__jjsdk_hud';
  hud.textContent = '手勢橋接已載入，等待事件…';
  document.documentElement.appendChild(hud);

  var items = [];
  var index = -1;
  var lastGesture = '—';

  // Fills across the focused button as the hand is held still, so the countdown to a press is
  // visible instead of the button firing out of nowhere.
  var dwell = document.createElement('div');
  dwell.id = '__jjsdk_dwell';
  dwell.style.display = 'none';
  document.documentElement.appendChild(dwell);

  function setDwell(progress) {
    var current = items[index];
    if (!current || !(progress > 0)) { dwell.style.display = 'none'; return; }
    var r = current.getBoundingClientRect();
    dwell.style.display = 'block';
    dwell.style.left = r.left + 'px';
    dwell.style.top = r.top + 'px';
    dwell.style.height = r.height + 'px';
    dwell.style.width = (r.width * Math.min(progress, 1)) + 'px';
  }

  function visible(element) {
    if (element.getClientRects().length === 0) return false;
    var style = window.getComputedStyle(element);
    if (style.visibility === 'hidden' || style.display === 'none') return false;
    return element.getAttribute('aria-hidden') !== 'true';
  }

  function rescan() {
    var previous = items[index];
    items = Array.prototype.filter.call(
      document.querySelectorAll(SELECTOR),
      function (element) { return element.id !== '__jjsdk_hud' && visible(element); });
    // Keep the highlight on the same element across re-renders where possible; React swaps
    // nodes constantly, so falling back to a clamped index avoids the ring vanishing.
    var moved = items.indexOf(previous);
    index = moved >= 0 ? moved : (items.length ? Math.min(Math.max(index, 0), items.length - 1) : -1);
    paint();
  }

  function paint() {
    Array.prototype.forEach.call(
      document.querySelectorAll('.' + FOCUS_CLASS),
      function (element) { element.classList.remove(FOCUS_CLASS); });
    var current = items[index];
    if (current) {
      current.classList.add(FOCUS_CLASS);
      current.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
    hud.innerHTML = '手勢 <b>' + lastGesture + '</b>　焦點 <b>' +
      (index < 0 ? '無' : (index + 1) + '/' + items.length) + '</b>　' +
      (current ? label(current) : '此畫面沒有可選元素');
  }

  function label(element) {
    var text = (element.innerText || element.value || element.getAttribute('aria-label') || '')
      .replace(/\s+/g, ' ').trim();
    return text.length > 30 ? text.slice(0, 30) + '…' : (text || element.tagName.toLowerCase());
  }

  function move(step) {
    if (!items.length) rescan();
    if (!items.length) return;
    index = (index + step + items.length) % items.length;
    paint();
  }

  function centre(element) {
    var r = element.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  // Spatial navigation. A scenario screen is a grid of buttons, and DOM order rarely matches
  // what the eye sees, so a swipe has to move to the element that is actually in that
  // direction. Candidates must lie in the swiped half-plane; the winner is the nearest one,
  // with travel across the swipe penalised so a swipe right prefers the neighbour beside the
  // current button over one far above it.
  function moveSpatial(dx, dy) {
    if (!items.length) rescan();
    if (!items.length) return;
    if (index < 0) { index = 0; paint(); return; }

    var from = centre(items[index]);
    var best = -1;
    var bestScore = Infinity;
    for (var i = 0; i < items.length; i++) {
      if (i === index) continue;
      var to = centre(items[i]);
      var along = (to.x - from.x) * dx + (to.y - from.y) * dy;
      if (along <= 8) continue;                       // not in the swiped direction
      var across = Math.abs((to.x - from.x) * dy - (to.y - from.y) * dx);
      var score = along + across * 2;
      if (score < bestScore) { bestScore = score; best = i; }
    }
    if (best >= 0) {
      index = best;
      paint();
      return;
    }
    // Nothing that way. CIBAR lays choices out vertically on some screens (.btns is a
    // single-column grid) and horizontally on others (.dating-actions, .pol-call-actions are
    // flex rows), so every swipe has to stay useful whichever way the current screen runs:
    // fall back to the neighbour in reading order rather than doing nothing. paint() scrolls
    // the new focus into view, so no separate scrolling gesture is needed.
    move(dx + dy > 0 ? 1 : -1);
  }

  function activate() {
    var current = items[index];
    if (!current) return;
    setDwell(0);
    current.focus({ preventScroll: true });
    current.click();
    // The click usually navigates or re-renders; rescan once the frame settles.
    setTimeout(rescan, 120);
  }

  function scrollPage(fraction) {
    window.scrollBy({ top: window.innerHeight * fraction, behavior: 'smooth' });
  }

  // Selection is the whole point: the four swipes move the highlight between buttons, and a
  // push presses the highlighted one. Scrolling is what a swipe falls back to when there is no
  // button that way, rather than something the user has to aim for separately.
  // Selection is the whole point. Holding the hand still presses the focused button: PUSH and
  // PULL both ride the distance axis, the noisiest one the module reports, so neither is
  // dependable enough to be the way into a scenario. They stay mapped as alternatives.
  var map = {
    0: function () { moveSpatial(0, -1); },    // GESTURE_UP
    1: function () { moveSpatial(0, 1); },     // GESTURE_DOWN
    2: function () { moveSpatial(-1, 0); },    // GESTURE_LEFT
    3: function () { moveSpatial(1, 0); },     // GESTURE_RIGHT
    4: function () { window.history.back(); }, // GESTURE_PULL
    5: activate,                               // GESTURE_PUSH
    6: rescan,                                 // GESTURE_HALT
    100: activate,                             // dwell: hold still to press
    255: function () { if (index < 0) move(1); } // PRESENCE
  };

  // Coalesce the re-render storms React produces into one rescan per frame.
  var pending = null;
  var observer = new MutationObserver(function () {
    if (pending) return;
    pending = setTimeout(function () { pending = null; rescan(); }, 150);
  });
  observer.observe(document.body, { childList: true, subtree: true });
  window.addEventListener('hashchange', function () { setTimeout(rescan, 150); });

  window.__jjsdk = {
    map: map,
    rescan: rescan,
    // Exposed for the shell's sensor-driven control: the ToF module's firmware never emits
    // gesture packets, so head orientation and hand range drive these directly instead.
    move: move,
    moveSpatial: moveSpatial,
    activate: activate,
    setDwell: setDwell,
    note: function (text) { lastGesture = text; paint(); },
    onGesture: function (code, name) {
      lastGesture = name + ' (' + code + ')';
      var action = window.__jjsdk.map[code];
      if (action) action(); else paint();
    }
  };

  rescan();
})();
