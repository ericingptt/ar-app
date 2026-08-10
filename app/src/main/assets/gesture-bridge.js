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
    '#__jjsdk_hud b{color:#66D9FF;}';
  document.documentElement.appendChild(style);

  var hud = document.createElement('div');
  hud.id = '__jjsdk_hud';
  hud.textContent = '手勢橋接已載入，等待事件…';
  document.documentElement.appendChild(hud);

  var items = [];
  var index = -1;
  var lastGesture = '—';

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

  function activate() {
    var current = items[index];
    if (!current) return;
    current.focus({ preventScroll: true });
    current.click();
    // The click usually navigates or re-renders; rescan once the frame settles.
    setTimeout(rescan, 120);
  }

  function scrollPage(fraction) {
    window.scrollBy({ top: window.innerHeight * fraction, behavior: 'smooth' });
  }

  var map = {
    0: function () { scrollPage(-0.6); },   // GESTURE_UP
    1: function () { scrollPage(0.6); },    // GESTURE_DOWN
    2: function () { move(-1); },           // GESTURE_LEFT
    3: function () { move(1); },            // GESTURE_RIGHT
    4: function () { window.history.back(); }, // GESTURE_PULL
    5: activate,                            // GESTURE_PUSH
    6: rescan,                              // GESTURE_HALT
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
    activate: activate,
    note: function (text) { lastGesture = text; paint(); },
    onGesture: function (code, name) {
      lastGesture = name + ' (' + code + ')';
      var action = window.__jjsdk.map[code];
      if (action) action(); else paint();
    }
  };

  rescan();
})();
