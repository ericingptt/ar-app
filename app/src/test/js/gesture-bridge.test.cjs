// Exercises the injected selection layer in a real DOM.
//
// jsdom has no layout engine, so each test supplies the geometry the bridge asks for - which
// is exactly what is being tested: the direction each option is bound to comes from where the
// options sit on screen. Run with `node app/src/test/js/gesture-bridge.test.cjs`.
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
// Resolved from this file so the test runs the same from CI and from a checkout anywhere.
const script = fs.readFileSync(
  path.join(__dirname, '..', '..', 'main', 'assets', 'gesture-bridge.js'), 'utf8');

let failures = 0;
function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) console.log(`        期望 ${JSON.stringify(expected)}\n        實際 ${JSON.stringify(actual)}`);
}

// rects: [[label, x, y, w, h], ...] inside one container
function build(rects, extraChrome = 0) {
  const dom = new JSDOM(`<!doctype html><body><div id="g"></div><div id="chrome"></div></body>`,
    { pretendToBeVisual: true, runScripts: 'dangerously' });
  const { window } = dom;
  const g = window.document.getElementById('g');
  const geom = new Map();
  rects.forEach(([text, x, y, w, h]) => {
    const b = window.document.createElement('button');
    b.textContent = text;
    g.appendChild(b);
    geom.set(b, { left: x, top: y, width: w, height: h });
  });
  const chrome = window.document.getElementById('chrome');
  for (let i = 0; i < extraChrome; i++) {
    const b = window.document.createElement('button');
    b.textContent = 'chrome' + i;
    chrome.appendChild(b);
    geom.set(b, { left: 0, top: 900, width: 40, height: 40 });
  }
  // jsdom has no layout: supply the geometry the bridge asks for.
  window.Element.prototype.getBoundingClientRect = function () {
    const r = geom.get(this) || { left: 0, top: 0, width: 0, height: 0 };
    return { ...r, right: r.left + r.width, bottom: r.top + r.height, x: r.left, y: r.top };
  };
  window.Element.prototype.scrollIntoView = function () {};
  window.Element.prototype.getClientRects = function () {
    return geom.has(this) ? [this.getBoundingClientRect()] : [];
  };
  const tag = window.document.createElement('script');
  tag.textContent = script;
  window.document.body.appendChild(tag);
  return window;
}

// 2 options side by side -> left / right
let w = build([['相信', 20, 400, 120, 50], ['不相信', 200, 400, 120, 50]]);
check('橫向 2 選項 → 左/右', w.__jjsdk.directions(), { left: '相信', right: '不相信' });

// 3 options stacked vertically (CIBAR's .btns grid) -> up / left / down
w = build([['A', 20, 100, 300, 50], ['B', 20, 200, 300, 50], ['C', 20, 300, 300, 50]]);
check('直向 3 選項 → 上/左/下', w.__jjsdk.directions(), { up: 'A', left: 'B', down: 'C' });

// 4 options stacked vertically -> up / left / right / down
w = build([['A', 20, 100, 300, 50], ['B', 20, 200, 300, 50],
           ['C', 20, 300, 300, 50], ['D', 20, 400, 300, 50]]);
check('直向 4 選項 → 上/左/右/下', w.__jjsdk.directions(),
      { up: 'A', left: 'B', right: 'C', down: 'D' });

// 3 options in a row -> left / up / right
w = build([['A', 20, 400, 80, 50], ['B', 120, 400, 80, 50], ['C', 220, 400, 80, 50]]);
check('橫向 3 選項 → 左/上/右', w.__jjsdk.directions(), { left: 'A', up: 'B', right: 'C' });

// a single control that is the whole screen -> right
w = build([['繼續', 20, 400, 300, 50]]);
check('單一選項 → 右', w.__jjsdk.directions(), { right: '繼續' });

// a lone content button alongside a small nav bar is not a decision
w = build([['繼續', 20, 400, 300, 50]], 2);
check('單一內容按鈕 + 導覽列 → 不啟用直接選擇', w.__jjsdk.directions(), null);

// the real risk: a nav bar is also a group of 2-4, and must not capture the swipes
w = build([['相信', 20, 400, 300, 56], ['不相信', 20, 470, 300, 56]], 3);
check('大選項組勝過小導覽列', w.__jjsdk.directions(), { up: '相信', down: '不相信' });

// explicit declaration wins over inference
w = build([['A', 20, 100, 300, 50], ['B', 20, 200, 300, 50]]);
w.document.querySelectorAll('#g button')[0].setAttribute('data-gesture-dir', 'right');
w.__jjsdk.rescan();
check('data-gesture-dir 覆寫推斷', w.__jjsdk.directions(), { right: 'A', up: 'B' });

// swiping actually clicks the mapped option
w = build([['相信', 20, 400, 120, 50], ['不相信', 200, 400, 120, 50]]);
let clicked = null;
w.document.querySelectorAll('#g button').forEach(b =>
  b.addEventListener('click', () => { clicked = b.textContent; }));
w.__jjsdk.onGesture(3, 'RIGHT');
check('右揮 → 按下右邊那顆', clicked, '不相信');
w.__jjsdk.onGesture(2, 'LEFT');
check('左揮 → 按下左邊那顆', clicked, '相信');

console.log(failures ? `\n${failures} 項失敗` : '\n全部通過');
process.exit(failures ? 1 : 0);
