/**
 * Cron `0 12 * * *` (18:00 Asia/Dhaka) — daily offer broadcast (PART J · P-004).
 *
 * The vendor writes the message rotation into config/promos from the app; this
 * cron only picks the next message, sends it to the `promos_all` topic and
 * records which index was used, so the rotation never repeats twice in a row.
 */
import type { Env } from "../env";
import { fsGet, fsPatchWithServerTimestamps } from "../firestore";
import { sendToTopic } from "../fcmv1";
import { dhakaClock } from "../util/store-hours";

interface PromoMessage {
  title?: string;
  body?: string;
  imageUrl?: string;
}

interface PromoConfig {
  enabled?: boolean;
  messages?: PromoMessage[];
  lastIndex?: number;
}

export interface PromoBlastResult {
  ok: boolean;
  sent: boolean;
  reason?: string;
  index?: number;
}

export async function runPromoBlast(env: Env, now: Date = new Date()): Promise<PromoBlastResult> {
  const config = (await fsGet(env, "config/promos")) as PromoConfig | null;
  if (!config || config.enabled !== true) return { ok: true, sent: false, reason: "promos-disabled" };

  const messages = Array.isArray(config.messages) ? config.messages : [];
  if (messages.length === 0) return { ok: true, sent: false, reason: "no-promo-messages" };

  const index = Math.abs(Math.trunc(Number(config.lastIndex ?? 0))) % messages.length;
  const message = messages[index] ?? {};
  const title = (message.title ?? "Today at Undal").slice(0, 90);
  const body = (message.body ?? "Something fresh is cooking in the Undal kitchen.").slice(0, 180);

  const outcome = await sendToTopic(env, env.PROMO_TOPIC || "promos_all", {
    data: {
      type: "promo",
      title,
      body,
      imageUrl: message.imageUrl ?? "",
      channelId: "customer_promos",
      clickAction: "customer_home",
      sentForDate: dhakaClock(now).dateKey,
    },
    ttl: "86400s",
    collapseKey: "daily-promo",
  });

  if (outcome.ok) {
    await fsPatchWithServerTimestamps(
      env,
      "config/promos",
      { lastIndex: index + 1, lastSentAt: now },
      ["updatedAt"],
    );
  }

  return { ok: true, sent: outcome.ok, index };
}
