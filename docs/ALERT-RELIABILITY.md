# Kitchen alert reliability

How an order placed at 21:40 on a phone browser makes a kitchen phone ring at
21:40 — when that phone is locked, in a pocket, on silent, asleep in Doze, or
was killed by its manufacturer's battery manager ten minutes ago.

This is the implementation guide for the alert ladder in this repository. Every
snippet below is taken from the code that ships, with its file path, and elisions
are marked `…`, so a reader can follow it line by line or lift it into another
project. The last sections cover
deployment, the acceptance tests that must be run on real hardware, the platform
limits that no implementation can code around, and the places where this
implementation deliberately differs from a naive reading of the brief.

---

## 1. The ladder

There is no single mechanism that guarantees a ringing phone. Android does not
offer one, FCM does not offer one, and no OEM leaves one intact. What works is a
ladder: several independent routes to the same outcome, each covering the
failure of the one below it. This repository ships eleven rungs.

| # | Rung | File | What it guarantees |
| --- | --- | --- | --- |
| 0 | Device registration | `app/.../core/fcm/TokenRegistrar.kt` | the Worker has a token to send to, and knows it landed |
| 1 | OAuth2 minting | `worker/src/gcp.ts` | FCM HTTP v1 accepts the send at all |
| 2 | The payload | `worker/src/fcmv1.ts` | data-only, high priority, a sane TTL, iOS headers |
| 3 | Fan-out | `worker/src/alerting.ts` | every device **and** a topic; a failure is reported, never swallowed |
| 4 | Re-alert sweep | `worker/src/crons/realert.ts`, `crons/alert-window.ts` | a phone that missed the push is asked again, every minute |
| 5 | Notification channels | `app/.../core/fcm/AlarmChannels.kt` | alarm-class audio, Do-Not-Disturb bypass, a channel that cannot stay muted |
| 6 | The bootloader | `app/.../core/fcm/UndalFcmService.kt` | `onMessageReceived` starts the alarm from a dead process |
| 7 | The alarm | `app/.../core/background/OrderAlertService.kt` | foreground service, looping audio, wake lock, full-screen intent, a queue |
| 8 | The live fallback | `app/.../core/background/KitchenWatchService.kt` | a Firestore listener rings the phone even if no push ever arrives |
| 9 | Alarm volume | `app/.../core/fcm/AlertVolume.kt` | the sound is audible, and the owner's volume comes back afterwards |
| 10 | Permissions and OEM exemptions | `AlarmChannels.kt`, `NotificationConsent.kt`, `MainActivity.kt` | battery optimisation, full-screen intents, exact alarms, six autostart screens |
| 11 | Reboot and update | `app/.../core/background/BootReceiver.kt` | the ladder re-arms after a restart without anybody opening the app |

Two cross-cutting rules hold the whole thing together:

- **One vocabulary.** The topic name, the message types and the channel ids are
  declared once per side — `worker/src/alerting.ts` and
  `app/.../core/fcm/AlertContract.kt` — and `tools/check-alert-ladder.mjs`
  fails the build when the two copies drift. A typo here is invisible in every
  other check: FCM accepts the message, the app receives it, and nothing
  happens.
- **Nothing fails silently.** Every rung returns its outcome. `raiseKitchenAlert`
  never throws for a delivery problem and never returns `undefined`; the caller
  records `tokens`, `tokenSent`, `tokenFailed`, `topicSent` and a human-readable
  `warning`, and the vendor diagnostics screen shows them.

---

## 2. Rung 0 — the device has to be reachable

A token that was written but not readable is the same as no token. Registration
therefore verifies itself:

```kotlin
// app/src/main/java/com/undal/syl/app/core/fcm/TokenRegistrar.kt
document.set(/* platform, appVersion, updatedAt */)

// A registration is believed only once it can be read back. The rules allow
// this write and the read; if either is refused the device is not reachable and
// the vendor diagnostics screen says so instead of showing a green tick.
if (!document.get().await().exists()) { /* report, do not pretend */ }
```

The document lives at `fcmTokens/{uid}/tokens/{token}`, and the Firestore rules
allow only its owner to write it, only with `platform`, `appVersion` and an
`updatedAt` equal to `request.time`:

```
// firebase/firestore.rules
match /fcmTokens/{userId}/tokens/{token} {
  allow read, delete: if isOwner(userId);
  allow create, update: if isOwner(userId)
    && request.resource.data.keys().hasAll(['platform', 'updatedAt', 'appVersion'])
    && request.resource.data.platform == 'android'
    && request.resource.data.updatedAt == request.time;
}
```

`onNewToken` and `onDeletedMessages` both re-enqueue registration, so a rotated
token or a dropped batch heals itself:

```kotlin
override fun onNewToken(token: String) {
    TokenRefreshWorker.enqueue(applicationContext)
}
override fun onDeletedMessages() {
    TokenRefreshWorker.enqueue(applicationContext)
    super.onDeletedMessages()
}
```

---

## 3. Rung 1 — minting the FCM HTTP v1 credential

The legacy server key does not exist any more. Every send needs an OAuth2 access
token minted from a Google service-account key, and the Worker does that itself:
an RS256-signed JWT is exchanged for an access token, which is cached in KV
under `oauth:token` and re-minted on a 401.

The service-account JSON is a Cloudflare secret (`FIREBASE_SA_JSON`), never a
wrangler variable, never in the repository:

```bash
wrangler secret put FIREBASE_SA_JSON     # the whole JSON file, one line
```

Scopes requested: `cloud-platform`, `firebase.database`, `firebase.messaging`,
`datastore`. The resulting token is used as an admin credential for Firestore and
the Realtime Database, which is why it bypasses the security rules — and why the
rules still have to be right for the app's own writes.

`GET /healthz` reports whether the credential, KV, the RTDB URL, the web sign-in
key and the App Links file are all present, and answers 503 if any one of them is
not. A deployment that cannot mint a token says so on its first request.

---

## 4. Rung 2 — the payload

This is the single most important decision in the system:

> **An order alert is a data-only message. It never carries a `notification`
> block.**

With a `notification` block, Android hands the message to the system tray when
the app is in the background and `onMessageReceived` is never called. The alarm
channel is not used, no wake lock is taken, no audio loops. The order becomes a
quiet icon in a drawer — which is exactly the failure this document exists to
prevent.

```ts
// worker/src/fcmv1.ts
export function pushBody(data: Record<string, string>, options: PushOptions): string {
  const payload: Record<string, string> = {};
  for (const [key, value] of Object.entries(data)) {
    payload[key] = typeof value === "string" ? value : String(value ?? "");
  }

  const ttl = options.ttl ?? DEFAULT_TTL;                 // "300s"
  const urgent = options.highPriority !== false;
  // An order alert never carries a display block, even if a caller passes one.
  const showsNotification = Boolean(options.display) && payload.type !== "order_created";

  const message: Record<string, unknown> = {
    data: payload,
    android: { priority: urgent ? "HIGH" : "NORMAL", ttl },
    apns: apnsConfig(showsNotification, urgent, ttl),
  };
  if (showsNotification && options.display) { /* customer status only */ }
  if (options.collapseKey) { /* notices only, never an order */ }
  return JSON.stringify({ message });
}
```

What goes over the wire for a new order:

```json
{
  "message": {
    "token": "…",
    "data": {
      "type": "order_created",
      "orderId": "order-1",
      "shortId": "UND-1042",
      "title": "New order UND-1042",
      "body": "Tap to review and accept",
      "channelId": "vendor_new_order",
      "clickAction": "vendor_order"
    },
    "android": { "priority": "HIGH", "ttl": "300s" },
    "apns": {
      "headers": {
        "apns-priority": "5",
        "apns-push-type": "background",
        "apns-expiration": "1791970089"
      },
      "payload": { "aps": { "content-available": 1 } }
    }
  }
}
```

Field by field:

| Field | Value | Why |
| --- | --- | --- |
| `data.type` | `order_created` | the app switches on this string; `AlertContract.TYPE_ORDER_CREATED` |
| `data.channelId` | `vendor_new_order` | the alarm channel, so a tray fallback still lands somewhere loud |
| `android.priority` | `HIGH` | lets a dozing device use the radio immediately instead of waiting for a maintenance window |
| `android.ttl` | `300s` | how long FCM keeps trying for an offline phone — see §12 for the deviation from `0s` |
| `android.collapse_key` | *absent* | Android keeps four collapse keys; a busy kitchen's backlog must not evict itself |
| `notification` | *absent* | the rule above |
| `apns.*` | as shown | the iOS half; ignored for Android tokens — see §12 |

Data values are coerced to strings. FCM v1 rejects the whole message with
`INVALID_ARGUMENT` if one value is a number, and a rejected message is a missed
order, so the coercion is cheaper than the type it protects.

The send itself is one `POST` per target — FCM v1 has no multicast endpoint, the
legacy `registration_ids` field is forbidden — with bounded concurrency of 20, and
a token FCM reports as `UNREGISTERED` or `NOT_FOUND` is deleted from
`fcmTokens/{uid}/tokens` so dead tokens cannot accumulate:

```ts
const unregistered =
  code === "UNREGISTERED" || code === "NOT_FOUND" ||
  (response.status === 400 && /not a valid FCM registration token/i.test(raw));
```

---

## 5. Rung 3 — every route at once

`raiseKitchenAlert` sends to the kitchen account's own devices **and** to a topic
every kitchen device subscribes to:

```ts
// worker/src/alerting.ts
export const KITCHEN_TOPIC = "undal_kitchen";

// route 1: the kitchen account's own devices
const uid = await vendorUid(env).catch(() => null);
if (!uid) outcome.warning = "no vendor account document found (vendor/{uid})";
else {
  const tokens = await tokensForUser(env, uid).catch(() => [] as string[]);
  outcome.tokens = tokens.length;
  if (tokens.length === 0) outcome.warning = "the kitchen account has no registered device token — …";
  else { /* sendToTokens, purge what FCM calls dead */ }
}

// route 2: the topic, which consults no account at all
const topicPush = await sendToTopic(env, topic, options).catch(() => ({ ok: false, … }));
outcome.topicSent = topicPush.ok;
outcome.delivered = outcome.tokenSent + (outcome.topicSent ? 1 : 0);
```

The topic exists because the three ways route 1 can fail are all real and all
silent: the bootstrap never ran, the vendor account was recreated, the KV cache
was cold and the lookup query failed at 3 a.m. A topic push needs no account
lookup, so none of those can mute an order while a device is subscribed.

`delivered` counts messages **accepted by FCM**. That is not a receipt from a
device and not proof that any sound played, and the type says so in its doc
comment. Pretending otherwise makes a log line lie.

The device side subscribes and unsubscribes with the role, in the same registrar
that writes the token:

```kotlin
// app/src/main/java/com/undal/syl/app/core/fcm/TokenRegistrar.kt
runCatching { messaging.subscribeToTopic(AlertContract.KITCHEN_TOPIC).await() }   // a kitchen device
runCatching { messaging.unsubscribeFromTopic(AlertContract.KITCHEN_TOPIC).await() } // a customer device
```

A customer phone that stays subscribed to `undal_kitchen` would ring for other
people's orders, which is why the unsubscribe is in the same function as the
subscribe rather than left to a sign-out path that may never run.

---

## 6. Rung 4 — the re-alert sweep

One push can be lost. A phone in a lift, an OEM battery saver, a token that
rotated a second before the send: FCM keeps no copy once the TTL is up. So the
Worker repeats the alert on a cron, and the policy lives in its own pure,
import-free, tested file:

```ts
// worker/src/crons/alert-window.ts
export const REALERT_FROM_SECONDS = 45;
export const REALERT_UNTIL_SECONDS = 15 * 60;
export const MISSED_FROM_SECONDS = 27 * 60;
export const MISSED_UNTIL_SECONDS = 29 * 60;

export function sweepAction(ageSeconds: number): SweepAction {
  if (ageSeconds >= MISSED_FROM_SECONDS && ageSeconds < MISSED_UNTIL_SECONDS) return "missed";
  if (ageSeconds >= REALERT_FROM_SECONDS && ageSeconds < REALERT_UNTIL_SECONDS) return "realert";
  return "silent";
}
```

| Age of an unanswered order | What happens |
| --- | --- |
| 0 – 45 s | nothing; the first push is still in flight |
| 45 s – 15 min | re-alert on every sweep |
| 15 – 27 min | silence; ringing for half an hour is a device problem, not a delivery problem |
| 27 – 29 min | one "this order was never answered" notice |
| older | silence, forever |

The last row matters as much as the others: an order left `placed` because nobody
ever touched it must not ring a phone every minute for the rest of the week.

The sweep is registered in `wrangler.toml` at Cloudflare's finest granularity:

```toml
[triggers]
crons = ["*/5 * * * *", "0 12 * * *", "* * * * *"]
```

and the query it runs is deliberately single-field:

```ts
// A query on status == placed alone. Adding `orderBy createdAt` would need a
// composite index, and a cron that throws FAILED_PRECONDITION every minute is
// worse than no cron: the sort happens here, over at most SWEEP_LIMIT documents.
const outstanding = await fsQuery(env, {
  collectionId: "orders",
  where: [{ field: "status", op: "EQUAL", value: "placed" }],
  limit: SWEEP_LIMIT,          // 10
});
```

A repeated alert carries `repeated: "true"`, which rings the phone again but does
not file a second entry in the notification inbox.

---

## 7. Rung 5 — the notification channel

The channel is created at every entry point that could be the first one after a
boot, an update or a cold push: `UndalApp`, `MainActivity`, `BootReceiver`,
`UndalFcmService`, `OrderAlertService`, `KitchenWatchService`,
`PrepTimerService`, `PresenceHeartbeatService`. `ensureChannels` is idempotent.

```kotlin
// app/src/main/java/com/undal/syl/app/core/fcm/AlarmChannels.kt
fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService<NotificationManager>() ?: return
    val soundUri = orderSoundUri(context)      // android.resource://pkg/R.raw.undal_order_alert

    val vendorOrder = NotificationChannel(
        VENDOR_NEW_ORDER,                                     // "vendor_new_order"
        context.getString(R.string.channel_vendor_new_order_title),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = context.getString(R.string.channel_vendor_new_order_desc)
        enableLights(true)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
        setSound(
            soundUri,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)        // the alarm stream
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)                                    // needs notification-policy access
        // A channel's sound and importance are frozen the moment it is created:
        // an owner who ever muted it could never be un-muted from inside the app.
        if (isMuted(context, VENDOR_NEW_ORDER)) {
            manager.deleteNotificationChannel(VENDOR_NEW_ORDER)
            manager.createNotificationChannel(this)
        }
    }
    manager.createNotificationChannels(listOf(vendorOrder, vendorGeneral, vendorWatch, customerOrders, customerPromos))
}
```

Five channels, one purpose each:

| Channel id | Importance | Purpose |
| --- | --- | --- |
| `vendor_new_order` | HIGH | the order alarm: alarm-class audio, DND bypass, public on the lock screen |
| `vendor_general` | DEFAULT | kitchen notices: auto-offline, prep due, an order nobody answered |
| `vendor_watch` | MIN | the silent "the kitchen is watching" foreground notification |
| `customer_orders` | HIGH | a customer's order status |
| `customer_promos` | DEFAULT | offers |

Two details are easy to miss and both have been real bugs elsewhere:

- **The muted-channel reset.** Android freezes a channel's sound and importance
  at creation. If an owner once set the order channel to "silent", every later
  `createNotificationChannel` call is a no-op and the kitchen is silent forever
  with nothing wrong in the code. `isMuted` detects `IMPORTANCE_NONE`, or a
  below-HIGH importance with no sound, and the channel is deleted and recreated.
  The readiness screen reports when it had to.
- **The alarm stream, not the ring stream.** `USAGE_ALARM` routes the sound to
  `STREAM_ALARM`, which a phone does not mute when it is put on silent. It also
  means one number can defeat everything else: see rung 9.

`setBypassDnd(true)` only takes effect if the owner grants notification-policy
access. It cannot be requested silently, so the diagnostics screen reports
whether it is granted rather than assuming it.

---

## 8. Rung 6 — the bootloader

`onMessageReceived` is the entry point that runs when the app is in the
foreground, in the background, killed but not force-stopped, and on a locked
screen. It is short on purpose: parse, decide, hand over.

```kotlin
// app/src/main/java/com/undal/syl/app/core/fcm/UndalFcmService.kt
override fun onMessageReceived(message: RemoteMessage) {
    AlarmChannels.ensureChannels(applicationContext)
    val data = message.data
    val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    val isVendor = PushDeviceRole.isKitchen(applicationContext, uid) || /* the token claim */

    when (data["type"]) {
        TYPE_ORDER_CREATED -> handleOrderCreated(data, uid)
        TYPE_ORDER_STATUS  -> handleOrderStatus(data, uid, isVendor)
        TYPE_CHAT          -> handleChat(data, uid, isVendor)
        TYPE_PROMO         -> handlePromo(data, uid)
        TYPE_VENDOR_SELF   -> handleVendorSelf(data, uid, isVendor)
        else -> Log.i(TAG, "ignoring push of unknown type ${data["type"]}")
    }
}

private fun handleOrderCreated(data: Map<String, String>, uid: String?) {
    val orderId = data["orderId"] ?: return
    val isTest = data["test"] == "true"

    OrderAlertService.ring(
        context = applicationContext,
        orderId = orderId,
        shortId = data["shortId"].orEmpty(),
        title = data["title"].orEmpty().ifBlank { "New order ${data["shortId"].orEmpty()}" },
        body = data["body"].orEmpty().ifBlank { "Tap to review and accept" },
        isTest = isTest,
        source = OrderAlertService.SOURCE_PUSH
    )

    // FCM's high-priority exemption is short: re-arm the live fallback now, not
    // from an unbounded async callback after this service returns.
    KitchenWatchService.start(applicationContext)

    if (isTest || data["repeated"] == "true") return
    if (uid != null) scope.launch { inboxRepo.record(uid, title, body, TYPE_ORDER_CREATED, orderId) }
}
```

Notes that matter when porting this:

- **Nothing awaits.** The FCM callback runs on a thread the system can reclaim.
  Everything slow — the inbox write, the token claim — is dispatched to a
  `SupervisorJob` scope; the alarm start is synchronous.
- **The vendor role is resolved twice.** A custom claim can be an hour stale, so
  a locally remembered role for this exact account is checked first and the claim
  second. A kitchen notice must not be dropped on a technicality.
- **The notification permission is never assumed.** `postNotification` returns
  early when notifications are disabled, and a missing permission must never
  crash the service that is also responsible for ringing orders. The alarm audio
  is played by the service, not by the notification, so a revoked permission
  makes the alert invisible but **not silent**.

---

## 9. Rung 7 — the alarm itself

`OrderAlertService` is a foreground service that owns the sound. Order of
operations is the whole design:

```kotlin
private fun beginAlert(orderId: String, shortId: String, title: String, body: String, isTest: Boolean) {
    val alreadyWaiting = waiting.containsKey(orderId)

    // Foreground first, before any of the work below: Android 14/15 kills a
    // service that plays audio before it calls startForeground, and a kill here
    // is a missed order. The very first ring has to establish it, which is why
    // this comes before the early return for a repeat.
    val ringing = Waiting(orderId, shortId, title, body, isTest)
    if (!alreadyWaiting) waiting[orderId] = ringing      // linkedMapOf: oldest first
    else waiting[orderId] = ringing.copy(shortId = shortId, title = title, body = body)

    val foregroundOrder = waiting.entries.first().value
    ServiceCompat.startForeground(
        this,
        AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
        buildNotification(foregroundOrder),
        foregroundType()
    )
    persistQueue()

    if (alreadyWaiting) { publishNotification(foregroundOrder); return }   // never restart the player

    currentOrderId = foregroundOrder.orderId
    // … start the player, arm the volume, watch the live status
}
```

The audio:

```kotlin
private fun buildPlayer(configure: MediaPlayer.() -> Unit): MediaPlayer? {
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    return runCatching {
        MediaPlayer().apply {
            setAudioAttributes(attributes)
            // The player holds a partial wake lock for us, which is exactly what
            // is needed for the sound to survive a locked screen.
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            configure()
            isLooping = true
            setOnErrorListener { _, what, extra -> Log.e(TAG, "order sound failed ($what/$extra)"); true }
            prepare()
            start()
        }
    }.onFailure { … release … }.getOrNull()
}
```

Three ways to the same tone, in order of cost, because one of them fails on some
builds:

1. `res/raw/undal_order_alert.mp3` through its asset file descriptor;
2. a copy of that resource written into the app's own cache and played as a file
   — which also covers a build where the tone was compressed and
   `openRawResourceFd` returns nothing;
3. the phone's own alarm tone (`RingtoneManager.TYPE_ALARM`, then `TYPE_RINGTONE`),
   re-played every 20 seconds because a `Ringtone` cannot loop. It is not the
   Undal sound, and a missed order costs more than a wrong ringtone.

The notification carries the full-screen intent and the actions that stop it:

```kotlin
NotificationCompat.Builder(this, AlarmChannels.VENDOR_NEW_ORDER)
    .setSmallIcon(R.drawable.ic_undal_mark)
    .setContentTitle(title)
    .setContentText(body)
    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
    .setPriority(NotificationCompat.PRIORITY_MAX)
    .setCategory(NotificationCompat.CATEGORY_ALARM)
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    .setOngoing(true)
    .setAutoCancel(false)
    .setOnlyAlertOnce(false)
    .setSound(AlarmChannels.orderSoundUri(this))
    .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
    .setFullScreenIntent(openIntent, true)      // wakes and unlocks the screen
    .setContentIntent(openIntent)
    .addAction(R.drawable.ic_undal_mark, "Accept", acceptIntent)
    .addAction(R.drawable.ic_undal_mark, "Reject", rejectIntent)
    .addAction(R.drawable.ic_undal_mark, "Open", openIntent)
    .build()
```

`acceptIntent` and `rejectIntent` are deep links into the activity, so the order
can be answered from the lock screen without unlocking:

```
undal://vendor/order/{orderId}?sheet=accept
undal://vendor/order/{orderId}?sheet=reject
```

**The loop stops on exactly three things:** Accept, Reject, or the order being
answered on any other kitchen device (rung 8). Dismissing the notification does
not stop it — it is `setOngoing(true)` and `setAutoCancel(false)` — and neither
does a duplicate push, which only refreshes the text.

### The ring watches itself

A `MediaPlayer` can stop without telling anybody: the audio server restarts, a
ROM reclaims the session, the decoder gives up on the file, a descriptor is
withdrawn. The order is still unanswered, the notification is still on the lock
screen — and the kitchen is silent, which is the worst of the three states,
because it *looks* handled. Nothing about that failure is detectable from
outside the process, so the service checks on itself:

```kotlin
private val soundWatchdog: Runnable = object : Runnable {
    override fun run() {
        if (waiting.isEmpty()) return
        keepRinging()                                   // is anything actually making noise?
        handler.postDelayed(this, SOUND_CHECK_MILLIS)    // ten seconds
    }
}

private fun keepRinging() {
    if (waiting.isEmpty()) return
    val playing = player?.isPlaying == true
    if (ringtone == null && !playing) {
        player?.let { dead -> runCatching { dead.release() } }   // a dead player still
        player = null                                            // holds an audio session
        if (soundFailures < MAX_BUNDLED_ATTEMPTS) startSound()
        else if (now - lastFallbackAtMillis >= SOUND_CHECK_MILLIS) playSystemRinger()
    }
    if ((player?.isPlaying == true || ringtone != null) && vibrator == null) startVibration()
}
```

`keepRinging()` is called when the head of the queue changes and every ten
seconds while anything is waiting. A player that is playing — or the fallback
ringtone the replay loop owns — is left completely alone: nothing here may
stutter a healthy tone, because that would be audible.

**Two, then the fallback.** The failure budget exists so that a device that
genuinely cannot decode the file goes quiet instead of going *busy*: the first
failure is usually transient (an audio server restarting), the second is usually
the device telling the truth. After [MAX_BUNDLED_ATTEMPTS] the phone's own alarm
tone takes over, and that is retried no faster than once per tick. A rebuild loop
on the main thread would turn "no order sound" into "no app".

**Ten seconds** is the point where a silent phone is still a phone somebody will
pick up, and the check costs two field reads. The attempt counter is reset when a
fresh ring starts, so one bad night does not push the next order straight to the
fallback.

**What this cannot fix, honestly:** a phone muted at the hardware level, Do Not
Disturb with the kitchen's own exception missing, or an alarm stream that the OS
refuses to route all mute the ring regardless of who is running. Those are the
rows in the device acceptance matrix below (rung 11), and the diagnostics screen
says which of them is in the way instead of pretending the phone is armed.

The queue survives the process:

- `waiting` is a `LinkedHashMap`, so with two orders the head stays the oldest
  unanswered one and the second is queued rather than substituted;
- the queue is written to `SharedPreferences("order_alert_queue")` after
  foreground promotion and restored on a sticky restart, for the same signed-in
  uid only and within 15 minutes of the last save. A null-intent restart is a
  real ring, not a broken one: `onStartCommand` restores the queue and every
  restored order goes through the same `beginAlert` — foreground, player,
  vibration, notification, live watcher and watchdog — so a phone whose process
  was killed mid-ring comes back *ringing*, not merely notified;
- `ring()` is idempotent per order id, which matters because three routes
  (device token, topic, re-alert sweep) can deliver the same order;
- `silence(orderId)` corrects the saved queue even when the service is not
  running, so a later restore cannot ring an order somebody already answered;
- a diagnostics test rings exactly like a real order, labels itself `TEST ·`, and
  stops itself after 45 seconds. It is never restored from the queue.

If Android denies a background foreground-service start, `ring()` catches the
`RuntimeException` and posts a plain actionable notification instead:

```kotlin
} catch (error: RuntimeException) {
    Log.e(TAG, "Background alarm start denied; posting notification", error)
    AlarmChannels.ensureChannels(context)
    val notification = NotificationCompat.Builder(context, AlarmChannels.VENDOR_NEW_ORDER)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setContentIntent(open).setAutoCancel(true).build()
    runCatching { NotificationManagerCompat.from(context).notify(orderId.hashCode() and 0x7FFFFFFF, notification) }
}
```

That fallback is **not** a looping alarm and is not presented as one. It is what
an OS restriction leaves.

---

## 10. Rung 8 — the live fallback

Push is a delivery mechanism, not a guarantee. `KitchenWatchService` is a second,
independent route: a Firestore listener on live orders running inside its own
foreground service, plus a Realtime Database watcher that answers the only
question that matters while ringing.

```kotlin
// orderLive/{id}/status is written by whichever device answers the order. When
// it changes, this phone stops ringing for it — including when a second kitchen
// phone answered first.
private var liveReference: DatabaseReference? = null
// …
OrderAlertService.silence(applicationContext, orderId)
```

It is started from `onMessageReceived` (so a push also arms the listener) and from
the app itself. Its notification is on the `vendor_watch` channel at
`IMPORTANCE_MIN`: it proves the kitchen is armed without buzzing for it, because
an ongoing notification is what keeps a foreground service alive, not a way to get
attention.

`OrderAlertService.SOURCE_WATCH` is logged when this route raised the alert, so a
support call can tell "push worked" from "push failed and the watcher caught it" —
which is the difference between a device problem and a configuration problem.

---

## 11. Rungs 9–11 — volume, permissions, reboot

### Alarm volume

Everything is routed to `STREAM_ALARM`, so one number can defeat every other
guarantee: **if the alarm volume is zero, a fully armed phone rings with nothing
audible.** `AlertVolume` treats that as a blocking failure, not a note:

- the readiness screen reports the percentage and fails below `AUDIBLE_PERCENT = 50`
  (half of maximum is already quiet in a kitchen with an exhaust fan running);
- the owner can raise it with one tap;
- a ringing order raises it by itself, and only when it has to, restoring the
  owner's own value when the ring ends.

Writing volume needs `MODIFY_AUDIO_SETTINGS`, a normal permission granted at
install. A phone in Do Not Disturb can still refuse the write; the refusal is
caught, logged and reported, because a refused volume change must never take the
alert down with it.

### Permissions and OEM exemptions

`AlarmChannels` builds the intents; the diagnostics screen probes each with
`resolveActivity` before showing a button, so it never offers one that opens
nothing.

```kotlin
fun requestIgnoreBatteryOptimisations(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun fullScreenIntentSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    else appNotificationSettings(context)

fun exactAlarmSettings(): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun autostartCandidates(): List<Pair<String, Intent>> = listOf(
    "Xiaomi · Autostart"          to Intent().setClassName("com.miui.securitycenter",  "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    "Oppo · Startup manager"      to Intent().setClassName("com.coloros.safecenter",   "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    "Realme · Startup manager"    to Intent().setClassName("com.oplus.safecenter",     "com.oplus.safecenter.startupapp.StartupAppListActivity"),
    "Vivo · Autostart"            to Intent().setClassName("com.vivo.permissionmanager","com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    "Huawei · Startup manager"    to Intent().setClassName("com.huawei.systemmanager",  "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    "Samsung · Battery settings"  to Intent().setClassName("com.samsung.android.lool",  "com.samsung.android.sm.ui.battery.BatteryActivity"),
).map { (label, intent) -> label to intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
```

`USE_FULL_SCREEN_INTENT` is granted at install for calling and alarm apps and
**revoked by default for everything else** on Android 14+. It cannot be requested
at runtime; only that settings screen can grant it, which is why an ungranted
full-screen intent shows as a checklist item rather than a bug.

The lock-screen half lives in the activity:

```kotlin
// MainActivity
setShowWhenLocked(true)
setTurnScreenOn(true)
keyguardManager.requestDismissKeyguard(this, null)
```

Notification permission is asked for at the moment it means something — standing
in the store, where the promise is concrete — and a refusal is remembered rather
than nagged:

```kotlin
// core/fcm/NotificationConsent.kt
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return@LaunchedEffect
if (progress.notificationConsentAsked()) return@LaunchedEffect
ask = true
```

### Reboot and update

```xml
<receiver android:name=".core.background.BootReceiver" android:enabled="true" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
        <action android:name="android.intent.action.TIME_SET" />
    </intent-filter>
</receiver>
```

It re-creates the channels, schedules the heartbeat worker, re-registers the
token if anybody is signed in, and — for a vendor — starts the presence heartbeat
and `KitchenWatchService`, so a rebooted kitchen phone can ring for an order that
arrives before anybody touches it. For a customer it stops the watcher: a
customer device must never keep the kitchen's listener alive.

Queue restoration after a reboot is not guaranteed — Android decides whether to
restart a sticky service — and the saved queue has a 15-minute expiry precisely so
a phone that was off overnight does not wake up ringing for an order the kitchen
already handled.

### The watchdog: when Android kills the app anyway

`stopWithTask="false"` and `START_STICKY` cover the polite half of Android. The
other half is a battery cleaner, a low-memory kill, or an owner who swiped Undal
off the recents list — and the kitchen then looks armed while it is not:

| file | what it is |
| --- | --- |
| `core/background/AlertWatchdog.kt` | an `AlarmManager` alarm the *system* holds for Undal |
| `core/background/AlertWatchdogReceiver.kt` | what the alarm wakes up: rebuilt services, or a warning |

```kotlin
// AlertWatchdog
fun arm(context: Context, delayMillis: Long = WATCHDOG_MILLIS)   // 9 min
fun cancel(context: Context)                                     // any sign-out
fun isArmed(context: Context): Boolean                           // the diagnostics row
const val SWIPE_GRACE_MILLIS = 2 * 60 * 1000L                     // armed from onTaskRemoved

// AlertWatchdogReceiver
if (!AlertWatchdog.isKitchenDevice()) { AlertWatchdog.cancel(app); return }
AlarmChannels.ensureChannels(app)
PresenceHeartbeat.startService(app)
KitchenWatchService.start(app)
AlertWatchdog.arm(app)   // the chain continues whether or not this pass worked
```

**Why an alarm and not a service.** A service dies with the process; an alarm is
held by the system, addressed to the package, and delivered by handing the app a
fresh process. It is the only one of the two that survives the kill this section
is about.

**Why nine minutes.** The Worker's rule is that ten minutes of silence pauses an
open kitchen, so a watchdog on a slower cadence would be watching from behind.
Nine minutes is also the shortest interval Android still delivers to a Dozing
phone (a woken app gets one precise alarm per nine minutes) — asking for less
would buy nothing and cost battery.

**What the owner experiences.** The phone is swiped away or killed; within nine
minutes it has rebuilt its own order watcher and heartbeat, without anybody
touching it. If the pause the Worker applied was shorter than thirty minutes the
store reopens itself and a push says so; if it was longer, or outside the
kitchen's hours, the owner is asked to confirm instead — see `crons/presence.ts`.

**When it cannot work.** A ROM that refuses `setExactAndAllowWhileIdle`, or an
exact-alarm permission the owner never granted, leaves the alarm inexact: it
still fires, just not to the minute. If Android refuses the foreground-service
start when the alarm fires, the receiver posts a visible *"Undal could not
restart its alerts"* notification rather than failing silently, and the alarm is
re-armed anyway. The diagnostics screen reports all of this in words —
`AlertWatchdog.describe()` — with its own repair action, so a phone that is
quietly unarmed says so on the screen the owner already checks.

**On a customer phone** the alarm is never kept: the receiver checks the vendor
claim on delivery and cancels itself, and every sign-out path
(`UndalRootViewModel`, `VendorDashboardViewModel`, `ProfileViewModel`) stops the
watcher, the alert and the watchdog together.

---

## 12. The manifest

```xml
<!-- notifications + background survival -->
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

`MODIFY_AUDIO_SETTINGS` is a normal permission — granted at install, never
prompted — and it is what lets the alert raise the alarm stream on a phone whose
alarm volume is at zero.

```xml
<service
    android:name=".core.background.OrderAlertService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback|specialUse"
    android:stopWithTask="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="order-alerts" />
</service>

<service android:name=".core.fcm.UndalFcmService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

`stopWithTask="false"` is what lets the alarm survive a task swipe; the
`specialUse` subtype is required on Android 14+ and is the string a Play policy
review reads. The three other foreground services (`KitchenWatchService`,
`PrepTimerService`, `PresenceHeartbeatService`) are declared the same way with
their own subtypes.

The FCM metadata defaults exist for the case where a display payload ever does
arrive, so it lands on a real channel instead of Android's fallback one:

```xml
<meta-data android:name="com.google.firebase.messaging.default_notification_channel_id"
           android:value="@string/channel_customer_orders" />
<meta-data android:name="com.google.firebase.messaging.default_notification_icon"
           android:resource="@drawable/ic_undal_mark" />
<meta-data android:name="com.google.firebase.messaging.default_notification_color"
           android:resource="@color/undal_brand" />
```

---

## 13. Deliberate deviations, and why

Three places where this implementation does not do the literal thing a brief
might ask for. Each is a decision, recorded here so it is not "fixed" later by
somebody who has not seen the reason.

**`android.ttl` is `300s`, not `0s`.** A zero TTL tells FCM to discard the
message immediately if the device is not reachable at that instant — which is
exactly the case the ladder exists for. Five minutes overlaps with the every-
minute re-alert sweep, so a phone that comes back after four minutes still gets
the order it missed, and the sweep is the backstop rather than the only route.
`apns-expiration` is derived from the same number so neither platform keeps a
stale alert longer than the other. A caller can still pass `ttl: "0s"`;
`test/fcm-alert.test.ts` asserts both behaviours.

**The channel is `IMPORTANCE_HIGH`, not `IMPORTANCE_MAX`.** Android's own
settings UI clamps user-visible importance at "Urgent", which is `HIGH`, and
`MAX` is reserved for the system; asking for it changes nothing a user can see or
hear. What actually makes the alert loud is not the importance number but the
`USAGE_ALARM` audio attributes, `setBypassDnd(true)`, the public lock-screen
visibility, and — above all — the app's own looping `MediaPlayer`, which does not
depend on the notification being seen at all.

**`apns-priority` is `10` for a visible push and `5` for a data-only one.** A
background push (`content-available`, no alert) at priority 10 is refused by APNs
with `BadPriority`; `5` is the only legal value for that shape. Priority 10 is
therefore used where it is permitted — the customer's status notifications — and
the data-only kitchen alert takes `5` plus `content-available`. This repository
ships an Android app and no iOS client, so the block is inert today: FCM drops it
for Android tokens. It is here so that a kitchen running an iPhone works on the
day one appears instead of being silently throttled.

---

## 14. Deployment

Worker configuration comes from GitHub Actions in two shapes, and the difference
matters: a **secret** is pushed with `wrangler secret put` and never appears in
any file, while a **variable** is substituted into `wrangler.toml` from a GitHub
secret at deploy time and is readable by anyone who can read the deployed Worker.

Pushed as Cloudflare secrets:

| Secret | What it is |
| --- | --- |
| `FIREBASE_SA_JSON` | the service-account key the Worker mints OAuth2 tokens from |
| `FIREBASE_RTDB_URL` | the Realtime Database base URL (`orderLive` mirrors, chat) |
| `APP_SIGNING_SHA256` | the APK signing certificate fingerprint for `assetlinks.json` |
| `RELEASE_WEBHOOK_SECRET` | the shared secret behind `POST /admin/release` |

Substituted into `[vars]`, because they are public client values by design:

| Variable | What it is |
| --- | --- |
| `FIREBASE_WEB_API_KEY` | Firebase's *public* web API key: anonymous web sessions and the web account |
| `CLOUDINARY_CLOUD_NAME` | where menu photography is served from |
| `GITHUB_REPO_SLUG` | the repository the release webhook and the download page read |
| `APP_PACKAGE_NAME`, `PROMO_TOPIC`, `KITCHEN_TOPIC` | the wire vocabulary; `KITCHEN_TOPIC` must equal `AlertContract.KITCHEN_TOPIC` |

Leaving `FIREBASE_WEB_API_KEY` blank is a supported state, not a broken one: the
storefront stays readable and `/checkout`, `/login` and `/account` park
themselves with an honest message instead of failing at the last tap.

The `deploy-worker` workflow runs the whole ladder of checks before it deploys:
preflight on the secrets, `npm ci`, typecheck, the test suite, KV resolution, the
Firestore index deployment (non-fatal, with the console link in the log when it
cannot be created), `wrangler deploy --dry-run`, the deploy itself, and a smoke
test of `/healthz` plus `POST /notify/test-alert`. The `seed-config` workflow
deploys `firestore:rules`, `firestore:indexes` and the Realtime Database rules.

Run the local gates before pushing:

```bash
cd worker && npm run typecheck && npm test
cd .. && node tools/check-alert-ladder.mjs     # the wire vocabulary agrees on both sides
node tools/check-web-rules.mjs                 # money, phone, distance, validation agree
bash tools/check_no_placeholders.sh
python3 tools/check_import_contracts.py
python3 tools/check_kotlin_shape.py
```

---

## 15. Device acceptance matrix — run on real hardware

Nothing in this document is proven until the matrix below has been run on the
actual kitchen phone. Use Android 12, 13, 14, 15 and the target OEM. For each
row record: order creation time, notification arrival, first audible sound, time
to silence.

| Scenario | Verify |
| --- | --- |
| Foreground / home screen / locked screen | loop starts, order link works, Accept stops the sound |
| Task swiped away / process killed normally | FCM wake-up and service promotion; record actual latency |
| Device in Doze, alarm volume raised by the app | audible; the owner's volume returns afterwards |
| Kill the process while ringing | same-uid queue restores when the OS restarts the service; an answered order reconciles |
| Two orders plus duplicate pushes | both ids stay distinct; answering one leaves the other ringing |
| Answer on a second kitchen phone | the first phone stops; a stale callback never stops another order |
| Brief network loss then reconnect | a re-alert arrives inside its window; answered orders reconcile |
| Push disabled at the OS level | the Firestore watcher still rings the phone (`SOURCE_WATCH` in the log) |
| Notification permission revoked / channel muted | readiness reports the limitation; no crash; the alarm is still audible |
| Background service start denied | actionable notification fallback — recorded as a limitation, not a pass |
| Sign out then sign into another account | the old queue is not restored |
| Diagnostic test alert | self-stops after 45 s; never returns after a process restart |
| Reboot without opening the app | channels, token and heartbeat re-arm |
| Force stop / OEM battery saver | record the limitation and the owner's recovery steps |

---

## 16. Platform limits — do not promise guaranteed or instant delivery

- Swiping away a task and **Force stop** are different things. FCM can wake a
  normally terminated process; after Force stop, Android generally blocks
  delivery until the owner launches the app again. No code can fix this.
- An offline or powered-off phone cannot receive an immediate alert. The TTL and
  the re-alert sweep decide what happens when it comes back.
- OEM battery restrictions, Doze, FCM priority downgrading, Do Not Disturb and a
  disabled notification channel all affect delivery or visibility independently
  of this code.
- Full-screen intents require OS permission and eligibility; Android 14+ revokes
  `USE_FULL_SCREEN_INTENT` by default for apps that are not calling or alarm
  apps, and Play restricts the declaration. Do not assume an order app
  automatically qualifies.
- This repository contains an Android app, not an iOS app. The APNs headers are
  forward-looking and unexercised.
- The web is a customer storefront. Browsers cannot reliably play looping audio
  with the page closed or the device locked, so kitchen alerts are the app's job
  and no web-push delivery guarantee is offered or implied.

---

## 17. Troubleshooting

| Symptom | First thing to check | Where |
| --- | --- | --- |
| FCM accepts, nothing rings | `data.type` and `channelId` spelling on both sides | `tools/check-alert-ladder.mjs` |
| Nothing at all, no warning | `vendor/{uid}` document and the vendor claim | `GET /healthz`, vendor diagnostics |
| `tokens: 0` in the outcome | the kitchen phone has never registered a token | rung 0; open the app and run the readiness check |
| Rings in the app, silent when killed | battery optimisation and OEM autostart | rung 10 |
| Rings once, then never again | the channel was muted and never reset | `AlarmChannels.isMuted` |
| Audible only sometimes | alarm volume below 50% | `AlertVolume.percent` |
| Alert arrives late, in a batch | the message carried a `notification` block, or priority was not HIGH | `worker/test/fcm-alert.test.ts` |
| The re-alert cron errors every minute | a composite index the query does not need was added | `crons/realert.ts` |

---

## 18. Vendor history cleanup

Store settings → **Clear finished history** (confirmation `DELETE_FINISHED_HISTORY`
required) calls `POST /admin/cleanup` with a vendor token. Each request deletes at
most 5 delivered or cancelled orders with their chat metadata and live mirrors,
and up to 25 of the kitchen account's own notifications created before the
request. The app repeats bounded batches and the whole operation is safe to run
again. Active orders — placed, accepted, ready, in delivery — are never touched,
and deletion is permanent: export records first.

Three behaviours exist because of a real 502:

- **The composite index is a fast path, not a requirement.** Cleanup queries
  `orders(status, updatedAt)`, which needs a composite index that a project may
  never have deployed. When Firestore refuses with `FAILED_PRECONDITION`, the
  route falls back to a bounded scan (`SCAN_LIMIT = 200`) and reports
  `strategy: "scan"`. A merchant clearing history is never again told
  "Clear paused (HTTP 502)" because of a deployment step nobody remembered.
- **A missing RTDB URL no longer aborts a successful delete.** Live mirrors that
  cannot be cleared are queued in KV under `cleanup:pending-mirrors` and reported
  as `mirrorsPending`, with a warning naming `FIREBASE_RTDB_URL`.
- **Every refusal carries a `hint` and a `detail`.** `HttpError` forwards both,
  and the Android client shows them. The original bug was a Firestore message
  containing the console link that creates the missing index, flattened by the
  client into "you can safely try again".

`POST /admin/cleanup` with `{"dryRun": true}` runs `diagnoseCleanup`, which reports the
verdict, the strategy, a finished-order sample, the pending mirror count and the
index URL, without deleting anything. The deploy workflow now attempts
`firestore:indexes` on every deploy so the indexed path is the normal one.

---

## 19. Checks performed for this change

Worker typecheck clean; 114 Node tests passing across eight files — the cleanup
and its fallbacks, the upstream-failure classifier, the alert payload and ladder,
the re-alert windows, the SEO builders and crawler routes, the web account API
against a fake Firebase Authentication that signs real RS256 tokens, and the
rendered pages; `wrangler deploy --dry-run` packaging; 31 alert-ladder wiring
checks; 130 web/app rule comparisons; Kotlin source-shape and import-contract
checks over 128 files.

*(The counts above are the ones recorded when this change landed, and they are
left as they were written. The numbers for the tree as it stands — Kotlin files,
placeholder-gate files, Node tests, rule comparisons — are in the README's
acceptance table, §11.)*

These do not replace an APK build or the device matrix in §15. The Java/Android
SDK and a production Firebase project were not available in the editing
environment, and no production deployment was performed by it.
