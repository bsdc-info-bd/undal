/**
 * The map tick system (P-046).
 *
 * One small script, no library, no API key, no build step. A customer in Sylhet
 * on a phone browser gets a real slippy map — OpenStreetMap tiles, Web Mercator,
 * drag to pan, zoom buttons, a pin fixed to the centre of the screen — and the
 * address they are about to give a rider is the place they *ticked*, not a
 * guess read from a GPS fix taken three streets away.
 *
 * Why a pin in the middle rather than a draggable marker
 * ------------------------------------------------------
 * A draggable marker on a touch screen fights the map: the finger that means
 * "move the marker" pans the map instead, and the fix that arrives is a metre or
 * a kilometre from where the customer meant. Moving the map under a fixed pin has
 * no such ambiguity, it is what Google Maps' own "pick location" does, and it
 * works with one finger on a low-end phone.
 *
 * Why the tick is a separate step
 * -------------------------------
 * The delivery fee is measured from this point, and the rider is sent to it. So
 * the pin is *proposed* while the map moves and *confirmed* by one deliberate
 * tap, which is also the moment the reverse-geocoded address is written into the
 * form. A customer who never taps it has not chosen an address, and the checkout
 * says so instead of sending a rider to the middle of a river.
 *
 * The map degrades honestly: if the tiles do not load (no data, a blocked CDN)
 * the pin, the radius circle, the distance and the fee still work, because none
 * of them need a picture — they need coordinates.
 */

export const MAP_STORAGE_KEY = "undal_map_view_v1";

export const MAP_SCRIPT = `
(function () {
  var TILE = 256;
  /** Sylhet city: where the map starts when nobody has chosen a point yet. */
  var DEFAULT_CENTER = { lat: 24.8951, lng: 91.8682 };
  var DEFAULT_ZOOM = 15;
  var MIN_ZOOM = 11;
  var MAX_ZOOM = 18;
  var VIEW_KEY = ${JSON.stringify(MAP_STORAGE_KEY)};

  function clamp(value, low, high) { return value < low ? low : value > high ? high : value; }

  function project(lat, lng, zoom) {
    var scale = TILE * Math.pow(2, zoom);
    var sinLat = Math.sin(clamp(lat, -85.05, 85.05) * Math.PI / 180);
    return {
      x: (lng + 180) / 360 * scale,
      y: (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale
    };
  }

  function unproject(x, y, zoom) {
    var scale = TILE * Math.pow(2, zoom);
    var n = Math.PI - 2 * Math.PI * clamp(y, 0, scale) / scale;
    return {
      lat: 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))),
      lng: clamp(x, 0, scale) / scale * 360 - 180
    };
  }

  function metersPerPixel(lat, zoom) {
    return 156543.03392 * Math.cos(lat * Math.PI / 180) / Math.pow(2, zoom);
  }

  // The same great-circle distance the app and the Worker compute. It is checked
  // against the app's own DistanceTest vectors on every push by
  // tools/check-web-rules.mjs, so it cannot drift away from the fee a customer is
  // charged.
  function haversine(a, b) {
    var toRad = function (degrees) { return degrees * Math.PI / 180; };
    var dLat = toRad(b.lat - a.lat), dLng = toRad(b.lng - a.lng);
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 6371.0088 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
  }
  window.undalHaversine = haversine;

  function usablePoint(point) {
    return !!point &&
      typeof point.lat === "number" && typeof point.lng === "number" &&
      isFinite(point.lat) && isFinite(point.lng) &&
      point.lat >= -90 && point.lat <= 90 && point.lng >= -180 && point.lng <= 180 &&
      !(point.lat === 0 && point.lng === 0);
  }
  window.undalUsablePoint = usablePoint;

  function readView() {
    try { return JSON.parse(localStorage.getItem(VIEW_KEY) || "null"); } catch (error) { return null; }
  }
  function writeView(view) {
    try { localStorage.setItem(VIEW_KEY, JSON.stringify(view)); } catch (error) { /* private mode */ }
  }

  /**
   * Builds a map in the element named by options.mount.
   *
   * options:
   *   mount        id of the element to fill
   *   pin          starting point (falls back to the last view, then to Sylhet)
   *   zoom         starting zoom
   *   store        { lat, lng, radiusKm } — draws the delivery radius
   *   perKmPaisa   shows the fee next to the distance when given
   *   onPin(point, info)   called while the map settles; info carries the address
   *   onConfirm(point, info)  called once, when the customer ticks the pin
   *   confirmLabel text for the tick button
   */
  function undalMap(options) {
    var host = document.getElementById(options.mount);
    if (!host) return null;

    var saved = readView();
    var center = usablePoint(options.pin) ? { lat: options.pin.lat, lng: options.pin.lng }
      : usablePoint(saved && saved.center) ? { lat: saved.center.lat, lng: saved.center.lng }
      : { lat: DEFAULT_CENTER.lat, lng: DEFAULT_CENTER.lng };
    var zoom = clamp(options.zoom || (saved && saved.zoom) || DEFAULT_ZOOM, MIN_ZOOM, MAX_ZOOM);
    var store = usablePoint(options.store) ? options.store : null;
    var radiusKm = store && options.store.radiusKm > 0 ? options.store.radiusKm : 0;
    var confirmed = false;
    var info = { area: "", road: "", label: "" };
    var geocodeTimer = null;
    var frame = null;
    var dragging = null;

    host.innerHTML = "";
    host.classList.add("undal-map-host");

    var frame_ = document.createElement("div");
    frame_.className = "undal-map";
    frame_.setAttribute("role", "group");
    frame_.setAttribute("aria-label", "Delivery map. Move the map until the pin is on your door, then tick it.");

    var tiles = document.createElement("div");
    tiles.className = "undal-map-tiles";
    tiles.setAttribute("aria-hidden", "true");

    var overlay = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    overlay.setAttribute("class", "undal-map-overlay");
    overlay.setAttribute("aria-hidden", "true");

    var pin = document.createElement("div");
    pin.className = "undal-map-pin";
    pin.innerHTML = '<svg viewBox="0 0 24 34" width="30" height="42" aria-hidden="true">' +
      '<path d="M12 0C5.4 0 0 5.3 0 11.9 0 20.6 12 34 12 34s12-13.4 12-22.1C24 5.3 18.6 0 12 0z" fill="#165b3b"/>' +
      '<circle cx="12" cy="12" r="4.6" fill="#fff"/></svg>';

    var controls = document.createElement("div");
    controls.className = "undal-map-controls";

    function controlButton(label, text, handler) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "undal-map-button";
      button.textContent = text;
      button.setAttribute("aria-label", label);
      button.title = label;
      button.addEventListener("click", handler);
      return button;
    }

    controls.appendChild(controlButton("Zoom in", "+", function () { setZoom(zoom + 1); }));
    controls.appendChild(controlButton("Zoom out", "\\u2212", function () { setZoom(zoom - 1); }));
    controls.appendChild(controlButton("Centre the map on my location", "\\u2316", function () { locate(); }));

    var readout = document.createElement("div");
    readout.className = "undal-map-readout";
    readout.setAttribute("role", "status");
    readout.setAttribute("aria-live", "polite");

    var tick = document.createElement("button");
    tick.type = "button";
    tick.className = "button undal-map-tick";
    tick.textContent = options.confirmLabel || "Tick this pin as my address";

    frame_.appendChild(tiles);
    frame_.appendChild(overlay);
    frame_.appendChild(pin);
    frame_.appendChild(controls);
    host.appendChild(frame_);
    host.appendChild(readout);
    host.appendChild(tick);

    function width() { return frame_.clientWidth || 320; }
    function height() { return frame_.clientHeight || 240; }

    function setZoom(next) {
      zoom = clamp(Math.round(next), MIN_ZOOM, MAX_ZOOM);
      draw();
      settle();
    }

    function setPin(lat, lng, nextZoom) {
      center = { lat: clamp(lat, -90, 90), lng: clamp(lng, -180, 180) };
      if (typeof nextZoom === "number") zoom = clamp(Math.round(nextZoom), MIN_ZOOM, MAX_ZOOM);
      confirmed = false;
      tick.classList.remove("ticked");
      draw();
      settle();
    }

    function draw() {
      var w = width(), h = height();
      var origin = project(center.lat, center.lng, zoom);
      var left = origin.x - w / 2, top = origin.y - h / 2;

      var firstX = Math.floor(left / TILE), firstY = Math.floor(top / TILE);
      var lastX = Math.floor((left + w) / TILE), lastY = Math.floor((top + h) / TILE);
      var span = Math.pow(2, zoom);
      var wanted = {};
      var html = [];
      for (var tx = firstX; tx <= lastX; tx++) {
        for (var ty = firstY; ty <= lastY; ty++) {
          if (ty < 0 || ty >= span) continue;
          var wrapped = ((tx % span) + span) % span;
          var key = zoom + "/" + wrapped + "/" + ty;
          if (wanted[key]) continue;
          wanted[key] = true;
          html.push('<img class="undal-tile" alt="" decoding="async" width="' + TILE + '" height="' + TILE +
            '" style="left:' + (tx * TILE - left) + 'px;top:' + (ty * TILE - top) + 'px" ' +
            'src="https://tile.openstreetmap.org/' + zoom + '/' + wrapped + '/' + ty + '.png" ' +
            'onerror="this.remove()">');
        }
      }
      tiles.innerHTML = html.join("");

      // The radius and the delivery leg are drawn from coordinates, not tiles, so
      // they survive a map that cannot load its pictures.
      while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
      overlay.setAttribute("viewBox", "0 0 " + w + " " + h);
      overlay.setAttribute("width", String(w));
      overlay.setAttribute("height", String(h));
      if (store) {
        var at = project(store.lat, store.lng, zoom);
        var cx = at.x - left, cy = at.y - top;
        if (radiusKm > 0) {
          var pixelsPerKm = 1000 / metersPerPixel(center.lat, zoom);
          var circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
          circle.setAttribute("cx", String(cx));
          circle.setAttribute("cy", String(cy));
          circle.setAttribute("r", String(Math.max(2, radiusKm * pixelsPerKm)));
          circle.setAttribute("class", "undal-map-radius");
          overlay.appendChild(circle);
        }
        var line = document.createElementNS("http://www.w3.org/2000/svg", "line");
        line.setAttribute("x1", String(cx));
        line.setAttribute("y1", String(cy));
        line.setAttribute("x2", String(w / 2));
        line.setAttribute("y2", String(h / 2));
        line.setAttribute("class", "undal-map-leg");
        overlay.appendChild(line);
        var dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
        dot.setAttribute("cx", String(cx));
        dot.setAttribute("cy", String(cy));
        dot.setAttribute("r", "5");
        dot.setAttribute("class", "undal-map-kitchen");
        overlay.appendChild(dot);
      }
      writeView({ center: center, zoom: zoom });
      paintReadout();
    }

    function paintReadout() {
      var parts = [];
      parts.push(confirmed ? "Pin ticked" : "Move the map, then tick the pin");
      if (info.label) parts.push(info.label);
      if (store) {
        var km = haversine(store, center);
        var text = km.toFixed(1) + " km from the kitchen";
        if (radiusKm > 0) text += km > radiusKm ? " \\u00b7 outside the " + radiusKm + " km radius" : " \\u00b7 inside the " + radiusKm + " km radius";
        if (options.perKmPaisa > 0 && typeof window.undalMoney === "function") {
          text += " \\u00b7 delivery " + window.undalMoney(Math.ceil(km) * options.perKmPaisa);
        }
        parts.push(text);
      }
      readout.textContent = parts.join(" \\u2014 ");
    }

    /** Announces the point to the page, and asks OpenStreetMap what it is called. */
    function settle() {
      paintReadout();
      if (typeof options.onPin === "function") options.onPin({ lat: center.lat, lng: center.lng }, info);
      if (geocodeTimer) clearTimeout(geocodeTimer);
      geocodeTimer = setTimeout(geocode, 650);
    }

    function geocode() {
      // Straight from this browser to OpenStreetMap: the customer's position is
      // never routed through the kitchen's server.
      fetch("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=" + center.lat.toFixed(6) +
        "&lon=" + center.lng.toFixed(6), { headers: { "Accept-Language": "en" } })
        .then(function (response) { return response.ok ? response.json() : null; })
        .then(function (data) {
          if (!data) return;
          var address = data.address || {};
          info = {
            area: address.suburb || address.neighbourhood || address.village || address.town || address.city || "",
            road: [address.house_number, address.road].filter(Boolean).join(" "),
            label: data.display_name ? String(data.display_name).split(",").slice(0, 3).join(",").trim() : "",
            city: address.city || address.town || ""
          };
          paintReadout();
          if (typeof options.onPin === "function") options.onPin({ lat: center.lat, lng: center.lng }, info);
        })
        .catch(function () { /* no name is not an error: the pin still works */ });
    }

    function locate() {
      if (!navigator.geolocation) {
        readout.textContent = "This browser cannot read your location. Drag the map to your door instead.";
        return;
      }
      readout.textContent = "Reading your position\\u2026";
      navigator.geolocation.getCurrentPosition(function (fix) {
        setPin(fix.coords.latitude, fix.coords.longitude, Math.max(zoom, 16));
      }, function (error) {
        readout.textContent = "Location refused (" + error.message + "). Drag the map to your door instead.";
      }, { enableHighAccuracy: true, timeout: 12000, maximumAge: 60000 });
    }

    tick.addEventListener("click", function () {
      confirmed = true;
      tick.classList.add("ticked");
      tick.textContent = "\\u2713 Ticked \\u2014 " + (info.label || "pin saved");
      paintReadout();
      if (typeof options.onConfirm === "function") {
        options.onConfirm({ lat: center.lat, lng: center.lng }, info);
      }
    });

    // ---- dragging: one finger moves the map under the pin --------------------
    frame_.addEventListener("pointerdown", function (event) {
      if (event.target.closest && event.target.closest(".undal-map-controls")) return;
      dragging = { x: event.clientX, y: event.clientY, moved: false };
      try { frame_.setPointerCapture(event.pointerId); } catch (error) { /* older browsers */ }
      frame_.classList.add("dragging");
      event.preventDefault();
    });
    frame_.addEventListener("pointermove", function (event) {
      if (!dragging) return;
      var dx = event.clientX - dragging.x, dy = event.clientY - dragging.y;
      if (Math.abs(dx) + Math.abs(dy) < 1) return;
      dragging.moved = true;
      dragging.x = event.clientX;
      dragging.y = event.clientY;
      var origin = project(center.lat, center.lng, zoom);
      var next = unproject(origin.x - dx, origin.y - dy, zoom);
      center = { lat: clamp(next.lat, -85.05, 85.05), lng: clamp(next.lng, -180, 180) };
      if (confirmed) { confirmed = false; tick.classList.remove("ticked"); }
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(draw);
    });
    function endDrag() {
      if (!dragging) return;
      var moved = dragging.moved;
      dragging = null;
      frame_.classList.remove("dragging");
      if (moved) settle();
    }
    frame_.addEventListener("pointerup", endDrag);
    frame_.addEventListener("pointercancel", endDrag);
    frame_.addEventListener("wheel", function (event) {
      event.preventDefault();
      setZoom(zoom + (event.deltaY < 0 ? 1 : -1));
    }, { passive: false });

    // ---- keyboard: the map has to work without a finger ----------------------
    frame_.tabIndex = 0;
    frame_.addEventListener("keydown", function (event) {
      var step = { ArrowLeft: [-40, 0], ArrowRight: [40, 0], ArrowUp: [0, -40], ArrowDown: [0, 40] }[event.key];
      if (step) {
        event.preventDefault();
        var origin = project(center.lat, center.lng, zoom);
        var next = unproject(origin.x - step[0], origin.y - step[1], zoom);
        center = next;
        draw();
        settle();
      } else if (event.key === "+" || event.key === "=") { setZoom(zoom + 1); }
      else if (event.key === "-") { setZoom(zoom - 1); }
      else if (event.key === "Enter" || event.key === " ") { event.preventDefault(); tick.click(); }
    });

    draw();
    if (usablePoint(options.pin)) settle(); else paintReadout();
    window.addEventListener("resize", draw);

    return {
      setPin: setPin,
      getPin: function () { return { lat: center.lat, lng: center.lng }; },
      isConfirmed: function () { return confirmed; },
      getInfo: function () { return info; },
      locate: locate,
      redraw: draw,
      /** Ticks the pin programmatically — a saved address arrives already chosen. */
      confirm: function () { tick.click(); }
    };
  }

  window.undalMap = undalMap;
})();
`;

/** The CSS the map needs; kept with the rest of the shared design tokens. */
export const MAP_STYLE = `
.undal-map-host{margin:6px 0 14px}
.undal-map{position:relative;width:100%;height:clamp(240px,44vh,420px);border-radius:20px;overflow:hidden;border:1px solid var(--line);
background:#eef1e8 repeating-linear-gradient(0deg,#e4e9dc 0 1px,transparent 1px 32px),repeating-linear-gradient(90deg,#e4e9dc 0 1px,transparent 1px 32px);
touch-action:none;cursor:grab;outline-offset:-3px}
.undal-map.dragging{cursor:grabbing}
.undal-map-tiles{position:absolute;inset:0}
.undal-tile{position:absolute;width:256px;height:256px;user-select:none;-webkit-user-drag:none}
.undal-map-overlay{position:absolute;inset:0;pointer-events:none}
.undal-map-radius{fill:#165b3b1f;stroke:#165b3b;stroke-width:2;stroke-dasharray:7 6}
.undal-map-leg{stroke:#d97930;stroke-width:2.5;stroke-dasharray:2 6;stroke-linecap:round}
.undal-map-kitchen{fill:#d97930;stroke:#fff;stroke-width:2}
.undal-map-pin{position:absolute;left:50%;top:50%;transform:translate(-50%,-100%);pointer-events:none;filter:drop-shadow(0 4px 8px #20392c40)}
.undal-map-controls{position:absolute;right:12px;top:12px;display:flex;flex-direction:column;gap:8px}
.undal-map-button{width:44px;height:44px;border-radius:14px;border:1px solid var(--line);background:#fffdf9;color:var(--ink);
font-size:1.25rem;font-weight:700;line-height:1;cursor:pointer;box-shadow:0 4px 14px #20392c14}
.undal-map-button:active{transform:scale(.96)}
.undal-map-readout{font-size:.85rem;color:var(--muted);margin:10px 2px;line-height:1.5}
.undal-map-tick{margin:0;width:100%}
.undal-map-tick.ticked{background:#10432c}
.undal-map-attribution{font-size:.72rem;color:var(--muted);margin:6px 2px 0}
@media(max-width:640px){.undal-map{height:clamp(220px,40vh,320px);border-radius:16px}.undal-map-controls{right:8px;top:8px;gap:6px}.undal-map-button{width:40px;height:40px}}
`;
