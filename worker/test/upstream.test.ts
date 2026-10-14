/**
 * Reading a Google refusal properly.
 *
 * These are the bodies Google actually sends. The first one is the reason a
 * merchant saw "Cleanup paused (HTTP 502). You can safely try again." for a
 * problem that retrying could never fix — and the answer Google gave, including
 * a link that creates the missing index, was thrown away on the floor.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { classifyUpstreamFailure, upstreamSentence } from "../src/util/upstream";
import { FirestoreError } from "../src/firestore";
import { RtdbError } from "../src/rtdb";
import { HttpError, errorJson } from "../src/util/http";
import { backoffDelayMs, isRefusedBeforeApplying, isTransientStatus, READ_RETRY, WRITE_RETRY } from "../src/util/retry";

const INDEX_BODY = JSON.stringify({
  error: {
    code: 400,
    message:
      "The query requires an index. You can create it here: https://console.firebase.google.com/v1/r/project/undal-kitchen/firestore/indexes?create_composite=CicS",
    status: "FAILED_PRECONDITION",
  },
});

test("a missing composite index is recognised and its console link is kept", () => {
  const failure = classifyUpstreamFailure(400, INDEX_BODY);
  assert.equal(failure.needsIndex, true);
  assert.equal(failure.googleStatus, "FAILED_PRECONDITION");
  assert.equal(failure.retryable, false, "retrying a missing index changes nothing");
  assert.ok(failure.indexUrl?.startsWith("https://console.firebase.google.com/v1/r/project/undal-kitchen/firestore/indexes"));

  const sentence = upstreamSentence("the delivered-order history query", failure);
  assert.match(sentence.message, /needs a composite index/);
  assert.match(sentence.hint, /console\.firebase\.google\.com/);
  assert.match(sentence.hint, /seed-config/);
});

test("a permission refusal blames the credential, not the data", () => {
  const failure = classifyUpstreamFailure(403, JSON.stringify({ error: { code: 403, message: "Missing or insufficient permissions.", status: "PERMISSION_DENIED" } }));
  const sentence = upstreamSentence("the live mirror at orderLive", failure);
  assert.match(sentence.message, /PERMISSION_DENIED/);
  assert.match(sentence.hint, /FIREBASE_SA_JSON/);
});

test("the Realtime Database's own error shape is read", () => {
  const failure = classifyUpstreamFailure(401, JSON.stringify({ error: "Permission denied" }));
  assert.equal(failure.message, "Permission denied");
  const sentence = upstreamSentence("the live mirrors", failure);
  assert.match(sentence.hint, /FIREBASE_RTDB_URL/);
});

test("a database that does not exist says which variable to check", () => {
  const sentence = upstreamSentence("the live mirror at orderLive", classifyUpstreamFailure(404, JSON.stringify({ error: "Not Found" })));
  assert.match(sentence.message, /404/);
  assert.match(sentence.hint, /FIREBASE_RTDB_URL/);
});

test("a transient refusal is marked retryable and says so honestly", () => {
  const failure = classifyUpstreamFailure(503, JSON.stringify({ error: { code: 503, message: "Backend Error.", status: "UNAVAILABLE" } }));
  assert.equal(failure.retryable, true);
  const sentence = upstreamSentence("the finished-order query", failure);
  assert.match(sentence.message, /temporarily unavailable/);
  assert.match(sentence.hint, /another try/);
});

test("a body that is not JSON at all still produces a sentence", () => {
  const failure = classifyUpstreamFailure(502, "<html><body>Bad Gateway</body></html>");
  assert.match(failure.message, /HTML instead of JSON/);
  assert.equal(failure.needsIndex, false);
  const empty = classifyUpstreamFailure(500, "");
  assert.equal(empty.message, "HTTP 500");
});

test("the Firestore and RTDB errors carry the hint all the way to the JSON body", async () => {
  const fs = new FirestoreError("the delivered-order history query", "firestore-query-failed", classifyUpstreamFailure(400, INDEX_BODY));
  assert.equal(fs.status, 502);
  assert.equal(fs.failure.needsIndex, true);
  assert.ok(fs.hint?.includes("console.firebase.google.com"));
  assert.equal(fs.detail?.needsIndex, true);

  const rtdb = new RtdbError("the live mirrors", "rtdb-update-failed", classifyUpstreamFailure(401, JSON.stringify({ error: "Permission denied" })));
  assert.ok(rtdb.hint?.includes("FIREBASE_RTDB_URL"));

  const body = (await errorJson(fs.status, fs.code, fs.message, { hint: fs.hint, detail: fs.detail }).json()) as Record<string, unknown>;
  assert.equal(body.ok, false);
  assert.equal(body.error, "firestore-query-failed");
  assert.match(String(body.hint), /console\.firebase\.google\.com/);
  assert.ok((body.detail as Record<string, unknown>).indexUrl, "the console link reaches the client");
});

test("a plain error still serialises without a hint", async () => {
  const body = (await errorJson(400, "bad", "No good").json()) as Record<string, unknown>;
  assert.deepEqual(Object.keys(body).sort(), ["error", "message", "ok"]);
  assert.ok(new HttpError(400, "x").hint === undefined);
});

test("backoff grows, stays inside its ceiling and never delays the first attempt", () => {
  assert.equal(backoffDelayMs(1, READ_RETRY, () => 0.5), 0);
  const fixed = () => 1; // the top of the jitter band, for a comparable curve
  const second = backoffDelayMs(2, READ_RETRY, fixed);
  const third = backoffDelayMs(3, READ_RETRY, fixed);
  assert.ok(second > 0 && third >= second, `${second} → ${third}`);
  assert.ok(backoffDelayMs(9, READ_RETRY, fixed) <= READ_RETRY.maxDelayMs);
  assert.ok(backoffDelayMs(2, READ_RETRY, () => 0) >= READ_RETRY.baseDelayMs / 2);
});

test("writes are only repeated for a refusal that happens before they are applied", () => {
  assert.equal(isRefusedBeforeApplying(429), true);
  assert.equal(isRefusedBeforeApplying(503), true);
  assert.equal(isRefusedBeforeApplying(500), false, "a 500 may already have written the document");
  assert.equal(isTransientStatus(500), true, "a read may be repeated after a 500");
  assert.equal(isTransientStatus(404), false);
  assert.equal(isTransientStatus(400), false);
  assert.ok(WRITE_RETRY.attempts <= READ_RETRY.attempts);
});
