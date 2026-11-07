/**
 * Responsive, keyless OpenStreetMap branch editor.
 * The kitchen pin is fixed at the centre and the map moves underneath it, which
 * is reliable with touch, mouse, pen and keyboard. Tiles are diffed by x/y/z,
 * never recreated during a frame, and ResizeObserver keeps projection correct.
 */
const TILE = 256;
const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
const rad = (degrees) => degrees * Math.PI / 180;
const deg = (radians) => radians * 180 / Math.PI;

function project(lat, lng, zoom) {
  const scale = TILE * 2 ** zoom;
  return {
    x: (lng + 180) / 360 * scale,
    y: (1 - Math.log(Math.tan(rad(lat)) + 1 / Math.cos(rad(lat))) / Math.PI) / 2 * scale,
  };
}
function unproject(x, y, zoom) {
  const scale = TILE * 2 ** zoom;
  const n = Math.PI - 2 * Math.PI * y / scale;
  return { lat: clamp(deg(Math.atan(Math.sinh(n))), -85.0511, 85.0511), lng: x / scale * 360 - 180 };
}
function valid(lat, lng) {
  return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 85 && Math.abs(lng) <= 180 && !(lat === 0 && lng === 0);
}

export class BranchMap {
  constructor(container, { lat = 24.8949, lng = 91.8687, radiusKm = 5, zoom = 13, onChange = () => {} } = {}) {
    this.container = container;
    this.onChange = onChange;
    this.lat = valid(lat, lng) ? lat : 24.8949;
    this.lng = valid(lat, lng) ? lng : 91.8687;
    this.radiusKm = clamp(Number(radiusKm) || 5, .25, 50);
    this.zoom = clamp(Math.round(zoom), 4, 18);
    this.tiles = new Map();
    this.destroyed = false;
    this.frame = 0;
    this.width = 1;
    this.height = 1;
    this.renderShell();
    this.observe = new ResizeObserver(() => this.resize());
    this.observe.observe(container);
    this.resize();
  }

  renderShell() {
    this.container.classList.add('undal-map');
    this.container.tabIndex = 0;
    this.container.setAttribute('role', 'application');
    this.container.setAttribute('aria-label', 'Branch location map. Drag to move the kitchen pin. Use arrow keys to pan and plus or minus to zoom.');
    this.container.innerHTML = `
      <div class="undal-map-tiles"></div>
      <div class="undal-map-radius" aria-hidden="true"></div>
      <div class="undal-map-center" aria-hidden="true"></div>
      <div class="undal-map-controls">
        <button type="button" data-zoom="in" aria-label="Zoom in">+</button>
        <button type="button" data-zoom="out" aria-label="Zoom out">−</button>
      </div>
      <button type="button" class="undal-map-controls undal-map-locate" data-locate aria-label="Use my current location" title="Use my current location">⌖</button>
      <div class="undal-map-attribution">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors</div>
      <div class="undal-map-message" hidden></div>`;
    this.tileLayer = this.container.querySelector('.undal-map-tiles');
    this.radius = this.container.querySelector('.undal-map-radius');
    this.message = this.container.querySelector('.undal-map-message');
    this.container.querySelector('[data-zoom="in"]').addEventListener('click', () => this.setZoom(this.zoom + 1));
    this.container.querySelector('[data-zoom="out"]').addEventListener('click', () => this.setZoom(this.zoom - 1));
    this.container.querySelector('[data-locate]').addEventListener('click', () => this.locate());
    this.container.addEventListener('pointerdown', (event) => this.pointerDown(event));
    this.container.addEventListener('pointermove', (event) => this.pointerMove(event));
    this.container.addEventListener('pointerup', (event) => this.pointerUp(event));
    this.container.addEventListener('pointercancel', (event) => this.pointerUp(event));
    this.container.addEventListener('wheel', (event) => {
      if (!event.ctrlKey && Math.abs(event.deltaY) < 20) return;
      event.preventDefault();
      this.setZoom(this.zoom + (event.deltaY < 0 ? 1 : -1));
    }, { passive: false });
    this.container.addEventListener('keydown', (event) => this.keydown(event));
  }

  resize() {
    if (this.destroyed) return;
    const rect = this.container.getBoundingClientRect();
    this.width = Math.max(1, Math.round(rect.width));
    this.height = Math.max(1, Math.round(rect.height));
    this.schedule();
  }

  schedule() {
    if (this.frame || this.destroyed) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.render();
    });
  }

  render() {
    const centre = project(this.lat, this.lng, this.zoom);
    const left = centre.x - this.width / 2;
    const top = centre.y - this.height / 2;
    const maxTile = 2 ** this.zoom;
    const minX = Math.floor(left / TILE) - 1;
    const minY = Math.floor(top / TILE) - 1;
    const maxX = Math.floor((left + this.width) / TILE) + 1;
    const maxY = Math.floor((top + this.height) / TILE) + 1;
    const wanted = new Set();
    for (let x = minX; x <= maxX; x += 1) {
      for (let y = minY; y <= maxY; y += 1) {
        if (y < 0 || y >= maxTile) continue;
        const wrappedX = ((x % maxTile) + maxTile) % maxTile;
        const key = `${this.zoom}/${wrappedX}/${y}`;
        wanted.add(key);
        let tile = this.tiles.get(key);
        if (!tile) {
          tile = document.createElement('img');
          tile.className = 'undal-map-tile';
          tile.alt = '';
          tile.draggable = false;
          tile.decoding = 'async';
          tile.loading = 'eager';
          tile.src = `https://tile.openstreetmap.org/${this.zoom}/${wrappedX}/${y}.png`;
          tile.onerror = () => { tile.style.visibility = 'hidden'; };
          tile.onload = () => { tile.style.visibility = 'visible'; };
          this.tiles.set(key, tile);
          this.tileLayer.append(tile);
        }
        const screenX = Math.round(x * TILE - left);
        const screenY = Math.round(y * TILE - top);
        tile.style.transform = `translate3d(${screenX}px,${screenY}px,0)`;
      }
    }
    for (const [key, tile] of this.tiles) {
      if (!wanted.has(key)) { tile.remove(); this.tiles.delete(key); }
    }
    const metresPerPixel = 156543.03392 * Math.cos(rad(this.lat)) / 2 ** this.zoom;
    const diameter = clamp(this.radiusKm * 1000 / metresPerPixel * 2, 8, Math.max(this.width, this.height) * 8);
    this.radius.style.width = `${diameter}px`;
    this.radius.style.height = `${diameter}px`;
    this.container.dataset.lat = this.lat.toFixed(6);
    this.container.dataset.lng = this.lng.toFixed(6);
  }

  emit() {
    this.onChange({ lat: Number(this.lat.toFixed(6)), lng: Number(this.lng.toFixed(6)), radiusKm: this.radiusKm, zoom: this.zoom });
  }

  setValue({ lat, lng, radiusKm, zoom }) {
    if (valid(lat, lng)) { this.lat = lat; this.lng = lng; }
    if (Number.isFinite(Number(radiusKm))) this.radiusKm = clamp(Number(radiusKm), .25, 50);
    if (Number.isFinite(Number(zoom))) this.zoom = clamp(Math.round(zoom), 4, 18);
    this.schedule();
  }

  setZoom(next) {
    const zoom = clamp(Math.round(next), 4, 18);
    if (zoom === this.zoom) return;
    this.zoom = zoom;
    this.schedule();
    this.emit();
  }

  panBy(dx, dy) {
    const centre = project(this.lat, this.lng, this.zoom);
    const next = unproject(centre.x + dx, centre.y + dy, this.zoom);
    this.lat = next.lat;
    this.lng = next.lng;
    this.schedule();
  }

  pointerDown(event) {
    if (event.target.closest('button,a') || event.button > 0) return;
    this.drag = { id: event.pointerId, x: event.clientX, y: event.clientY };
    this.container.setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  pointerMove(event) {
    if (!this.drag || this.drag.id !== event.pointerId) return;
    const dx = event.clientX - this.drag.x;
    const dy = event.clientY - this.drag.y;
    this.drag.x = event.clientX;
    this.drag.y = event.clientY;
    this.panBy(-dx, -dy);
  }

  pointerUp(event) {
    if (!this.drag || this.drag.id !== event.pointerId) return;
    this.drag = null;
    if (this.container.hasPointerCapture(event.pointerId)) this.container.releasePointerCapture(event.pointerId);
    this.emit();
  }

  keydown(event) {
    const step = event.shiftKey ? 160 : 55;
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[event.key]) {
      event.preventDefault();
      this.panBy(...moves[event.key]);
      this.emit();
    } else if (['+', '=', '-', '_'].includes(event.key)) {
      event.preventDefault();
      this.setZoom(this.zoom + (['+', '='].includes(event.key) ? 1 : -1));
    }
  }

  locate() {
    if (!navigator.geolocation) return this.notify('This browser cannot read GPS. Drag the pin instead.');
    this.notify('Finding your location…');
    navigator.geolocation.getCurrentPosition((position) => {
      this.lat = position.coords.latitude;
      this.lng = position.coords.longitude;
      this.zoom = Math.max(this.zoom, 15);
      this.schedule();
      this.emit();
      this.notify(`Pinned to about ${Math.round(position.coords.accuracy)} m accuracy.`);
    }, () => this.notify('Location permission was not available. You can still drag the pin by hand.'), {
      enableHighAccuracy: true, timeout: 12000, maximumAge: 30000,
    });
  }

  notify(text) {
    this.message.textContent = text;
    this.message.hidden = false;
    clearTimeout(this.messageTimer);
    this.messageTimer = setTimeout(() => { this.message.hidden = true; }, 4500);
  }

  destroy() {
    this.destroyed = true;
    this.observe.disconnect();
    cancelAnimationFrame(this.frame);
    clearTimeout(this.messageTimer);
    this.tiles.clear();
    this.container.replaceChildren();
  }
}
