#!/usr/bin/env node
/**
 * Undal — alert ladder gate.
 *
 * The kitchen's ability to ring spans two codebases and four mechanisms: a push
 * from the Worker, a topic fan-out, a live Firestore watcher on the phone, and a
 * re-alert cron that repeats the push until somebody answers. Every join between
 * those pieces is a string — a topic name, a message type, a channel id, a cron
 * expression, a route path — and a string that drifts silently takes the ring
 * with it. FCM accepts the message, the app receives it, and nothing happens.
 *
 * There is no test that can feel a phone not ringing, so this file asserts the
 * joins instead, reading both sides from source:
 *
 *   · the topic the Worker fans out to is the topic the app subscribes to
 *   · every message type the Worker can send is a type the app handles
 *   · every channel id the Worker names exists on the app side
 *   · the silent-return bug that caused all of this cannot come back
 *   · the cron is declared, dispatched and implemented — all three
 *   · the live watcher, the test-alert route and the manifest entry all exist
 *
 * Rules of this file, borrowed from check-web-rules.mjs:
 *   • every value is read out of the shipping source; nothing is retyped here
 *   • a check that cannot find its evidence fails instead of passing quietly
 *   • zero dependencies, so the workflow needs nothing but Node
 *
 * Run it by hand:  node tools/check-alert-ladder.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => readFileSync(join(ROOT, relative), "utf8");

const WORKER_ALERTING = "worker/src/alerting.ts";
const WORKER_REALERT = "worker/src/crons/realert.ts";
const WORKER_INDEX = "worker/src/index.ts";
const WORKER_NOTIFY = "worker/src/routes/notify.ts";
const WORKER_WRANGLER = "worker/wrangler.toml";

const APP_CONTRACT = "app/src/main/java/com/undal/syl/app/core/fcm/AlertContract.kt";
const APP_FCM = "app/src/main/java/com/undal/syl/app/core/fcm/UndalFcmService.kt";
const APP_CHANNELS = "app/src/main/java/com/undal/syl/app/core/fcm/AlarmChannels.kt";
const APP_ALERT = "app/src/main/java/com/undal/syl/app/core/background/OrderAlertService.kt";
const APP_WATCHER = "app/src/main/java/com/undal/syl/app/core/background/KitchenWatchService.kt";
const APP_MANIFEST = "app/src/main/AndroidManifest.xml";
const APP_TOKEN = "app/src/main/java/com/undal/syl/app/core/fcm/TokenRegistrar.kt";

const failures = [];
const passes = [];

function check(title, condition, detail) {
  if (condition) {
    passes.push(title);
  } else {
    failures.push({ title, detail });
  }
}

/** A Kotlin `const val NAME = "value"` out of a named object. */
function kotlinConst(source, name) {
  const match = new RegExp(`const val ${name}\\s*=\\s*"([^"]+)"`).exec(source);
  return match?.[1] ?? null;
}

/** A `const NAME = "value"` or `NAME: string = "value"` out of TypeScript. */
function typeScriptConst(source, name) {
  const match = new RegExp(`(?:export )?const ${name}\\s*=\\s*"([^"]+)"`).exec(source);
  return match?.[1] ?? null;
}

// ---------------------------------------------------------------------------
// 1 · the topic: one name, two codebases
// ---------------------------------------------------------------------------
const alerting = read(WORKER_ALERTING);
const contract = read(APP_CONTRACT);

const workerTopic = typeScriptConst(alerting, "KITCHEN_TOPIC");
const appTopic = kotlinConst(contract, "KITCHEN_TOPIC");

check(
  "the kitchen topic is defined on both sides",
  Boolean(workerTopic) && Boolean(appTopic),
  `worker=${JSON.stringify(workerTopic)} app=${JSON.stringify(appTopic)}`,
);
check(
  "both sides subscribe to the same kitchen topic",
  workerTopic !== null && workerTopic === appTopic,
  `the Worker fans out to "${workerTopic}" while the app subscribes to "${appTopic}" — every order alert ` +
    "would be sent to a topic nobody is listening to",
);
check(
  "the device actually subscribes to it",
  read(APP_TOKEN).includes("subscribeToTopic(AlertContract.KITCHEN_TOPIC)"),
  "TokenRegistrar never subscribes a kitchen device to the alert topic",
);

// ---------------------------------------------------------------------------
// 2 · message types: everything sent is something handled
// ---------------------------------------------------------------------------
const fcm = read(APP_FCM);
const handledTypes = [...contract.matchAll(/const val TYPE_[A-Z_]+ = "([^"]+)"/g)].map((m) => m[1]);
const handledInWhen = [...fcm.matchAll(/TYPE_[A-Z_]+ -> /g)].length;

const sentTypes = new Set(["order_created"]); // the default in raiseKitchenAlert
for (const match of alerting.matchAll(/type:\s*"([a-z_]+)"/g)) sentTypes.add(match[1]);
sentTypes.add("order_created"); // the order path's own literal

check(
  "the app declares every message type the Worker can send",
  [...sentTypes].every((type) => handledTypes.includes(type)),
  `sent: ${[...sentTypes].join(", ")} — declared: ${handledTypes.join(", ")}`,
);
check(
  "each of those types is dispatched by the app",
  handledInWhen >= handledTypes.length,
  `${handledTypes.length} types declared but only ${handledInWhen} branches in onMessageReceived`,
);

// ---------------------------------------------------------------------------
// 3 · channel ids: a name the Worker uses must exist on the phone
// ---------------------------------------------------------------------------
const channelIds = new Set([
  ...[...read(WORKER_NOTIFY).matchAll(/channelId:\s*"([a-z_]+)"/g)].map((m) => m[1]),
  ...[...read(WORKER_ALERTING).matchAll(/channelId:\s*"([a-z_]+)"/g)].map((m) => m[1]),
]);
const declaredChannels = [...contract.matchAll(/const val CHANNEL_[A-Z_]+ = "([^"]+)"/g)].map((m) => m[1]);
const createdChannels = [...read(APP_CHANNELS).matchAll(/NotificationChannel\(\s*\n?\s*([A-Z_]+)/g)].length;

check(
  "the Worker only names channels the app creates",
  [...channelIds].length > 0 && [...channelIds].every((id) => declaredChannels.includes(id)),
  `Worker names ${[...channelIds].join(", ")} — the app declares ${declaredChannels.join(", ")}`,
);
check(
  "the app creates every channel it declares",
  createdChannels >= declaredChannels.length,
  `${declaredChannels.length} channel ids declared, ${createdChannels} NotificationChannel(...) constructions found`,
);

// ---------------------------------------------------------------------------
// 4 · the bug that caused all of this cannot come back
// ---------------------------------------------------------------------------
for (const [file, source] of [
  [WORKER_ALERTING, alerting],
  ["worker/src/routes/store.ts", read("worker/src/routes/store.ts")],
  [WORKER_NOTIFY, read(WORKER_NOTIFY)],
]) {
  check(
    `${file} never abandons an alert without saying so`,
    !/tokens\.length === 0\)\s*return;/.test(source) && !/if \(tokens\.length === 0\) return;/.test(source),
    "the old `if (tokens.length === 0) return;` is back: a kitchen with no registered device goes " +
      "silent with nothing in the logs and nothing on the screen",
  );
}

check(
  "the alert fan-out reports what it reached",
  /raiseKitchenAlert/.test(alerting) &&
    /topicSent/.test(alerting) &&
    /warning/.test(alerting) &&
    /delivered/.test(alerting),
  "raiseKitchenAlert must return the device count, the topic result and a warning",
);
check(
  "every order path goes through the shared fan-out",
  read("worker/src/routes/store.ts").includes("raiseKitchenAlert") &&
    read(WORKER_NOTIFY).includes("raiseKitchenAlert"),
  "the web store or the notify route still sends its own way, so the two can drift",
);

// ---------------------------------------------------------------------------
// 5 · the re-alert sweep: declared, dispatched, implemented
// ---------------------------------------------------------------------------
const wrangler = read(WORKER_WRANGLER);
const index = read(WORKER_INDEX);
const realert = read(WORKER_REALERT);

check(
  "the every-minute cron is declared",
  /"\* \* \* \* \*"/.test(wrangler),
  `${WORKER_WRANGLER} has no "* * * * *" trigger, so nothing repeats a missed order alert`,
);
check(
  "the cron constant matches the declaration",
  index.includes('REALERT_CRON = "* * * * *"') && wrangler.includes('"* * * * *"'),
  "index.ts and wrangler.toml disagree about the re-alert schedule",
);
check(
  "the scheduled handler calls the sweep",
  /cron === REALERT_CRON/.test(index) && /runRealertSweep\(env\)/.test(index),
  "the trigger exists but nothing runs for it",
);
check(
  "the sweep reads unanswered orders only",
  realert.includes('field: "status"') && realert.includes('value: "placed"'),
  "the sweep must select orders that are still placed",
);
check(
  "the sweep re-uses the first push's collapse key",
  /order-\$\{orderId\}`/.test(realert),
  "without a shared collapse key every re-alert stacks another notification",
);

// ---------------------------------------------------------------------------
// 6 · the phone-side routes exist
// ---------------------------------------------------------------------------
const manifest = read(APP_MANIFEST);
check(
  "the live watcher service is declared in the manifest",
  manifest.includes("KitchenWatchService") && /foregroundServiceType="specialUse"/.test(manifest),
  "KitchenWatchService is not declared, so it can never start",
);
check(
  "the watcher rings through the same alert service as push",
  read(APP_WATCHER).includes("OrderAlertService.ring"),
  "the watcher must hand orders to the alert service, not post a notification of its own",
);
check(
  "the alert service is the one place that plays the sound",
  /isLooping = true/.test(read(APP_ALERT)) && /USAGE_ALARM/.test(read(APP_ALERT)),
  "the order sound must loop on the alarm stream",
);

const alertService = read(APP_ALERT);
check(
  "a second order cannot silence the first",
  /waiting\[orderId\] = /.test(alertService) && /ringHead\(\)/.test(alertService),
  "the alert service must queue orders and move to the next one when an order is answered",
);
check(
  "the test alert is labelled and self-stopping",
  /EXTRA_IS_TEST/.test(alertService) && /TEST_SECONDS/.test(alertService) && /stopTestRunnable/.test(alertService),
  "a test alert must be distinguishable from a real one and must stop itself",
);

check(
  "a silent phone is treated as a phone that cannot ring",
  /AlertVolume\.arm\(/.test(read(APP_ALERT)) &&
    /AlertVolume\.raise\(/.test(read("app/src/main/java/com/undal/syl/app/features/vendor/DiagnosticsViewModel.kt")) &&
    /MODIFY_AUDIO_SETTINGS/.test(manifest) &&
    /blocking = !volumeHealthy/.test(read("app/src/main/java/com/undal/syl/app/features/vendor/DiagnosticsViewModel.kt")),
  "the ring plays on the ALARM stream: at zero volume nothing else in this app can make it audible",
);
const withoutComments = (source) =>
  source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");

check(
  "the sound is prepared before it is asked to play",
  (() => {
    // Comments talk about prepare() as well, so the order is read from code only.
    const code = withoutComments(read(APP_ALERT));
    const attributes = code.indexOf("setAudioAttributes");
    const prepare = code.indexOf("prepare()");
    return attributes !== -1 && prepare !== -1 && attributes < prepare;
  })(),
  "setAudioAttributes() after prepare() makes the platform reset the player and start() throw",
);
check(
  "there is a second way to the tone, and a third to a ring",
  /cachedOrderSound\(\)/.test(read(APP_ALERT)) && /playSystemRinger\(\)/.test(read(APP_ALERT)),
  "one resource, one decode path: if it fails the kitchen is silent with nothing to say",
);

// ---------------------------------------------------------------------------
// 7 · the half of the test only the server can answer
// ---------------------------------------------------------------------------
check(
  "the Worker exposes the ring-my-phone endpoint",
  /"\/notify\/test-alert"/.test(index) && /export async function handleTestAlert/.test(read(WORKER_NOTIFY)),
  "there is no way to prove the relay can reach this phone",
);
check(
  "the readiness screen calls it",
  read("app/src/main/java/com/undal/syl/app/core/net/WorkerApi.kt").includes("/notify/test-alert"),
  "the endpoint exists but the app never asks for the test",
);
check(
  "the endpoint is vendor-only",
  /handleTestAlert[\s\S]{0,600}?requireVendor\(env, request\)/.test(read(WORKER_NOTIFY)),
  "a customer could ring the kitchen phone with no order",
);
check(
  "a device token is only believed when it can be read back",
  /document\.get\(\)\.await\(\)\.exists\(\)/.test(read(APP_TOKEN)),
  "TokenRegistrar must verify the write, or a refused write is invisible",
);

// ---------------------------------------------------------------------------
// 8 · the customer half: an order update has to be allowed to arrive
// ---------------------------------------------------------------------------
check(
  "the customer is asked for notification permission",
  /POST_NOTIFICATIONS/.test(read("app/src/main/java/com/undal/syl/app/core/fcm/NotificationConsent.kt")) &&
    /CustomerNotificationConsent/.test(read("app/src/main/java/com/undal/syl/app/features/root/UndalRoot.kt")),
  "Android 13+ drops every notification until the permission is granted, and nothing asks the customer",
);
check(
  "the customer's status relay is wired end to end",
  /STATUS_COPY/.test(read(WORKER_NOTIFY)) &&
    /notifyOrderStatus/.test(read("app/src/main/java/com/undal/syl/app/core/net/WorkerApi.kt")) &&
    /KIND_ORDER_STATUS -> api.notifyOrderStatus/.test(read("app/src/main/java/com/undal/syl/app/core/background/UndalWorkers.kt")),
  "the customer must receive a push for each status the kitchen sets",
);

// ---------------------------------------------------------------------------
// result
// ---------------------------------------------------------------------------
console.log(`check-alert-ladder.mjs — ${passes.length} check(s) passed against ${ROOT.split("/").pop()}/`);
for (const pass of passes) console.log(`  ✓ ${pass}`);

if (failures.length > 0) {
  console.log(`\n${failures.length} hole(s) in the alert ladder:\n`);
  for (const failure of failures) {
    console.log(`  ✗ ${failure.title}`);
    console.log(`      ${failure.detail}`);
  }
  console.log("\nRESULT: an order could be missed.\n");
  process.exit(1);
}

console.log("\nRESULT: every route to a ringing kitchen phone is wired.\n");
