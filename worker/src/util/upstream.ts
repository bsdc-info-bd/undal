/**
 * Reading a Google API's refusal properly.
 *
 * Firestore, the Realtime Database, FCM and Firebase Auth all answer a failure
 * with a JSON body that says exactly what went wrong — including, for the single
 * most common Firestore mistake, a ready-made console link that creates the
 * missing index. The Worker used to throw all of that away and answer `502
 * firestore-query-failed`, which reached a merchant as "Cleanup paused (HTTP
 * 502). You can safely try again." Retrying could never help: the index was
 * still missing.
 *
 * Everything in this file is pure, so the classification is unit tested against
 * the real bodies Google sends rather than assumed.
 */

import { isTransientStatus } from "./retry";

export interface UpstreamFailure {
  /** The HTTP status the upstream service answered with. */
  httpStatus: number;
  /** Google's own name for it: `FAILED_PRECONDITION`, `PERMISSION_DENIED`, … */
  googleStatus: string;
  /** The upstream message, trimmed to something safe to put in a JSON body. */
  message: string;
  /** True when Firestore is asking for a composite index that does not exist. */
  needsIndex: boolean;
  /** The console link Firestore offers for creating it, when there is one. */
  indexUrl: string | null;
  /** True when repeating the call could succeed on its own. */
  retryable: boolean;
}

const INDEX_URL = /https:\/\/console\.firebase\.google\.com\/[^\s"'<)]+/i;
const NEEDS_INDEX = /requires an index|no matching index|missing index|FAILED_PRECONDITION/i;

function trimMessage(value: string): string {
  const single = value.replace(/\s+/g, " ").trim();
  return single.length > 400 ? `${single.slice(0, 400)}…` : single;
}

/** Parses the many shapes a Google refusal arrives in. */
export function classifyUpstreamFailure(httpStatus: number, rawBody: string): UpstreamFailure {
  const body = (rawBody ?? "").slice(0, 8192);
  let googleStatus = "";
  let message = "";

  try {
    const parsed = JSON.parse(body) as {
      error?: { code?: number; message?: string; status?: string } | string;
    };
    if (typeof parsed.error === "string") {
      // The Realtime Database answers `{ "error": "Permission denied" }`.
      message = parsed.error;
    } else if (parsed.error) {
      message = parsed.error.message ?? "";
      googleStatus = parsed.error.status ?? "";
    }
  } catch {
    message = body.startsWith("<") ? "The service answered with HTML instead of JSON." : body;
  }

  if (!message) message = `HTTP ${httpStatus}`;
  const needsIndex = /requires an index|no matching index|missing index/i.test(message)
    || (googleStatus === "FAILED_PRECONDITION" && NEEDS_INDEX.test(`${googleStatus} ${message}`));
  const indexMatch = INDEX_URL.exec(message);

  return {
    httpStatus,
    googleStatus: googleStatus || `HTTP_${httpStatus}`,
    message: trimMessage(message),
    needsIndex,
    indexUrl: indexMatch ? indexMatch[0] : null,
    retryable: isTransientStatus(httpStatus),
  };
}

/**
 * Turns a classification into the two strings a client shows: what happened, and
 * what to do about it. `subject` is the thing that failed, in merchant language
 * ("the finished-order query", "the live-order mirror"), never a URL.
 */
export function upstreamSentence(
  subject: string,
  failure: UpstreamFailure,
): { message: string; hint: string } {
  if (failure.needsIndex) {
    return {
      message: `Firestore refused ${subject}: it needs a composite index that this project does not have yet.`,
      hint: failure.indexUrl
        ? `Create the index at ${failure.indexUrl} or re-run the seed-config workflow, which deploys firebase/firestore.indexes.json. Indexes take a few minutes to build.`
        : "Re-run the seed-config workflow, which deploys firebase/firestore.indexes.json. Indexes take a few minutes to build.",
    };
  }
  if (failure.httpStatus === 401 || failure.httpStatus === 403 || failure.googleStatus === "PERMISSION_DENIED") {
    return {
      message: `Google refused to let the Worker reach ${subject} (${failure.googleStatus}).`,
      hint:
        "FIREBASE_SA_JSON must be a private key from this same Firebase project, and the service account needs access to it. "
        + "For the Realtime Database, FIREBASE_RTDB_URL must be the URL shown at the top of that database's tab.",
    };
  }
  if (failure.httpStatus === 404) {
    return {
      message: `Google could not find ${subject} (404).`,
      hint:
        "Check FIREBASE_RTDB_URL points at a Realtime Database that exists in this project, and that the Firestore database is the default one.",
    };
  }
  if (failure.retryable) {
    return {
      message: `${subject} is temporarily unavailable (${failure.googleStatus}). Undal already retried.`,
      hint: "This one really is worth another try in a minute — it is Google's service, not your data.",
    };
  }
  return {
    message: `${subject} was refused: ${failure.message}`,
    hint: "Nothing in the app can fix this. The Worker log line above it carries the full upstream answer.",
  };
}
