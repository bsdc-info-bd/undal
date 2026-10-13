/**
 * A dependency-free QR Code encoder (byte mode, error correction level M).
 *
 * The download page shows a QR code so the owner can hold one phone up to
 * another, and the Worker must never depend on an external image service to
 * draw it: a third-party QR endpoint would leak every visitor's IP to a
 * stranger and would break the day that service is blocked. The encoder below
 * is the whole feature: ISO/IEC 18004 data placement, Reed–Solomon over
 * GF(256), the eight masks scored with the four penalty rules, and an SVG
 * renderer.
 *
 * Scope is deliberate: byte mode covers the URLs this project shares, and
 * versions 1–12 (up to 290 data codewords) cover any realistic link with room
 * to spare. Anything longer throws instead of silently truncating.
 */

const ECC_BITS_M = 0b00;

interface VersionSpec {
  version: number;
  /** Total data codewords at ECC level M. */
  dataCodewords: number;
  /** Error correction codewords per block. */
  ecPerBlock: number;
  /** Number of Reed–Solomon blocks. */
  blocks: number;
  /** Alignment pattern centre coordinates. */
  align: number[];
}

/** The subset of the standard's table this encoder supports. */
const SPECS: VersionSpec[] = [
  { version: 1, dataCodewords: 16, ecPerBlock: 10, blocks: 1, align: [] },
  { version: 2, dataCodewords: 28, ecPerBlock: 16, blocks: 1, align: [6, 18] },
  { version: 3, dataCodewords: 44, ecPerBlock: 26, blocks: 1, align: [6, 22] },
  { version: 4, dataCodewords: 64, ecPerBlock: 18, blocks: 2, align: [6, 26] },
  { version: 5, dataCodewords: 86, ecPerBlock: 24, blocks: 2, align: [6, 30] },
  { version: 6, dataCodewords: 108, ecPerBlock: 16, blocks: 4, align: [6, 34] },
  { version: 7, dataCodewords: 124, ecPerBlock: 18, blocks: 4, align: [6, 22, 38] },
  { version: 8, dataCodewords: 154, ecPerBlock: 22, blocks: 4, align: [6, 24, 42] },
  { version: 9, dataCodewords: 182, ecPerBlock: 22, blocks: 5, align: [6, 26, 46] },
  { version: 10, dataCodewords: 216, ecPerBlock: 26, blocks: 5, align: [6, 28, 50] },
  { version: 11, dataCodewords: 254, ecPerBlock: 30, blocks: 5, align: [6, 30, 54] },
  { version: 12, dataCodewords: 290, ecPerBlock: 22, blocks: 8, align: [6, 32, 58] },
];

const MIN_VERSION = SPECS[0];
const MAX_VERSION = SPECS[SPECS.length - 1];

// ---------------------------------------------------------------- field arithmetic

let expTable: number[] | null = null;
let logTable: number[] | null = null;

/** GF(256) tables with the QR primitive polynomial 0x11D. */
function fields(): { exp: number[]; log: number[] } {
  if (expTable && logTable) return { exp: expTable, log: logTable };
  const exp = new Array<number>(256).fill(0);
  const log = new Array<number>(256).fill(0);
  let x = 1;
  for (let i = 0; i < 255; i++) {
    exp[i] = x;
    log[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 256; i++) exp[i] = exp[i - 255];
  expTable = exp;
  logTable = log;
  return { exp, log };
}

function gfMultiply(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  const { exp, log } = fields();
  return exp[(log[a] + log[b]) % 255];
}

/** The generator polynomial for `degree` error correction codewords. */
function generatorPolynomial(degree: number): number[] {
  let polynomial = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array<number>(polynomial.length + 1).fill(0);
    for (let j = 0; j < polynomial.length; j++) {
      next[j] ^= polynomial[j];
      next[j + 1] ^= gfMultiply(polynomial[j], fields().exp[i]);
    }
    polynomial = next;
  }
  return polynomial;
}

/** Remainder of `data` divided by the generator polynomial. */
function reedSolomon(data: number[], ecCount: number): number[] {
  const generator = generatorPolynomial(ecCount);
  const remainder = new Array<number>(ecCount).fill(0);
  for (const byte of data) {
    const factor = byte ^ remainder[0];
    remainder.shift();
    remainder.push(0);
    for (let i = 0; i < ecCount; i++) {
      remainder[i] ^= gfMultiply(generator[i + 1], factor);
    }
  }
  return remainder;
}

// ------------------------------------------------------------------ bit buffer

class BitBuffer {
  readonly bits: number[] = [];

  put(value: number, length: number): void {
    for (let i = length - 1; i >= 0; i--) {
      this.bits.push((value >>> i) & 1);
    }
  }

  get length(): number {
    return this.bits.length;
  }

  toCodewords(): number[] {
    const codewords: number[] = [];
    for (let i = 0; i < this.bits.length; i += 8) {
      let byte = 0;
      for (let j = 0; j < 8; j++) byte = (byte << 1) | (this.bits[i + j] ?? 0);
      codewords.push(byte);
    }
    return codewords;
  }
}

// ---------------------------------------------------------------------- matrix

class Matrix {
  readonly size: number;
  readonly modules: boolean[][];
  readonly isFunction: boolean[][];

  constructor(size: number) {
    this.size = size;
    this.modules = Array.from({ length: size }, () => new Array<boolean>(size).fill(false));
    this.isFunction = Array.from({ length: size }, () => new Array<boolean>(size).fill(false));
  }

  set(x: number, y: number, dark: boolean): void {
    if (x < 0 || y < 0 || x >= this.size || y >= this.size) return;
    this.modules[y][x] = dark;
    this.isFunction[y][x] = true;
  }

  get(x: number, y: number): boolean {
    return this.modules[y][x];
  }
}

function drawFinder(matrix: Matrix, centreX: number, centreY: number): void {
  for (let dy = -4; dy <= 4; dy++) {
    for (let dx = -4; dx <= 4; dx++) {
      const distance = Math.max(Math.abs(dx), Math.abs(dy));
      matrix.set(centreX + dx, centreY + dy, distance !== 2 && distance !== 4);
    }
  }
}

function drawAlignment(matrix: Matrix, centreX: number, centreY: number): void {
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      matrix.set(centreX + dx, centreY + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }
}

function drawFunctionPatterns(matrix: Matrix, spec: VersionSpec): void {
  const size = matrix.size;

  // Timing patterns first: the finders are drawn over their ends.
  for (let i = 0; i < size; i++) {
    matrix.set(6, i, i % 2 === 0);
    matrix.set(i, 6, i % 2 === 0);
  }

  drawFinder(matrix, 3, 3);
  drawFinder(matrix, size - 4, 3);
  drawFinder(matrix, 3, size - 4);

  const positions = spec.align;
  for (const y of positions) {
    for (const x of positions) {
      const corner =
        (x === 6 && y === 6) ||
        (x === 6 && y === positions[positions.length - 1]) ||
        (x === positions[positions.length - 1] && y === 6);
      if (!corner) drawAlignment(matrix, x, y);
    }
  }

  // Reserve the format information areas (their values are written per mask).
  const formatArea: Array<[number, number]> = [];
  for (let i = 0; i <= 5; i++) formatArea.push([8, i]);
  formatArea.push([8, 7], [8, 8], [7, 8]);
  for (let i = 9; i < 15; i++) formatArea.push([14 - i, 8]);
  for (let i = 0; i < 8; i++) formatArea.push([size - 1 - i, 8]);
  for (let i = 8; i < 15; i++) formatArea.push([8, size - 15 + i]);
  for (const [x, y] of formatArea) matrix.set(x, y, false);

  if (spec.version >= 7) {
    let remainder = spec.version;
    for (let i = 0; i < 12; i++) {
      remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25);
    }
    const bits = (spec.version << 12) | remainder;
    for (let i = 0; i < 18; i++) {
      const bit = ((bits >>> i) & 1) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      matrix.set(a, b, bit);
      matrix.set(b, a, bit);
    }
  }
}

function drawFormatBits(matrix: Matrix, mask: number): void {
  let data = (ECC_BITS_M << 3) | mask;
  let remainder = data;
  for (let i = 0; i < 10; i++) remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
  const bits = ((data << 10) | remainder) ^ 0x5412;
  const size = matrix.size;
  const bit = (index: number): boolean => ((bits >>> index) & 1) === 1;

  for (let i = 0; i <= 5; i++) matrix.set(8, i, bit(i));
  matrix.set(8, 7, bit(6));
  matrix.set(8, 8, bit(7));
  matrix.set(7, 8, bit(8));
  for (let i = 9; i < 15; i++) matrix.set(14 - i, 8, bit(i));

  for (let i = 0; i < 8; i++) matrix.set(size - 1 - i, 8, bit(i));
  for (let i = 8; i < 15; i++) matrix.set(8, size - 15 + i, bit(i));
  matrix.set(8, size - 8, true); // the always-dark module
}

function maskCondition(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0:
      return (x + y) % 2 === 0;
    case 1:
      return y % 2 === 0;
    case 2:
      return x % 3 === 0;
    case 3:
      return (x + y) % 3 === 0;
    case 4:
      return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0;
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
    default:
      return ((((x + y) % 2) + ((x * y) % 3)) % 2) === 0;
  }
}

function penalty(matrix: Matrix): number {
  const size = matrix.size;
  let score = 0;

  // Rule 1 — runs of five or more modules of one colour.
  const runScore = (run: number): number => (run >= 5 ? 3 + (run - 5) : 0);
  for (let y = 0; y < size; y++) {
    let run = 1;
    for (let x = 1; x < size; x++) {
      if (matrix.get(x, y) === matrix.get(x - 1, y)) {
        run++;
      } else {
        score += runScore(run);
        run = 1;
      }
    }
    score += runScore(run);
  }
  for (let x = 0; x < size; x++) {
    let run = 1;
    for (let y = 1; y < size; y++) {
      if (matrix.get(x, y) === matrix.get(x, y - 1)) {
        run++;
      } else {
        score += runScore(run);
        run = 1;
      }
    }
    score += runScore(run);
  }

  // Rule 2 — 2×2 blocks of one colour.
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const colour = matrix.get(x, y);
      if (
        colour === matrix.get(x + 1, y) &&
        colour === matrix.get(x, y + 1) &&
        colour === matrix.get(x + 1, y + 1)
      ) {
        score += 3;
      }
    }
  }

  // Rule 3 — finder-like patterns in either orientation.
  const pattern = [true, false, true, true, true, false, true, false, false, false, false];
  const reverse = [...pattern].reverse();
  const matchesAt = (get: (index: number) => boolean, start: number, needle: boolean[]): boolean => {
    for (let i = 0; i < needle.length; i++) {
      if (get(start + i) !== needle[i]) return false;
    }
    return true;
  };
  for (let y = 0; y < size; y++) {
    for (let x = 0; x + pattern.length <= size; x++) {
      const row = (index: number): boolean => matrix.get(index, y);
      if (matchesAt(row, x, pattern) || matchesAt(row, x, reverse)) score += 40;
    }
  }
  for (let x = 0; x < size; x++) {
    for (let y = 0; y + pattern.length <= size; y++) {
      const column = (index: number): boolean => matrix.get(x, index);
      if (matchesAt(column, y, pattern) || matchesAt(column, y, reverse)) score += 40;
    }
  }

  // Rule 4 — deviation from a 50/50 balance of dark modules.
  let dark = 0;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (matrix.get(x, y)) dark++;
    }
  }
  const total = size * size;
  const percent = (dark * 100) / total;
  score += Math.floor(Math.abs(percent - 50) / 5) * 10;

  return score;
}

// ------------------------------------------------------------------- encoder

/** Splits the data codewords into blocks, appends EC codes and interleaves them. */
function interleave(codewords: number[], spec: VersionSpec): number[] {
  const numBlocks = spec.blocks;
  const shortBlockLength = Math.floor(spec.dataCodewords / numBlocks);
  const numLongBlocks = spec.dataCodewords % numBlocks;
  const ecLength = spec.ecPerBlock;

  const dataBlocks: number[][] = [];
  const ecBlocks: number[][] = [];
  let offset = 0;
  for (let i = 0; i < numBlocks; i++) {
    const length = shortBlockLength + (i >= numBlocks - numLongBlocks ? 1 : 0);
    const block = codewords.slice(offset, offset + length);
    offset += length;
    dataBlocks.push(block);
    ecBlocks.push(reedSolomon(block, ecLength));
  }

  const result: number[] = [];
  for (let i = 0; i < shortBlockLength + 1; i++) {
    for (let b = 0; b < numBlocks; b++) {
      if (i < dataBlocks[b].length) result.push(dataBlocks[b][i]);
    }
  }
  for (let i = 0; i < ecLength; i++) {
    for (let b = 0; b < numBlocks; b++) result.push(ecBlocks[b][i]);
  }
  return result;
}

function utf8Bytes(text: string): number[] {
  return Array.from(new TextEncoder().encode(text));
}

function chooseSpec(byteLength: number): VersionSpec {
  for (const spec of SPECS) {
    const countBits = spec.version >= 10 ? 16 : 8;
    const capacityBits = spec.dataCodewords * 8;
    const neededBits = 4 + countBits + byteLength * 8;
    if (neededBits <= capacityBits) return spec;
  }
  throw new Error(
    `QR payload of ${byteLength} bytes needs a version above ${MAX_VERSION.version} ` +
      `(this encoder handles versions ${MIN_VERSION.version}–${MAX_VERSION.version} at ECC level M) — shorten the link.`,
  );
}

function buildCodewords(text: string, spec: VersionSpec): number[] {
  const bytes = utf8Bytes(text);
  const countBits = spec.version >= 10 ? 16 : 8;
  const buffer = new BitBuffer();
  buffer.put(0b0100, 4); // byte mode
  buffer.put(bytes.length, countBits);
  for (const byte of bytes) buffer.put(byte, 8);

  const capacityBits = spec.dataCodewords * 8;
  const terminator = Math.min(4, capacityBits - buffer.length);
  buffer.put(0, terminator);
  while (buffer.length % 8 !== 0) buffer.bits.push(0);

  const codewords = buffer.toCodewords();
  const padBytes = [0xec, 0x11];
  let padIndex = 0;
  while (codewords.length < spec.dataCodewords) {
    codewords.push(padBytes[padIndex % 2]);
    padIndex++;
  }
  return interleave(codewords, spec);
}

function drawCodewords(matrix: Matrix, codewords: number[]): void {
  const size = matrix.size;
  let bitIndex = 0;
  // The two-module-wide column bands walk right to left, skipping the vertical
  // timing column: the band that would start at column 6 is shifted one column
  // left, and the loop variable itself must carry that shift or every later
  // band lands in the wrong columns.
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vertical = 0; vertical < size; vertical++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - vertical : vertical;
        if (matrix.isFunction[y][x]) continue;
        if (bitIndex < codewords.length * 8) {
          const byte = codewords[bitIndex >>> 3];
          matrix.modules[y][x] = ((byte >>> (7 - (bitIndex & 7))) & 1) === 1;
          bitIndex++;
        }
      }
    }
  }
}

export interface QrMatrix {
  size: number;
  /** `modules[y][x]` — true where the module is dark. */
  modules: boolean[][];
  version: number;
  /** The mask the penalty rules chose (0–7). */
  mask: number;
}

export interface QrOptions {
  /** Forces a mask instead of scoring all eight; used by the encoder's tests. */
  mask?: number;
}

function buildMasked(text: string, spec: VersionSpec, mask: number): Matrix {
  const size = spec.version * 4 + 17;
  const codewords = buildCodewords(text, spec);
  const matrix = new Matrix(size);
  drawFunctionPatterns(matrix, spec);
  drawFormatBits(matrix, mask);
  drawCodewords(matrix, codewords);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (!matrix.isFunction[y][x] && maskCondition(mask, x, y)) {
        matrix.modules[y][x] = !matrix.modules[y][x];
      }
    }
  }
  return matrix;
}

/** Encodes `text` and returns the final, masked module matrix. */
export function qrMatrix(text: string, options: QrOptions = {}): QrMatrix {
  const spec = chooseSpec(utf8Bytes(text).length);
  const size = spec.version * 4 + 17;

  if (options.mask !== undefined) {
    const matrix = buildMasked(text, spec, options.mask);
    return { size, modules: matrix.modules, version: spec.version, mask: options.mask };
  }

  let best: { matrix: Matrix; penalty: number; mask: number } | null = null;
  for (let mask = 0; mask < 8; mask++) {
    const matrix = buildMasked(text, spec, mask);
    const score = penalty(matrix);
    if (best === null || score < best.penalty) best = { matrix, penalty: score, mask };
  }

  const chosen = best as { matrix: Matrix; penalty: number; mask: number };
  return { size, modules: chosen.matrix.modules, version: spec.version, mask: chosen.mask };
}

/**
 * Renders the code as a single-path SVG. One path keeps the page small: a
 * 45×45 code becomes a few hundred bytes, not 2025 rectangles.
 */
export function qrSvg(
  text: string,
  options: { moduleSize?: number; quietZone?: number; dark?: string; light?: string } = {},
): string {
  const moduleSize = options.moduleSize ?? 4;
  const quiet = options.quietZone ?? 4;
  const dark = options.dark ?? "#14261B";
  const light = options.light ?? "#FFFFFF";
  const { size, modules } = qrMatrix(text);
  const dimension = (size + quiet * 2) * moduleSize;

  const segments: string[] = [];
  for (let y = 0; y < size; y++) {
    let x = 0;
    while (x < size) {
      if (!modules[y][x]) {
        x++;
        continue;
      }
      let run = 1;
      while (x + run < size && modules[y][x + run]) run++;
      const px = (x + quiet) * moduleSize;
      const py = (y + quiet) * moduleSize;
      segments.push(`M${px} ${py}h${run * moduleSize}v${moduleSize}h${-run * moduleSize}z`);
      x += run;
    }
  }

  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${dimension}" height="${dimension}" ` +
    `viewBox="0 0 ${dimension} ${dimension}" role="img" aria-label="QR code">` +
    `<rect width="${dimension}" height="${dimension}" fill="${light}"/>` +
    `<path d="${segments.join("")}" fill="${dark}"/></svg>`
  );
}
