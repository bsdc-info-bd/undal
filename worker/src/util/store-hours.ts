/**
 * Store open/closed logic shared by the web store, the web checkout and the
 * presence cron. `app/…/core/model/StoreHours.kt` is the Kotlin twin, and the
 * two are held to the same vectors by `worker/test/store-hours.test.ts` and
 * `StoreHoursTest.kt`.
 *
 * Asia/Dhaka is UTC+6 all year round (Bangladesh has no daylight saving), so the
 * local calendar can be derived arithmetically — no timezone database needed on
 * the edge.
 *
 * **The schedule is the authority on *when*; the owner's switch decides
 * *whether*.** A store is taking orders when, and only when,
 *
 *   the switch is on  ∧  the watchdog has not paused it  ∧  the hours cover now.
 *
 * The switch no longer opens a store outside its hours. It used to, and that is
 * how a kitchen that published 09:00–22:00 came to take orders at 3 a.m.: the
 * owner opened once, nothing ever closed it, and every surface read "Open". Now
 * an owner who leaves the switch on gets a store that opens and closes itself on
 * the schedule, every day; turning it off pauses the store until it is turned
 * back on.
 *
 * Two fields carry that, and both are documented on [StoreConfig]:
 * `acceptingOrders` is the owner's switch, and `open` stays what every older
 * build reads — the effective state, kept in step with the schedule by the
 * presence cron (`syncedOpenFlag`).
 */
export interface StoreSlot {
  open: string;
  close: string;
}

export interface SpecialDate {
  date: string;
  /** What the Android app writes: `true` for a day off. */
  closed?: boolean;
  /** The older spelling of the same thing, still honoured. */
  mode?: "closed" | "custom";
  slots?: StoreSlot[];
  note?: string;
}

export interface StoreConfig {
  storeName?: string;
  /**
   * The effective state — "taking orders right now" — and the only field builds
   * older than the schedule-aware ones read. Kept in step with the schedule by
   * the presence cron, so those builds stop selling outside the hours too.
   */
  open?: boolean;
  /**
   * The owner's switch: on means "take orders whenever my hours say so", off
   * means "paused until I say otherwise". Absent on a document no schedule-aware
   * writer has touched yet, in which case `open` is the switch.
   */
  acceptingOrders?: boolean;
  /**
   * The value of `open` the last schedule-aware writer set. When `open` differs
   * from it, something that predates the schedule wrote `open` (an older vendor
   * build's Open/Close button) and that write is the owner's latest word.
   */
  openSynced?: boolean;
  autoClosed?: boolean;
  autoClosedReason?: string;
  /** When the watchdog paused the store (ISO string once Firestore reads it back). */
  autoClosedAt?: string | number;
  /** When the watchdog undid that pause by itself. */
  autoRestoredAt?: string | number;
  /** How long the device had been silent when the pause was applied, in minutes. */
  autoClosedSilentMinutes?: number;
  manualOverrideNote?: string;
  weekly?: Record<string, StoreSlot[]>;
  specialDates?: SpecialDate[];
  deliveryPerKmAmountPaisa?: number;
  radiusKm?: number;
  minOrderAmountPaisa?: number;
  storeLat?: number;
  storeLng?: number;
  storeAddressText?: string;
  bannerUrl?: string;
  logoUrl?: string;
}

const DHAKA_OFFSET_MINUTES = 6 * 60;
const DAY_MS = 24 * 60 * 60 * 1000;
const WEEKDAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const;
const WEEKDAY_NAMES: Record<string, string> = {
  sun: "Sunday",
  mon: "Monday",
  tue: "Tuesday",
  wed: "Wednesday",
  thu: "Thursday",
  fri: "Friday",
  sat: "Saturday",
};

/** Minutes in a day: the closing minute of a window that runs to midnight. */
export const END_OF_DAY = 24 * 60;

export interface DhakaClock {
  weekday: string;
  dateKey: string;
  minutes: number;
  label: string;
}

export function dhakaClock(date: Date = new Date()): DhakaClock {
  const shifted = new Date(date.getTime() + DHAKA_OFFSET_MINUTES * 60_000);
  const weekday = WEEKDAY_KEYS[shifted.getUTCDay()] ?? "sun";
  const hours = shifted.getUTCHours();
  const minutes = shifted.getUTCMinutes();
  return {
    weekday,
    dateKey: shifted.toISOString().slice(0, 10),
    minutes: hours * 60 + minutes,
    label: `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`,
  };
}

/** Bengali, Devanagari, Arabic-Indic and full-width digits, as ASCII. */
function asciiDigits(text: string): string {
  return text.replace(/[\u0660-\u0669\u06F0-\u06F9\u0966-\u096F\u09E6-\u09EF\uFF10-\uFF19]/g, (digit) => {
    const code = digit.charCodeAt(0);
    for (const base of [0x0660, 0x06f0, 0x0966, 0x09e6, 0xff10]) {
      if (code >= base && code <= base + 9) return String(code - base);
    }
    return digit;
  });
}

const CLOCK_TEXT = /^(\d{1,2}):(\d{2})$/;

/**
 * "09:00", "9:00" or "০৯:০০" → minutes since midnight; `null` for anything that
 * is not a clock time. "24:00" is accepted and means the end of the day.
 *
 * Strict on purpose: a time nobody can read must not quietly become midnight
 * (the old reader answered 0 for garbage, which is a slot that opens at 00:00).
 */
export function clockMinutes(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const match = CLOCK_TEXT.exec(asciiDigits(value.trim()));
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (minutes > 59) return null;
  if (hours === 24 && minutes === 0) return END_OF_DAY;
  if (hours > 23) return null;
  return hours * 60 + minutes;
}

/** Kept for callers that want a number whatever happens: unreadable is 0. */
export function parseHhMm(value: string): number {
  const minutes = clockMinutes(value);
  if (minutes === null) return 0;
  return Math.max(0, Math.min(1439, minutes));
}

/**
 * One slot as minutes: `open` in 0…1439, `close` in 1…1440. A close of "00:00",
 * "23:59" or "24:00" means "until midnight" — the 24/7 template writes 23:59,
 * and a store must not close for the last minute of every day because of it.
 * `close < open` is a window that crosses midnight (20:00 → 01:00).
 */
export function slotWindow(slot: StoreSlot | null | undefined): { open: number; close: number } | null {
  if (!slot || typeof slot !== "object") return null;
  const open = clockMinutes(slot.open);
  const rawClose = clockMinutes(slot.close);
  if (open === null || rawClose === null || open >= END_OF_DAY) return null;
  if (rawClose === open) return null;
  const close = rawClose === 0 || rawClose === END_OF_DAY - 1 || rawClose === END_OF_DAY ? END_OF_DAY : rawClose;
  if (close === open) return null;
  return { open, close };
}

/** "fri", "Fri", "friday" and "FRIDAY" are all Friday. */
function weekdayOf(key: string): string {
  return key.trim().toLowerCase().slice(0, 3);
}

function weeklySlots(config: StoreConfig, weekday: string): StoreSlot[] {
  const weekly = config.weekly;
  if (!weekly || typeof weekly !== "object") return [];
  for (const [key, value] of Object.entries(weekly)) {
    if (weekdayOf(key) === weekday && Array.isArray(value)) return value;
  }
  return [];
}

function specialFor(config: StoreConfig, dateKey: string): SpecialDate | null {
  const specials = Array.isArray(config.specialDates) ? config.specialDates : [];
  return specials.find((entry) => entry && typeof entry === "object" && entry.date === dateKey) ?? null;
}

/** True for a special date that is a day off, in either spelling. */
export function isClosedSpecial(special: SpecialDate | null | undefined): boolean {
  return !!special && (special.closed === true || special.mode === "closed");
}

/**
 * The slots that apply on one Dhaka date: a day off has none, a special date
 * with hours has its own, and everything else — including a special date marked
 * open with no hours, which the schedule builder tells the owner "behaves like a
 * normal day" — has the weekly ones.
 */
export function slotsOn(config: StoreConfig, dateKey: string, weekday: string): StoreSlot[] {
  const special = specialFor(config, dateKey);
  if (special) {
    if (isClosedSpecial(special)) return [];
    const own = Array.isArray(special.slots) ? special.slots : [];
    if (own.length > 0) return own;
  }
  return weeklySlots(config, weekday);
}

/** Slots for today, honouring special-date overrides. */
export function slotsForDate(config: StoreConfig, clock: DhakaClock): StoreSlot[] {
  return slotsOn(config, clock.dateKey, clock.weekday);
}

/**
 * A minute against one window, read as a circle: 20:00 → 01:00 contains 00:30.
 * Kept for callers that think of a slot on its own; the store's own decision is
 * [isOpenAt], which knows that 00:30 belongs to *yesterday's* 20:00 slot.
 */
export function isWithinSlot(slot: StoreSlot, minutes: number): boolean {
  const window = slotWindow(slot);
  if (!window) return false;
  if (window.close > window.open) return minutes >= window.open && minutes < window.close;
  return minutes >= window.open || minutes < window.close;
}

export function isWithinAnySlot(slots: StoreSlot[], minutes: number): boolean {
  return slots.some((slot) => isWithinSlot(slot, minutes));
}

/**
 * Do the published hours cover this moment?
 *
 * Today's windows from their opening minute, plus the after-midnight tail of
 * *yesterday's* windows that cross midnight. So a Friday 20:00–01:00 slot is
 * open at 00:30 on Saturday even when Saturday is a day off, and a Saturday
 * 20:00–01:00 slot does not make 00:30 on Saturday open when Friday was a day off.
 */
export function isOpenAt(config: StoreConfig, now: Date = new Date()): boolean {
  const clock = dhakaClock(now);
  for (const slot of slotsOn(config, clock.dateKey, clock.weekday)) {
    const window = slotWindow(slot);
    if (!window) continue;
    if (window.close > window.open) {
      if (clock.minutes >= window.open && clock.minutes < window.close) return true;
    } else if (clock.minutes >= window.open) {
      return true;
    }
  }
  const yesterday = dhakaClock(new Date(now.getTime() - DAY_MS));
  for (const slot of slotsOn(config, yesterday.dateKey, yesterday.weekday)) {
    const window = slotWindow(slot);
    if (window && window.close < window.open && clock.minutes < window.close) return true;
  }
  return false;
}

/** "9:00 AM", the form the app prints with `TimeDhaka.prettyHhMm`. */
export function prettyMinutes(minutes: number): string {
  const wrapped = ((minutes % END_OF_DAY) + END_OF_DAY) % END_OF_DAY;
  const hours = Math.floor(wrapped / 60);
  const mins = wrapped % 60;
  const suffix = hours < 12 ? "AM" : "PM";
  const twelve = hours % 12 === 0 ? 12 : hours % 12;
  return `${twelve}:${String(mins).padStart(2, "0")} ${suffix}`;
}

export interface NextOpening {
  /** 0 today, 1 tomorrow, … up to 7. */
  dayOffset: number;
  /** Minutes since midnight on that day. */
  minutes: number;
  /** "today at 7:00 PM", "tomorrow at 9:00 AM", "on Saturday at 9:00 AM". */
  label: string;
}

/** The next time a window opens, searched over the coming week. */
export function nextOpening(config: StoreConfig, now: Date = new Date()): NextOpening | null {
  const clock = dhakaClock(now);
  for (let offset = 0; offset <= 7; offset += 1) {
    const day = offset === 0 ? clock : dhakaClock(new Date(now.getTime() + offset * DAY_MS));
    const opens = slotsOn(config, day.dateKey, day.weekday)
      .map((slot) => slotWindow(slot))
      .filter((window): window is { open: number; close: number } => window !== null)
      .map((window) => window.open)
      .filter((open) => offset > 0 || open > clock.minutes)
      .sort((left, right) => left - right);
    const first = opens[0];
    if (first === undefined) continue;
    const when = offset === 0 ? "today" : offset === 1 ? "tomorrow" : `on ${WEEKDAY_NAMES[day.weekday] ?? day.weekday}`;
    return { dayOffset: offset, minutes: first, label: `${when} at ${prettyMinutes(first)}` };
  }
  return null;
}

/** When the window that is open right now closes, as "10:00 PM", or "". */
export function closingLabel(config: StoreConfig, now: Date = new Date()): string {
  const clock = dhakaClock(now);
  for (const slot of slotsOn(config, clock.dateKey, clock.weekday)) {
    const window = slotWindow(slot);
    if (!window) continue;
    if (window.close > window.open && clock.minutes >= window.open && clock.minutes < window.close) {
      return window.close === END_OF_DAY ? "midnight" : prettyMinutes(window.close);
    }
    if (window.close < window.open && clock.minutes >= window.open) return prettyMinutes(window.close);
  }
  const yesterday = dhakaClock(new Date(now.getTime() - DAY_MS));
  for (const slot of slotsOn(config, yesterday.dateKey, yesterday.weekday)) {
    const window = slotWindow(slot);
    if (window && window.close < window.open && clock.minutes < window.close) return prettyMinutes(window.close);
  }
  return "";
}

/** True when any day of the week, or any special date, publishes a usable window. */
export function hasAnyHours(config: StoreConfig): boolean {
  const weekly = config.weekly && typeof config.weekly === "object" ? Object.values(config.weekly) : [];
  for (const slots of weekly) {
    if (Array.isArray(slots) && slots.some((slot) => slotWindow(slot) !== null)) return true;
  }
  const specials = Array.isArray(config.specialDates) ? config.specialDates : [];
  return specials.some(
    (special) => !isClosedSpecial(special) && Array.isArray(special?.slots) && special.slots.some((slot) => slotWindow(slot) !== null),
  );
}

/**
 * The owner's switch. `acceptingOrders` when a schedule-aware writer recorded
 * it — unless an older build has since written `open` by hand (it no longer
 * matches `openSynced`), in which case that newer, older-style word wins.
 */
export function switchOn(config: StoreConfig): boolean {
  const open = config.open === true;
  if (typeof config.acceptingOrders === "boolean") {
    // An absent `open` reads as false, exactly as the app's `StoreConfig` reads it.
    const legacyWrite = typeof config.openSynced === "boolean" && open !== config.openSynced;
    if (!legacyWrite) return config.acceptingOrders;
  }
  return open;
}

/** Is the store taking orders at this moment? The one rule every surface asks. */
export function isTakingOrders(config: StoreConfig | null, now: Date = new Date()): boolean {
  if (!config) return false;
  return switchOn(config) && config.autoClosed !== true && isOpenAt(config, now);
}

/**
 * The fields the presence cron writes so `open` — the flag older builds read —
 * says what the schedule says. Empty when nothing needs writing.
 */
export function syncedOpenFlag(config: StoreConfig, now: Date = new Date()): Record<string, boolean> {
  const intent = switchOn(config);
  const desired = intent && config.autoClosed !== true && isOpenAt(config, now);
  const patch: Record<string, boolean> = {};
  if (config.acceptingOrders !== intent) patch.acceptingOrders = intent;
  if (config.open !== desired) patch.open = desired;
  if (config.openSynced !== desired) patch.openSynced = desired;
  return patch;
}

export type StoreStatusReason = "open" | "setting-up" | "auto-closed" | "paused" | "outside-hours" | "no-hours";

export interface StoreStatus {
  openNow: boolean;
  headline: string;
  detail: string;
  todaySlots: StoreSlot[];
  autoClosed: boolean;
  /** Why it is open or closed, for a page that wants more than the headline. */
  reason: StoreStatusReason;
  /** "today at 7:00 PM", or "" when nothing opens in the coming week. */
  nextOpening: string;
}

export function storeStatus(config: StoreConfig | null, now: Date = new Date()): StoreStatus {
  const clock = dhakaClock(now);
  if (!config) {
    return {
      openNow: false,
      headline: "Kitchen setting up",
      detail: "Store configuration is being published right now.",
      todaySlots: [],
      autoClosed: false,
      reason: "setting-up",
      nextOpening: "",
    };
  }

  const todaySlots = slotsForDate(config, clock);
  const inside = isOpenAt(config, now);
  const autoClosed = config.autoClosed === true;
  const switchedOn = switchOn(config);
  const openNow = switchedOn && !autoClosed && inside;
  const next = nextOpening(config, now);
  const nextLabel = next?.label ?? "";
  const todayHours = todaySlots
    .filter((slot) => slotWindow(slot) !== null)
    .map((slot) => `${slot.open}–${slot.close}`)
    .join(", ");

  if (openNow) {
    const closes = closingLabel(config, now);
    return {
      openNow,
      headline: "Open now",
      detail: closes ? `Taking orders until ${closes}.` : `Our hours today are ${todayHours}.`,
      todaySlots,
      autoClosed,
      reason: "open",
      nextOpening: nextLabel,
    };
  }
  if (autoClosed) {
    return {
      openNow,
      headline: "Temporarily closed",
      detail: "The kitchen device went offline, so orders are paused. We reopen shortly.",
      todaySlots,
      autoClosed,
      reason: "auto-closed",
      nextOpening: nextLabel,
    };
  }
  if (!switchedOn) {
    return {
      openNow,
      headline: "Closed",
      detail: (config.manualOverrideNote ?? "").trim() || "The kitchen has paused new orders.",
      todaySlots,
      autoClosed,
      reason: "paused",
      nextOpening: nextLabel,
    };
  }
  if (!hasAnyHours(config)) {
    return {
      openNow,
      headline: "Closed",
      detail: "The kitchen has not published its opening hours yet.",
      todaySlots,
      autoClosed,
      reason: "no-hours",
      nextOpening: "",
    };
  }
  const special = specialFor(config, clock.dateKey);
  const dayOff = isClosedSpecial(special);
  const note = dayOff ? (special?.note ?? "").trim() : "";
  const opens = nextLabel ? `We open ${nextLabel}.` : "Please check back later.";
  return {
    openNow,
    headline: "Closed now",
    detail: dayOff ? `Closed today${note ? ` — ${note}` : ""}. ${opens}` : opens,
    todaySlots,
    autoClosed,
    reason: "outside-hours",
    nextOpening: nextLabel,
  };
}

/**
 * The refusal a checkout gives when the store is not taking orders: one
 * sentence, with the time it opens, so a closed kitchen is an answer and not a
 * dead end. "" when the store is open.
 */
export function closedSentence(config: StoreConfig | null, now: Date = new Date()): string {
  const status = storeStatus(config, now);
  if (status.openNow) return "";
  switch (status.reason) {
    case "auto-closed":
      return "The kitchen's phone is offline, so orders are paused for a few minutes. Your basket is saved — please try again shortly.";
    case "paused":
      return `The kitchen is not taking orders right now: ${status.detail} Your basket is saved.`;
    case "outside-hours":
      return status.nextOpening
        ? `The kitchen is closed right now — it opens ${status.nextOpening}. Your basket is saved until then.`
        : "The kitchen is closed right now. Your basket is saved.";
    case "no-hours":
      return "The kitchen has not published its opening hours yet, so it cannot take orders. Please call them.";
    default:
      return "The kitchen is not taking orders right now. Your basket is saved.";
  }
}
