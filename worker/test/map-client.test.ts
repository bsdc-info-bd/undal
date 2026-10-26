import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { MAP_SCRIPT } from '../src/web/map-client';
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
function setup() {
  const host = new Element(), timers: Function[] = [], requests: Function[] = [];
  const window: any = { addEventListener() {}, removeEventListener() {} };
  runInNewContext(MAP_SCRIPT, {
    window, document: { getElementById: () => host, createElement: () => new Element(), createElementNS: () => new Element() },
    localStorage: { getItem: () => null, setItem() {} }, navigator: {},
    setTimeout: (fn: Function) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    requestAnimationFrame: (fn: Function) => { fn(); return 1; }, cancelAnimationFrame() {},
    fetch: () => new Promise(resolve => requests.push(resolve)),
  });
  return { map: window.undalMap({ mount: 'map', pin: { lat: 24.89, lng: 91.86 } }), host, timers, requests };
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
