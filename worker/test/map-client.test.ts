import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { MAP_SCRIPT, MAP_STYLE } from '../src/web/map-client';
class Element {
  children: Element[] = []; className = ''; textContent = ''; innerHTML = '';
  clientWidth = 360; clientHeight = 280; style = {};
  classList = { add() {}, remove() {} }; listeners: Record<string, Function> = {};
  appendChild(child: Element) { this.children.push(child); }
  setAttribute() {} setPointerCapture() {} closest() { return null; }
  addEventListener(name: string, fn: Function) { this.listeners[name] = fn; }
  click() { this.fire('click'); }
  fire(type: string, extra = {}) { this.listeners[type]?.({ target: this, preventDefault() {}, ...extra }); }
}
function setup(
  options: Record<string, unknown> = {},
  size: { width: number; height: number } = { width: 360, height: 280 }
) {
  const host = new Element(), timers: Function[] = [], requests: Function[] = [];
  const window: any = { addEventListener() {}, removeEventListener() {}, innerHeight: 700 };
  // Every element the map builds is the size the caller says: a real browser hands
  // back a measured box, and "the stylesheet could not give the frame a height" is
  // a box of zero.
  const make = () => {
    const el = new Element();
    el.clientWidth = size.width;
    el.clientHeight = size.height;
    return el;
  };
  host.clientWidth = size.width;
  host.clientHeight = size.height;
  runInNewContext(MAP_SCRIPT, {
    window, document: { getElementById: () => host, createElement: make, createElementNS: make },
    localStorage: { getItem: () => null, setItem() {} }, navigator: {},
    setTimeout: (fn: Function) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    requestAnimationFrame: (fn: Function) => { fn(); return 1; }, cancelAnimationFrame() {},
    fetch: () => new Promise(resolve => requests.push(resolve)),
  });
  return {
    map: window.undalMap({ mount: 'map', pin: { lat: 24.89, lng: 91.86 }, ...options }),
    host, timers, requests, window
  };
}
test('keyboard movement invalidates confirmation and restores the button label', () => {
  const { map, host } = setup(); map.confirm(); assert.equal(map.isConfirmed(), true);
  host.children[0].fire('keydown', { key: 'ArrowRight' });
  assert.equal(map.isConfirmed(), false);
  assert.equal(host.children[3].textContent, 'Tick this pin as my address');
});
test('keyboard events from controls do not confirm or pan the map', () => {
  const { map, host } = setup(), before = JSON.stringify(map.getPin());
  host.children[0].fire('keydown', { key: 'ArrowRight', target: new Element() });
  host.children[0].fire('keydown', { key: 'Enter', target: new Element() });
  assert.equal(JSON.stringify(map.getPin()), before); assert.equal(map.isConfirmed(), false);
});
test('stale geocoding cannot overwrite a moved pin address', async () => {
  const { map, timers, requests } = setup(); timers.at(-1)!(); map.setPin(24.90, 91.87);
  requests[0]({ ok: true, json: async () => ({ display_name: 'Old road', address: { road: 'Old road' } }) });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(map.getInfo().label, '');
});
test('a second pointer cannot hijack an active drag', () => {
  const { map, host } = setup(), frame = host.children[0];
  frame.fire('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 });
  frame.fire('pointerdown', { pointerId: 2, clientX: 200, clientY: 200, isPrimary: false });
  const before = JSON.stringify(map.getPin());
  frame.fire('pointermove', { pointerId: 2, clientX: 220, clientY: 220 });
  assert.equal(JSON.stringify(map.getPin()), before);
  frame.fire('pointermove', { pointerId: 1, clientX: 120, clientY: 100 });
  assert.notEqual(JSON.stringify(map.getPin()), before);
});
test('invalid coordinates are ignored', () => {
  const { map } = setup(), before = JSON.stringify(map.getPin());
  map.setPin(NaN, 91); assert.equal(JSON.stringify(map.getPin()), before);
});
test('disposing the map suppresses outstanding address lookups', async () => {
  const { map, timers, requests } = setup(); timers.at(-1)!(); map.destroy();
  requests[0]({ ok: true, json: async () => ({ display_name: 'Late result', address: {} }) });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(map.getInfo().label, '');
});
test('lost pointer capture ends a drag so the pin can be confirmed', () => {
  const { map, host } = setup(), frame = host.children[0];
  frame.fire('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 });
  map.confirm(); assert.equal(map.isConfirmed(), false);
  frame.fire('lostpointercapture', { pointerId: 1 });
  map.confirm(); assert.equal(map.isConfirmed(), true);
});
test('precision arrows move the pin and require fresh confirmation', () => {
  const { map, host } = setup(), before = map.getPin(); map.confirm();
  host.children[4].children[1].click();
  assert.ok(map.getPin().lat > before.lat);
  assert.equal(map.getPin().lng, before.lng);
  assert.equal(map.isConfirmed(), false);
});
test('a frame the stylesheet could not size still gets a map', () => {
  // The pin is centred on the frame and the frame is overflow:hidden: a collapsed
  // frame is a map with nothing in it and a pin on its top edge, which is the
  // "showpiece stuck in the corner" a customer reports. The script measures the
  // frame it built and writes a height itself when CSS gave it none.
  const { host } = setup({}, { width: 0, height: 0 });
  const frame = host.children[0];
  const tall = parseInt(String(frame.style.height), 10);
  assert.ok(tall >= 240 && tall <= 420, `the map sized itself to ${frame.style.height}`);
  assert.equal(frame.style.width, '100%');
  assert.equal(frame.style.minWidth, '240px');
});
test('a frame CSS did size is left exactly as CSS left it', () => {
  const { host } = setup();
  assert.equal(host.children[0].style.height, undefined, 'no inline height over a working stylesheet');
  assert.equal(host.children[0].style.width, undefined);
});
test('a finger drags the map on an engine with no Pointer Events', () => {
  const picks: Array<{ lat: number; lng: number }> = [];
  const { map, host } = setup({ onPin: (point: { lat: number; lng: number }) => picks.push(point) });
  const frame = host.children[0];
  const before = map.getPin();
  frame.fire('touchstart', { touches: [{ clientX: 200, clientY: 200 }] });
  frame.fire('touchmove', { touches: [{ clientX: 160, clientY: 200 }] });
  frame.fire('touchend', {});
  assert.ok(map.getPin().lng > before.lng, 'the map moved under the pin');
  assert.ok(picks.length > 0, 'the moved pin was announced to the page');
  assert.equal(picks[picks.length - 1].lat, map.getPin().lat);
});
test('a second finger is a map gesture, not a drag', () => {
  const { map, host } = setup();
  const frame = host.children[0], before = JSON.stringify(map.getPin());
  frame.fire('touchstart', { touches: [{ clientX: 200, clientY: 200 }, { clientX: 260, clientY: 200 }] });
  frame.fire('touchmove', { touches: [{ clientX: 120, clientY: 200 }, { clientX: 300, clientY: 200 }] });
  assert.equal(JSON.stringify(map.getPin()), before);
});
test('the stylesheet keeps a height on an engine that has never heard of clamp', () => {
  const frame = MAP_STYLE.match(/\.undal-map\{[^}]*\}/);
  assert.ok(frame, 'the map frame is styled');
  // The plain pixel height comes first so an engine that cannot parse clamp()
  // still has one, and a floor so no engine can collapse the frame at all.
  assert.match(frame[0], /height:280px;height:clamp\(240px,44vh,420px\)/);
  assert.match(frame[0], /min-height:220px/);
  assert.match(MAP_STYLE, /@media\(max-width:640px\)\{\.undal-map\{height:260px;height:clamp\(220px,40vh,320px\);min-height:200px/);
  // inset:0 needs Chrome 87 and the storefront's floor is Chrome 80.
  assert.ok(!/inset:/.test(MAP_STYLE), 'offsets are written out, not shorthand');
  assert.match(MAP_STYLE, /\.undal-map-tiles\{position:absolute;top:0;right:0;bottom:0;left:0\}/);
  // A touch target never shrinks below 44 CSS px, on a phone least of all.
  assert.ok(!/\.undal-map-button\{width:40px/.test(MAP_STYLE), 'map buttons stay 44px on a phone');
  assert.match(MAP_STYLE, /\.undal-map-tick\{margin:0;width:100%;min-height:44px\}/);
});
