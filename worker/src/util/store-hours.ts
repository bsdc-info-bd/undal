/**
 * Store open/closed logic shared by the web store and the presence cron.
 *
 * Asia/Dhaka is UTC+6 all year round (Bangladesh has no daylight saving), so the
 * local calendar can be derived arithmetically — no timezone database needed on
 * the edge.
 */
export interface StoreSlot {
  open: string;
  close: string;
}

export interface SpecialDate {
  date: string;
  mode: "closed" | "custom";
  slots?: StoreSlot[];
}

export interface StoreConfig {
  storeName?: string;
  open?: boolean;
  autoClosed?: boolean;
  autoClosedReason?: string;
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
const WEEKDAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const;

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

export function parseHhMm(value: string): number {
  const [hoursPart, minutesPart] = value.split(":");
  const hours = Number(hoursPart ?? 0);
  const minutes = Number(minutesPart ?? 0);
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return 0;
  return Math.max(0, Math.min(1439, hours * 60 + minutes));
}

/** Slots for today, honouring special-date overrides. */
export function slotsForDate(config: StoreConfig, clock: DhakaClock): StoreSlot[] {
  const special = (config.specialDates ?? []).find((entry) => entry.date === clock.dateKey);
  if (special) {
    if (special.mode === "closed") return [];
    return special.slots ?? [];
  }
  return config.weekly?.[clock.weekday] ?? [];
}

/** A window that ends before it starts (e.g. 20:00 → 01:00) crosses midnight. */
export function isWithinSlot(slot: StoreSlot, minutes: number): boolean {
  const open = parseHhMm(slot.open);
  const close = parseHhMm(slot.close);
  if (close === open) return false;
  if (close > open) return minutes >= open && minutes < close;
  return minutes >= open || minutes < close;
}

export function isWithinAnySlot(slots: StoreSlot[], minutes: number): boolean {
  return slots.some((slot) => isWithinSlot(slot, minutes));
}

export interface StoreStatus {
  openNow: boolean;
  headline: string;
  detail: string;
  todaySlots: StoreSlot[];
  autoClosed: boolean;
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
    };
  }

  const todaySlots = slotsForDate(config, clock);
  const scheduleAllows = isWithinAnySlot(todaySlots, clock.minutes);
  const autoClosed = config.autoClosed === true;
  const manualOpen = config.open === true;
  const openNow = manualOpen && scheduleAllows && !autoClosed;

  let headline = openNow ? "Open now" : "Closed";
  let detail = "";

  if (autoClosed) {
    headline = "Temporarily closed";
    detail = "The kitchen device went offline, so orders are paused. We reopen shortly.";
  } else if (!manualOpen && scheduleAllows) {
    detail = (config.manualOverrideNote ?? "").trim() || "The kitchen has paused new orders.";
  } else if (manualOpen && !scheduleAllows) {
    detail = todaySlots.length === 0
      ? "Closed today. Please check back tomorrow."
      : `Opens at ${todaySlots[0]?.open ?? ""}.`;
  } else {
    detail = todaySlots.length === 0
      ? "Closed today."
      : `Our hours today are ${todaySlots.map((slot) => `${slot.open}–${slot.close}`).join(", ")}.`;
  }

  return { openNow, headline, detail, todaySlots, autoClosed };
}
