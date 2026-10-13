/**
 * A minimal PNG encoder, used to draw the installable app's icons.
 *
 * A PWA without raster icons is not installable on Android, and this Worker has
 * no binary assets in its bundle. The fix is to draw the mark arithmetically and
 * encode a real PNG here: IHDR + IDAT (deflate through the platform's own
 * CompressionStream) + IEND, with the CRC32 the format requires. Everything is
 * computed from the same colours the app uses, so the icon can never drift from
 * the brand.
 */

const BRAND = [0x0b, 0x5a, 0x2b] as const;
const CREAM = [0xff, 0xfd, 0xf7] as const;
const ACCENT = [0xe8, 0x87, 0x1e] as const;

const CRC_TABLE: number[] = (() => {
  const table: number[] = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff;
  for (const byte of bytes) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
  out.set(data, 8);
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

export const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** Encodes 8-bit RGBA pixels as a PNG. */
export async function encodePng(width: number, height: number, rgba: Uint8Array): Promise<Uint8Array> {
  if (rgba.length !== width * height * 4) {
    throw new Error("PNG buffer does not match its dimensions");
  }

  // Each scanline is prefixed with its filter byte (0 = none).
  const raw = new Uint8Array((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    raw.set(rgba.subarray(y * width * 4, (y + 1) * width * 4), y * (width * 4 + 1) + 1);
  }

  const compressed = await deflate(raw);

  const ihdr = new Uint8Array(13);
  const header = new DataView(ihdr.buffer);
  header.setUint32(0, width);
  header.setUint32(4, height);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  ihdr[10] = 0; // deflate
  ihdr[11] = 0; // adaptive filtering
  ihdr[12] = 0; // no interlacing

  const parts = [PNG_SIGNATURE, chunk("IHDR", ihdr), chunk("IDAT", compressed), chunk("IEND", new Uint8Array(0))];
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const png = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    png.set(part, offset);
    offset += part.length;
  }
  return png;
}

/** zlib-wrapped deflate, which is exactly what a PNG IDAT chunk holds. */
async function deflate(bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array> {
  const stream = new CompressionStream("deflate");
  const writer = stream.writable.getWriter();
  void writer.write(bytes);
  void writer.close();
  const buffer = await new Response(stream.readable).arrayBuffer();
  return new Uint8Array(buffer);
}

function blend(base: readonly number[], over: readonly number[], alpha: number): number[] {
  const mixed: number[] = [];
  for (let i = 0; i < 3; i++) {
    mixed[i] = Math.round(base[i] * (1 - alpha) + over[i] * alpha);
  }
  return mixed;
}

interface Shape {
  /** Returns coverage 0..1 for a point in unit coordinates (0..1). */
  (x: number, y: number): number;
}

function circle(cx: number, cy: number, radius: number, above?: number): Shape {
  return (x, y) => {
    if (above !== undefined && y > above) return 0;
    const dx = x - cx;
    const dy = y - cy;
    return dx * dx + dy * dy <= radius * radius ? 1 : 0;
  };
}

/**
 * Draws the Undal mark: the brand plate, the cream bowl and the orange dome, on
 * a rounded square — the same three colours the app uses.
 *
 * `scale` shrinks the mark inside the canvas so a maskable icon keeps its art
 * inside Android's safe zone, and `bleed` fills the corners for that variant.
 */
function drawIcon(size: number, scale: number, bleed: boolean): Uint8Array {
  const pixels = new Uint8Array(size * size * 4);
  const samples = 2;
  const corner = bleed ? 0 : size * 0.22;

  // The mark in unit coordinates, scaled around the centre: a cream plate, the
  // orange dome that sits on it, and the knob on top — the same three shapes the
  // Android drawable and the web header use.
  const centre = (value: number): number => value * scale + (1 - scale) * 0.5;
  const plate = circle(0.5, centre(0.62), 0.3 * scale);
  const dome = circle(0.5, centre(0.56), 0.31 * scale, centre(0.56));
  const knob = circle(0.5, centre(0.2), 0.05 * scale);

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let plateHits = 0;
      let domeHits = 0;
      let knobHits = 0;
      let squareHits = 0;
      for (let sy = 0; sy < samples; sy++) {
        for (let sx = 0; sx < samples; sx++) {
          const px = x + (sx + 0.5) / samples;
          const py = y + (sy + 0.5) / samples;
          const ux = px / size;
          const uy = py / size;
          const insideSquare =
            bleed ||
            (px >= corner && px <= size - corner) ||
            (py >= corner && py <= size - corner) ||
            Math.hypot(px - corner, py - corner) <= corner ||
            Math.hypot(px - (size - corner), py - corner) <= corner ||
            Math.hypot(px - corner, py - (size - corner)) <= corner ||
            Math.hypot(px - (size - corner), py - (size - corner)) <= corner;
          if (insideSquare) squareHits++;
          if (dome(ux, uy) > 0) domeHits++;
          if (plate(ux, uy) > 0) plateHits++;
          if (knob(ux, uy) > 0) knobHits++;
        }
      }
      const total = samples * samples;
      const squareAlpha = squareHits / total;
      const alpha = bleed ? 255 : Math.round(squareAlpha * 255);
      // Paint in order: brand square, cream plate, orange dome, cream knob.
      let colour: number[] = [...BRAND];
      const plateAlpha = plateHits / total;
      const domeAlpha = domeHits / total;
      const knobAlpha = knobHits / total;
      if (plateAlpha > 0) colour = blend(colour, CREAM, plateAlpha);
      if (domeAlpha > 0) colour = blend(colour, ACCENT, domeAlpha);
      if (knobAlpha > 0) colour = blend(colour, CREAM, knobAlpha);
      const index = (y * size + x) * 4;
      pixels[index] = colour[0];
      pixels[index + 1] = colour[1];
      pixels[index + 2] = colour[2];
      pixels[index + 3] = alpha;
    }
  }
  return pixels;
}

export interface IconRequest {
  size: number;
  /** `maskable` bleeds to the edges and keeps the mark inside the safe zone. */
  variant: "any" | "maskable";
}

const iconCache = new Map<string, Uint8Array>();

/** The PNG bytes for one icon, computed once per isolate. */
export async function undalIcon({ size, variant }: IconRequest): Promise<Uint8Array> {
  const key = `${variant}-${size}`;
  const cached = iconCache.get(key);
  if (cached) return cached;
  const pixels = drawIcon(size, variant === "maskable" ? 0.62 : 0.86, variant === "maskable");
  const png = await encodePng(size, size, pixels);
  iconCache.set(key, png);
  return png;
}
