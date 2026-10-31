/**
 * The KV gateway — every Cloudflare KV operation in this Worker goes through here.
 *
 * Why this file exists
 * --------------------
 * KV is billed per operation and the free tier is small: 100,000 reads, 1,000
 * writes and 1,000 deletes **per day**, for the whole account. A storefront that
 * reads its OAuth token, its menu snapshot and its rate-limit counter straight
 * from KV on every request spends three reads and two writes per page view —
 * which turns a 1,000-write day into roughly 500 page views, and then every
 * write fails with 429 while the site is still up and still busy.
 *
 * The gateway keeps the same KV namespace and the same keys, and changes only
 * *how often* it is touched:
 *
 *   · **Isolate memory first.** A Worker isolate lives for minutes and serves
 *     hundreds of requests. A value that is fresh for 60 seconds is read from KV
 *     once per isolate per minute instead of once per request.
 *   · **Single-flight.** When a hundred requests arrive in the same millisecond
 *     and the cache is empty, one of them goes to KV (or to Firestore) and the
 *     other ninety-nine wait for that promise. No thundering herd, no duplicate
 *     writes.
 *   · **Coalesced writes.** Writing the same value twice inside its own TTL is
 *     one write, not two.
 *   · **A write brake.** Optional writes — rate-limit counters, caches that can
 *     be rebuilt — are refused once an isolate passes its per-minute budget, so
 *     a traffic spike or a bug cannot spend the account's daily write allowance
 *     in an hour. Writes marked `required` (alert state, release metadata, the
 *     cleanup backlog) are never refused: correctness is not optional.
 *   · **Counters.** Every read, write, delete, memo hit and refused write is
 *     counted, and `/healthz` prints them, so "are we near the limit?" is a
 *     question with an answer instead of a guess.
 *
 * What this is not: a second source of truth. KV still holds the value that
 * matters across isolates and across deploys; memory only holds a copy that is
 * provably younger than the TTL its own writer chose. Anything that must be
 * globally exact at this instant (an alert cursor, a chat throttle) says so with
 * `required: true` and bypasses the memory tier.
 */

/** The slice of `KVNamespace` this Worker uses. Tests hand in a Map-backed fake. */
export interface KvBinding {
  get(key: string, type?: "text" | "json"): Promise<unknown>;
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
  delete(key: string): Promise<void>;
}

interface MemoEntry {
  value: unknown;
  /** Epoch millis. `0` means "no expiry inside this isolate". */
  expiresAt: number;
  /** The raw string, kept so a coalesced write can be recognised as a duplicate. */
  raw: string;
  /** `true` once KV itself holds `raw`. Only then is a repeat write a duplicate. */
  persisted: boolean;
}

interface IsolateKvStore {
  entries: Map<string, MemoEntry>;
  inflight: Map<string, Promise<unknown>>;
  /** Per-minute optional-write brake. */
  writeWindowStart: number;
  writeWindowCount: number;
}

/**
 * One store per KV binding, held weakly.
 *
 * In production there is exactly one binding per isolate, so this is one store.
 * Keying it on the binding object rather than on a module-level Map is what makes
 * the test suite honest: each test builds its own fake KV, and a cache from one
 * test can never answer a question in the next.
 */
const stores = new WeakMap<object, IsolateKvStore>();

function storeFor(binding: KvBinding): IsolateKvStore {
  const existing = stores.get(binding);
  if (existing) return existing;
  const created: IsolateKvStore = {
    entries: new Map(),
    inflight: new Map(),
    writeWindowStart: 0,
    writeWindowCount: 0,
  };
  stores.set(binding, created);
  return created;
}

/** Hard bound on the memo, so a long-lived isolate cannot grow without limit. */
const MAX_MEMO_ENTRIES = 400;
/** Oldest entries are dropped first when the memo is full. */
function evictIfNeeded(store: IsolateKvStore): void {
  if (store.entries.size <= MAX_MEMO_ENTRIES) return;
  const drop = store.entries.size - MAX_MEMO_ENTRIES;
  let removed = 0;
  for (const key of store.entries.keys()) {
    if (removed >= drop) break;
    store.entries.delete(key);
    removed += 1;
  }
}

// ------------------------------------------------------------------- counters

export interface KvUsage {
  reads: number;
  writes: number;
  deletes: number;
  /** Reads answered from isolate memory: KV operations that did not happen. */
  memoHits: number;
  /** Writes skipped because the value was already the newest one in memory. */
  coalescedWrites: number;
  /** Writes skipped by the per-minute brake. Always an *optional* write. */
  refusedWrites: number;
  /** KV calls that threw. The gateway degrades; it never turns into a 500. */
  failures: number;
}

const usage: KvUsage = {
  reads: 0,
  writes: 0,
  deletes: 0,
  memoHits: 0,
  coalescedWrites: 0,
  refusedWrites: 0,
  failures: 0,
};

/** The counters, for `/healthz` and the diagnostics page. A copy, never a reference. */
export function kvUsage(): KvUsage {
  return { ...usage };
}

/**
 * How many KV operations this Worker saves per 1,000 memo-eligible requests.
 * Printed by `/healthz` because a saving that cannot be measured is a rumour.
 */
export function kvSavings(): { avoidedReads: number; avoidedWrites: number; memoHitRate: number } {
  const total = usage.reads + usage.memoHits;
  return {
    avoidedReads: usage.memoHits,
    avoidedWrites: usage.coalescedWrites + usage.refusedWrites,
    memoHitRate: total === 0 ? 0 : Math.round((usage.memoHits / total) * 1000) / 10,
  };
}

/** Tests and the diagnostics route: start the counts again. */
export function resetKvUsage(): void {
  usage.reads = 0;
  usage.writes = 0;
  usage.deletes = 0;
  usage.memoHits = 0;
  usage.coalescedWrites = 0;
  usage.refusedWrites = 0;
  usage.failures = 0;
}

/** Drops one binding's isolate memory (a deploy hook, and a test helper). */
export function forgetKvMemo(binding: KvBinding, key?: string): void {
  const store = stores.get(binding);
  if (!store) return;
  if (key === undefined) {
    store.entries.clear();
    return;
  }
  store.entries.delete(key);
}

// ------------------------------------------------------------------ the brake

/**
 * Optional KV writes per isolate per minute.
 *
 * The daily allowance is 1,000 writes. An isolate that lives ten minutes and
 * spends four optional writes a minute costs 40 — and a deployment with dozens of
 * busy isolates stays inside the day. Required writes are outside this budget:
 * the kitchen's alert state is never the thing that gets dropped to save a
 * fraction of a cent.
 */
export const OPTIONAL_WRITES_PER_MINUTE = 4;
const WRITE_WINDOW_MS = 60_000;

function optionalWriteAllowed(store: IsolateKvStore, now: number): boolean {
  if (now - store.writeWindowStart >= WRITE_WINDOW_MS) {
    store.writeWindowStart = now;
    store.writeWindowCount = 0;
  }
  if (store.writeWindowCount >= OPTIONAL_WRITES_PER_MINUTE) return false;
  store.writeWindowCount += 1;
  return true;
}

// -------------------------------------------------------------------- reading

export interface MemoGetOptions {
  /** How long an answer stays valid inside this isolate. 0 disables the memo. */
  ttlMs: number;
  /** Ask KV for parsed JSON instead of text. */
  json?: boolean;
  /**
   * Cache a miss too. Worth it for a key that is usually absent and cheap to be
   * wrong about for a few seconds (a "did we already remind them?" flag); never
   * worth it for a credential, where a cached miss means minting nothing.
   */
  cacheNulls?: boolean;
}

/**
 * Reads a key: isolate memory first, then KV, then nothing.
 *
 * Concurrent reads of the same cold key share one KV call. A KV failure is a
 * `null` answer, not an exception — every caller already has a "what if the
 * cache is empty" path, and that path is the correct one.
 */
export async function memoGet<T>(binding: KvBinding | null | undefined, key: string, options: MemoGetOptions): Promise<T | null> {
  if (!binding) return null;
  const store = storeFor(binding);
  const now = Date.now();

  if (options.ttlMs > 0) {
    const entry = store.entries.get(key);
    if (entry && (entry.expiresAt === 0 || entry.expiresAt > now)) {
      usage.memoHits += 1;
      return entry.value as T | null;
    }
    if (entry) store.entries.delete(key);
  }

  const inflight = store.inflight.get(key);
  if (inflight) return (await inflight) as T | null;

  const read = (async (): Promise<unknown> => {
    try {
      usage.reads += 1;
      return await binding.get(key, options.json ? "json" : "text");
    } catch {
      usage.failures += 1;
      return null;
    }
  })();

  if (options.ttlMs > 0) store.inflight.set(key, read);
  try {
    const value = (await read) as T | null;
    if (options.ttlMs > 0 && (value !== null || options.cacheNulls)) {
      store.entries.set(key, {
        value,
        expiresAt: now + options.ttlMs,
        raw: typeof value === "string" ? value : JSON.stringify(value ?? null),
        // A value read *from* KV is by definition in KV, so writing it straight
        // back is the duplicate `memoPut` refuses to spend an operation on.
        persisted: true,
      });
      evictIfNeeded(store);
    }
    return value ?? null;
  } finally {
    store.inflight.delete(key);
  }
}

// -------------------------------------------------------------------- writing

export interface MemoPutOptions {
  /** TTL handed to KV. Cloudflare's minimum is 60 seconds. */
  expirationTtl?: number;
  /** How long the written value is trusted inside this isolate. */
  memoTtlMs?: number;
  /**
   * `true` for a write that must reach KV now: alert cursors, release metadata,
   * the cleanup backlog, a throttle another isolate has to see this second.
   * `false` (the default) for a cache that can simply be rebuilt.
   */
  required?: boolean;
  /**
   * What the isolate should remember, when it is not the string KV gets. A JSON
   * cache writes text and reads objects; without this the memo would hand back
   * the text and every caller would think its own snapshot had gone stale.
   */
  memoValue?: unknown;
}

/**
 * Writes a key, and remembers it.
 *
 * Returns `true` when KV holds the value, `false` when the write was coalesced or
 * refused by the brake. Callers that must know — a release webhook that has to
 * publish — check it; caches do not.
 */
export async function memoPut(
  binding: KvBinding | null | undefined,
  key: string,
  value: string,
  options: MemoPutOptions = {},
): Promise<boolean> {
  if (!binding) return false;
  const store = storeFor(binding);
  const now = Date.now();
  const memoTtlMs = options.memoTtlMs ?? (options.expirationTtl ? options.expirationTtl * 1000 : 0);

  // Writing what KV already holds is a spent write for no new information. The
  // comparison is against the last value this isolate *knows* reached KV, within
  // its own TTL — a duplicate here is a duplicate there.
  const remembered = options.memoValue === undefined ? value : options.memoValue;
  const previous = store.entries.get(key);
  const duplicate =
    !options.required && !!previous && previous.persisted && previous.raw === value && previous.expiresAt > now;
  if (duplicate) {
    usage.coalescedWrites += 1;
    return false;
  }
  if (!options.required && !optionalWriteAllowed(store, now)) {
    usage.refusedWrites += 1;
    // Still trusted locally: the next read in this isolate gets the new value,
    // and the next one in another isolate gets the older one from KV. For a
    // cache that is a delay of seconds, not a wrong answer.
    store.entries.set(key, { value: remembered, expiresAt: memoTtlMs > 0 ? now + memoTtlMs : 0, raw: value, persisted: false });
    evictIfNeeded(store);
    return false;
  }

  try {
    usage.writes += 1;
    await binding.put(key, value, options.expirationTtl ? { expirationTtl: options.expirationTtl } : undefined);
    store.entries.set(key, { value: remembered, expiresAt: memoTtlMs > 0 ? now + memoTtlMs : 0, raw: value, persisted: true });
    evictIfNeeded(store);
    return true;
  } catch {
    usage.failures += 1;
    store.entries.set(key, { value: remembered, expiresAt: memoTtlMs > 0 ? now + memoTtlMs : 0, raw: value, persisted: false });
    evictIfNeeded(store);
    return false;
  }
}

export interface MemoDeleteOptions {
  /** Delete through the brake. A cursor another isolate must not read is required. */
  required?: boolean;
  /**
   * What the caller already knows about the key. `false` skips the KV call:
   * a delete is billed like a write, and a cron that "resets" a key every minute
   * whether or not it was ever set spends 1,440 of the free 1,000-a-day
   * allowance on nothing. The knowledge is the caller's, not the memo's — the
   * caller is the one that just read the key.
   */
  present?: boolean;
}

/** Deletes a key and forgets it locally. */
export async function memoDelete(
  binding: KvBinding | null | undefined,
  key: string,
  options: MemoDeleteOptions = {},
): Promise<boolean> {
  if (!binding) return false;
  const store = storeFor(binding);
  store.entries.delete(key);
  if (options.present === false) return false;
  if (!options.required && !optionalWriteAllowed(store, Date.now())) {
    usage.refusedWrites += 1;
    return false;
  }
  try {
    usage.deletes += 1;
    await binding.delete(key);
    return true;
  } catch {
    usage.failures += 1;
    return false;
  }
}

// ---------------------------------------------------------------- single-flight

const flights = new WeakMap<object, Map<string, Promise<unknown>>>();

/**
 * Runs one asynchronous job per key, sharing the result with everyone who asks
 * while it is in flight.
 *
 * This is the guard that keeps an expired cache from becoming a stampede: at the
 * top of a minute, fifty requests for the menu would otherwise issue fifty
 * seven-document Firestore reads and fifty KV writes. They now issue one.
 */
export async function singleFlight<T>(binding: object, key: string, job: () => Promise<T>): Promise<T> {
  let map = flights.get(binding);
  if (!map) {
    map = new Map();
    flights.set(binding, map);
  }
  const existing = map.get(key);
  if (existing) return (await existing) as T;

  const started = (async (): Promise<unknown> => {
    try {
      return await job();
    } finally {
      map?.delete(key);
    }
  })();
  map.set(key, started);
  return (await started) as T;
}
