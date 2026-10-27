/**
 * Menu reads for the web store, cached in KV.
 *
 * The catalogue is public (the rules allow anonymous reads) and changes a few
 * times a day, so the Worker keeps one merged snapshot in KV for a minute
 * instead of asking Firestore on every page view. A stale cache is still better
 * than a failed page: the checkout always re-reads live prices before it writes
 * an order, so nothing sold out can slip through on a cached page.
 */
import type { Env } from "../env";
import { fsGet, fsQuery, type FsRecord } from "../firestore";
import { buildCouponBoard, type CouponBoardEntry, type CouponDocument } from "./coupons";

export interface WebStoreSlot {
  open: string;
  close: string;
}

export interface WebStoreConfig {
  storeName: string;
  /** The effective flag older builds read; see `StoreConfig.open` in util/store-hours. */
  open: boolean;
  /** The owner's switch, when a schedule-aware writer recorded it. Absent ≠ false. */
  acceptingOrders?: boolean;
  /** Bookkeeping for `open`; see `StoreConfig.openSynced`. */
  openSynced?: boolean;
  autoClosed: boolean;
  manualOverrideNote: string;
  weekly?: Record<string, WebStoreSlot[]>;
  /** `closed: true` is what the app writes for a day off; `mode: "closed"` is the older spelling. */
  specialDates?: Array<{ date: string; closed?: boolean; mode?: "closed" | "custom"; slots?: WebStoreSlot[]; note?: string }>;
  deliveryPerKmAmountPaisa: number;
  radiusKm: number;
  minOrderAmountPaisa: number;
  storeLat: number;
  storeLng: number;
  storeAddressText: string;
  bannerUrl: string;
  logoUrl: string;
}

export interface WebDish {
  id: string;
  kind: "dish";
  name: string;
  description: string;
  amountPaisa: number;
  /** What the customer actually pays, after the dish discount. */
  payablePaisa: number;
  discountAmountPaisa: number;
  available: boolean;
  veg: boolean;
  spiceLevel: number;
  tags: string[];
  orderCount: number;
  categoryId: string;
  subcategoryId: string;
  imageUrl: string;
  prepMinutes: number;
  featured: boolean;
  /** Source lists a starting price; the kitchen must configure the exact option. */
}

export interface WebCombo {
  id: string;
  kind: "combo";
  name: string;
  description: string;
  amountPaisa: number;
  payablePaisa: number;
  /** Combos are bundles: no per-dish discount, but a saving against the parts. */
  discountAmountPaisa: number;
  regularAmountPaisa: number;
  available: boolean;
  imageUrl: string;
  itemRefs: Array<{ menuItemId: string; qty: number }>;
  featured: boolean;
}

export type WebItem = WebDish | WebCombo;

export interface WebSection {
  id: string;
  name: string;
  order: number;
  active: boolean;
  iconUrl: string;
}

export interface WebSubsection {
  id: string;
  categoryId: string;
  name: string;
  order: number;
  active: boolean;
}

export interface WebCampaign {
  id: string;
  title: string;
  subtitle: string;
  type: string;
  value: number;
  endAt: string;
  bannerUrl: string;
  code: string;
  active: boolean;
}

export interface WebMenu {
  fetchedAt: number;
  sections: WebSection[];
  subsections: WebSubsection[];
  dishes: WebDish[];
  combos: WebCombo[];
  campaigns: WebCampaign[];
  /**
   * The coupons the kitchen published for this storefront: live, automatic ones,
   * with their conditions and their banner text. This is the same board the
   * Android app reads (`config/couponBoard`), so a coupon the owner creates
   * appears on the web **and** in the app, and is applied by itself in both.
   */
  coupons: CouponBoardEntry[];
  store: WebStoreConfig | null;
}

const MENU_KEY = "web:menu:v1";
const MENU_TTL_SECONDS = 60;

const num = (value: unknown, fallback = 0): number => {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};
const str = (value: unknown, fallback = ""): string => (typeof value === "string" ? value : fallback);
const bool = (value: unknown, fallback = false): boolean => (typeof value === "boolean" ? value : fallback);

function toDish(record: FsRecord): WebDish {
  const amountPaisa = num(record.amountPaisa);
  const discountAmountPaisa = num(record.discountAmountPaisa);
  const hasDiscount = discountAmountPaisa > 0 && discountAmountPaisa < amountPaisa;
  return {
    id: String(record.id),
    kind: "dish",
    name: str(record.name, "Dish"),
    description: str(record.description),
    amountPaisa,
    payablePaisa: hasDiscount ? amountPaisa - discountAmountPaisa : amountPaisa,
    discountAmountPaisa: hasDiscount ? discountAmountPaisa : 0,
    // A published price is the price. "Sold out" is the only switch that takes a
    // dish off sale — there is no second, hidden state a customer has to guess at.
    available: bool(record.available, true),
    veg: bool(record.veg),
    spiceLevel: num(record.spiceLevel),
    tags: Array.isArray(record.tags) ? record.tags.filter((tag): tag is string => typeof tag === "string") : [],
    orderCount: num(record.orderCount),
    categoryId: str(record.categoryId),
    subcategoryId: str(record.subcategoryId),
    imageUrl: str(record.imageUrl),
    prepMinutes: num(record.prepMinutes, 0),
    featured: bool(record.featured),
  };
}

function toCombo(record: FsRecord): WebCombo {
  const refs = Array.isArray(record.itemRefs) ? record.itemRefs : [];
  return {
    id: String(record.id),
    kind: "combo",
    name: str(record.name, "Combo"),
    description: str(record.description),
    amountPaisa: num(record.amountPaisa),
    payablePaisa: num(record.amountPaisa),
    discountAmountPaisa: 0,
    regularAmountPaisa: num(record.regularAmountPaisa, num(record.amountPaisa)),
    available: bool(record.available, true),
    imageUrl: str(record.imageUrl),
    itemRefs: refs
      .map((entry) => entry as { menuItemId?: unknown; qty?: unknown })
      .filter((entry) => typeof entry?.menuItemId === "string")
      .map((entry) => ({ menuItemId: String(entry.menuItemId), qty: Math.max(1, num(entry.qty, 1)) })),
    featured: bool(record.featured),
  };
}

export function toStoreConfig(record: FsRecord | null): WebStoreConfig | null {
  if (!record) return null;
  return {
    storeName: str(record.storeName, "Undal HomeKitchen"),
    open: bool(record.open),
    ...(typeof record.acceptingOrders === "boolean" ? { acceptingOrders: record.acceptingOrders } : {}),
    ...(typeof record.openSynced === "boolean" ? { openSynced: record.openSynced } : {}),
    autoClosed: bool(record.autoClosed),
    manualOverrideNote: str(record.manualOverrideNote),
    weekly: record.weekly as Record<string, WebStoreSlot[]> | undefined,
    specialDates: record.specialDates as WebStoreConfig["specialDates"],
    deliveryPerKmAmountPaisa: num(record.deliveryPerKmAmountPaisa),
    radiusKm: num(record.radiusKm),
    minOrderAmountPaisa: num(record.minOrderAmountPaisa, 15000),
    storeLat: num(record.storeLat),
    storeLng: num(record.storeLng),
    storeAddressText: str(record.storeAddressText, "Sylhet, Bangladesh"),
    bannerUrl: str(record.bannerUrl),
    logoUrl: str(record.logoUrl),
  };
}

async function readMenu(env: Env): Promise<WebMenu> {
  const [sections, subsections, dishes, combos, campaigns, coupons, store] = await Promise.all([
    fsQuery(env, { collectionId: "categories", limit: 60 }).catch(() => []),
    fsQuery(env, { collectionId: "subcategories", limit: 120 }).catch(() => []),
    fsQuery(env, { collectionId: "menuItems", limit: 300 }).catch(() => []),
    fsQuery(env, { collectionId: "combos", limit: 100 }).catch(() => []),
    fsQuery(env, { collectionId: "campaigns", limit: 40 }).catch(() => []),
    // The discount table, read with the service account — the same read the
    // Android app does through `config/couponBoard` when it is published. A
    // Firestore failure here must never take the menu down with it.
    fsQuery(env, { collectionId: "coupons", limit: 60 }).catch(() => []),
    fsGet(env, "config/store").catch(() => null),
  ]);

  return {
    fetchedAt: Date.now(),
    sections: sections
      .map((record) => ({
        id: String(record.id),
        name: str(record.name),
        order: num(record.order),
        active: bool(record.active, true),
        iconUrl: str(record.iconUrl),
      }))
      .filter((section) => section.active && section.name.length > 0)
      .sort((a, b) => a.order - b.order),
    subsections: subsections
      .map((record) => ({
        id: String(record.id),
        categoryId: str(record.categoryId),
        name: str(record.name),
        order: num(record.order),
        active: bool(record.active, true),
      }))
      .filter((section) => section.active)
      .sort((a, b) => a.order - b.order),
    dishes: dishes.map(toDish).filter((dish) => dish.name.length > 0),
    combos: combos.map(toCombo).filter((combo) => combo.name.length > 0),
    campaigns: campaigns
      .map((record) => ({
        id: String(record.id),
        title: str(record.title),
        subtitle: str(record.subtitle),
        type: str(record.type, "percent"),
        value: num(record.value),
        endAt: str(record.endAt),
        bannerUrl: str(record.bannerUrl),
        code: str(record.code),
        active: bool(record.active, true),
      }))
      .filter((campaign) => campaign.title.length > 0),
    coupons: buildCouponBoard(
      coupons.map((record) => ({ ...(record as CouponDocument), code: str(record.code, String(record.id)) })),
    ),
    store: toStoreConfig(store),
  };
}

/** The merged menu, from KV when it is fresh enough. */
export async function webMenu(env: Env, options: { force?: boolean } = {}): Promise<WebMenu> {
  if (!options.force) {
    const cached = (await env.UNDAL_KV.get(MENU_KEY, "json").catch(() => null)) as WebMenu | null;
    if (cached && Date.now() - cached.fetchedAt < MENU_TTL_SECONDS * 1000) {
      // A snapshot written before the coupon board existed is still a good menu.
      // Filling the gap here means no page has to defend against its own cache.
      return { ...cached, coupons: cached.coupons ?? [] };
    }
  }
  const menu = await readMenu(env);
  await env.UNDAL_KV.put(MENU_KEY, JSON.stringify(menu), { expirationTtl: 3600 }).catch(() => undefined);
  return menu;
}

export function menuItem(menu: WebMenu, id: string): WebItem | null {
  const dish = menu.dishes.find((entry) => entry.id === id);
  if (dish) return dish;
  return menu.combos.find((entry) => entry.id === id) ?? null;
}

/** Sections that actually hold something the customer can order. */
export function populatedSections(menu: WebMenu): Array<WebSection & { count: number }> {
  return menu.sections
    .map((section) => ({
      ...section,
      count: menu.dishes.filter((dish) => dish.categoryId === section.id).length,
    }))
    .filter((section) => section.count > 0);
}

export interface MenuQuery {
  sectionId?: string;
  subsectionId?: string;
  search?: string;
  sort?: "recommended" | "price-asc" | "price-desc" | "popular";
}

export function queryMenu(menu: WebMenu, query: MenuQuery): WebItem[] {
  const needle = (query.search ?? "").trim().toLowerCase();
  let items: WebItem[] = query.sectionId === "combos" ? [...menu.combos] : [...menu.dishes, ...menu.combos];

  if (query.sectionId && query.sectionId !== "combos") {
    items = items.filter((item) =>
      item.kind === "combo" ? false : item.categoryId === query.sectionId,
    );
  }
  if (query.subsectionId) {
    items = items.filter((item) => item.kind === "dish" && item.subcategoryId === query.subsectionId);
  }
  if (needle) {
    items = items.filter((item) => {
      const haystack =
        item.kind === "dish"
          ? `${item.name} ${item.description} ${item.tags.join(" ")}`.toLowerCase()
          : `${item.name} ${item.description}`.toLowerCase();
      return haystack.includes(needle);
    });
  }

  switch (query.sort) {
    case "price-asc":
      return items.sort((a, b) => a.payablePaisa - b.payablePaisa);
    case "price-desc":
      return items.sort((a, b) => b.payablePaisa - a.payablePaisa);
    case "popular":
      return items.sort(
        (a, b) => (b.kind === "dish" ? b.orderCount : 0) - (a.kind === "dish" ? a.orderCount : 0),
      );
    default:
      return items.sort((a, b) => {
        if (a.featured !== b.featured) return a.featured ? -1 : 1;
        const aCount = a.kind === "dish" ? a.orderCount : 0;
        const bCount = b.kind === "dish" ? b.orderCount : 0;
        return bCount - aCount;
      });
  }
}
