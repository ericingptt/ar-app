// Drives the bundled harness (app/src/main/assets/gesture-test.html) through the injected
// selection layer and asserts each screen behaves the way that screen says it will.
//
// This is the end-to-end check the device test cannot give us cheaply: it proves a swipe
// reaches the right button and that the button really fired, on every screen shape the
// harness covers. jsdom has no layout engine, so a small simulator assigns the geometry the
// real CSS would produce - a .row lays its buttons out across, a .btns stacks them down, and
// the nav bar sits small at the bottom. Run with:
//   node app/src/test/js/gesture-test-page.test.cjs
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', '..', 'main', 'assets');
const page = fs.readFileSync(path.join(assets, 'gesture-test.html'), 'utf8');
const bridge = fs.readFileSync(path.join(assets, 'gesture-bridge.js'), 'utf8');

const GESTURE = { up: 0, down: 1, left: 2, right: 3 };
let failures = 0;

function check(name, actual, expected) {
  const ok = actual === expected;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) console.log(`        期望 ${JSON.stringify(expected)}\n        實際 ${JSON.stringify(actual)}`);
}

function open() {
  const dom = new JSDOM(page, { runScripts: 'dangerously', pretendToBeVisual: true });
  const { window } = dom;
  window.scrollTo = () => {};
  window.Element.prototype.scrollIntoView = function () {};

  // Stand in for the stylesheet: .row is a horizontal strip, .btns a vertical stack, and the
  // nav bar is deliberately small so the area rule has something to reject.
  const rects = new WeakMap();
  function layout() {
    const doc = window.document;
    doc.querySelectorAll('.row').forEach((row) => {
      const kids = [...row.children];
      const w = Math.floor(448 / kids.length) - 12;
      kids.forEach((kid, i) => rects.set(kid, { left: 16 + i * (w + 12), top: 400, width: w, height: 56 }));
    });
    doc.querySelectorAll('.btns').forEach((box) => {
      [...box.children].forEach((kid, i) => rects.set(kid, { left: 16, top: 120 + i * 66, width: 448, height: 56 }));
    });
    doc.querySelectorAll('.nav button').forEach((btn, i) => {
      rects.set(btn, { left: 40 + i * 120, top: 900, width: 80, height: 30 });
    });
  }
  window.Element.prototype.getBoundingClientRect = function () {
    const r = rects.get(this) || { left: 0, top: 0, width: 0, height: 0 };
    return { ...r, right: r.left + r.width, bottom: r.top + r.height, x: r.left, y: r.top };
  };
  window.Element.prototype.getClientRects = function () {
    return rects.has(this) ? [this.getBoundingClientRect()] : [];
  };

  const tag = window.document.createElement('script');
  tag.textContent = bridge;
  window.document.body.appendChild(tag);

  return {
    window,
    goto(id) {
      window.go(id);
      layout();
      window.__jjsdk.rescan();
    },
    swipe(dir) {
      window.__jjsdk.onGesture(GESTURE[dir], dir.toUpperCase());
      layout();
      window.__jjsdk.rescan();
    },
    /** The bold text on the result screen, i.e. what was actually pressed. */
    result() {
      const b = window.document.querySelector('.result b');
      return b ? b.textContent : null;
    },
    directions() {
      return window.__jjsdk.directions();
    },
  };
}

// ① two options in a row
let app = open();
app.goto('t1');
check('① 橫排 2 選項的方向綁定', JSON.stringify(app.directions()),
  JSON.stringify({ left: '相信', right: '不相信' }));
app.swipe('left');
check('① 左揮 → 相信', app.result(), '相信');
app = open(); app.goto('t1'); app.swipe('right');
check('① 右揮 → 不相信', app.result(), '不相信');

// ② two options stacked
app = open(); app.goto('t2');
check('② 直排 2 選項的方向綁定', JSON.stringify(app.directions()),
  JSON.stringify({ up: '同意', down: '拒絕' }));
app.swipe('down');
check('② 下揮 → 拒絕', app.result(), '拒絕');

// ③ three options stacked: ends take the axis they are spread on, middle takes the other
app = open(); app.goto('t3');
check('③ 直排 3 選項的方向綁定', JSON.stringify(app.directions()),
  JSON.stringify({ up: '馬上匯款', left: '再想想', down: '檢舉詐騙' }));
app.swipe('left');
check('③ 左揮 → 再想想（中間那個）', app.result(), '再想想');

// ④ four options stacked
app = open(); app.goto('t4');
check('④ 直排 4 選項的方向綁定', JSON.stringify(app.directions()),
  JSON.stringify({ up: '選項 A', left: '選項 B', right: '選項 C', down: '選項 D' }));
app.swipe('right');
check('④ 右揮 → 選項 C', app.result(), '選項 C');

// ⑤ three options in a row
app = open(); app.goto('t5');
check('⑤ 橫排 3 選項的方向綁定', JSON.stringify(app.directions()),
  JSON.stringify({ left: '接聽', up: '靜音', right: '掛斷' }));
app.swipe('right');
check('⑤ 右揮 → 掛斷', app.result(), '掛斷');

// ⑥ a lone option
app = open(); app.goto('t6');
check('⑥ 單一選項綁定到右', JSON.stringify(app.directions()),
  JSON.stringify({ right: '繼續下一步' }));
app.swipe('right');
check('⑥ 右揮 → 繼續下一步', app.result(), '繼續下一步');

// ⑦ seven options: too many for four directions, so direct mode must stand down
app = open(); app.goto('t7');
check('⑦ 七個選項 → 不啟用直接選擇', app.directions(), null);
app.swipe('down');
check('⑦ 揮動只移動焦點，不會誤按', app.result(), null);
app.window.__jjsdk.activate();
check('⑦ 停留選取才按下', app.result() !== null, true);

// the nav bar must never capture the swipes away from the question being asked
app = open(); app.goto('t1');
const bound = Object.values(app.directions());
check('導覽列沒有被綁定成選項',
  bound.some((label) => label.indexOf('測試選單') >= 0 || label.indexOf('說明') >= 0), false);

console.log(failures ? `\n${failures} 項失敗` : '\n全部通過');
process.exit(failures ? 1 : 0);
