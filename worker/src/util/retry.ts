/**
 * Bounded retries for the Google APIs behind this Worker.
 *
 * Firestore, the Realtime Database and FCM all answer with a transient failure
 * now and then: a 429 when a project crosses a read quota for one second, a 503
 * when a backend is being moved, a socket that closes on a cold connection. The
 * code that used to meet those failures turned them straight into an HTTP 502
 * for the caller — and the caller, a merchant tapping "Clear finished history"
 * at midnight, read "you can safely try again" and got the same answer forever,
 * because the real cause (a missing composite index) was not transient at all.
 *
 * So retries live here, in one place, with two rules:
 *
 *   1. **Only what is safe to repeat is repeated.** Reads and deletes are
 *      idempotent by nature. A create-or-overwrite write is repeated only on the
 *      statuses that are refused *before* they are applied (429 and the
 *      transport errors), never on a 500 that may already have landed.
 *   2. **A retry is never the whole story.** The final failure is returned with
 *      its upstream detail attached, so the route can tell a merchant what to
 *      do instead of asking them to guess.
 *
 * Everything here is pure apart from `sleep`, which is why the backoff curve can
 * be unit tested without a network.
 */

export interface RetryPolicy {
  /** Total attempts, including the first one. 1 means "do not retry". */
  attempts: number;
  /** Delay before the second attempt; it doubles on each further attempt. */
  baseDelayMs: number;
  /** Ceiling for one delay, so a long chain cannot exceed a Worker's budget. */
  maxDelayMs: number;
}

/** Reads: cheap, idempotent, and the most common victim of a one-second quota. */
export const READ_RETRY: RetryPolicy = { attempts: 3, baseDelayMs: 150, maxDelayMs: 1200 };

/** Deletes: repeating one is a no-op the second time, so the same budget is safe. */
export const DELETE_RETRY: RetryPolicy = { attempts: 3, baseDelayMs: 200, maxDelayMs: 1500 };

/**
 * Writes that create or overwrite: retried only for a refusal that happens
 * before the write is applied. Expressed as one attempt plus an explicit
 * status allow-list at the call site, never as a blanket repeat.
 */
export const WRITE_RETRY: RetryPolicy = { attempts: 2, baseDelayMs: 200, maxDelayMs: 800 };

/** No retry at all — the caller has its own recovery path. */
export const NO_RETRY: RetryPolicy = { attempts: 1, baseDelayMs: 0, maxDelayMs: 0 };

/** Statuses that mean "the request was refused, nothing was written". */
const REFUSED_BEFORE_APPLYING = new Set([408, 425, 429, 502, 503, 504]);

/** Statuses worth repeating on an idempotent call. */
const TRANSIENT = new Set([408, 425, 429, 500, 502, 503, 504]);

export function isTransientStatus(status: number): boolean {
  return TRANSIENT.has(status);
}

export function isRefusedBeforeApplying(status: number): boolean {
  return REFUSED_BEFORE_APPLYING.has(status);
}

/**
 * Exponential backoff with full jitter.
 *
 * Jitter matters here more than it looks: the re-alert cron and a merchant's
 * cleanup both fan out to the same project, and a fixed delay makes every
 * retrying caller arrive at the same instant they were refused.
 */
export function backoffDelayMs(
  attempt: number,
  policy: RetryPolicy,
  random: () => number = Math.random,
): number {
  if (attempt <= 1) return 0;
  const exponential = policy.baseDelayMs * 2 ** (attempt - 2);
  const capped = Math.min(policy.maxDelayMs, exponential);
  return Math.round(capped * (0.5 + random() / 2));
}

export function sleep(milliseconds: number): Promise<void> {
  if (milliseconds <= 0) return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/** Releases a response body we decided not to read, so the socket is reusable. */
async function discard(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    /* an already-consumed body cannot be cancelled; nothing to do */
  }
}

export interface FetchRetryOptions {
  policy?: RetryPolicy;
  /** Defaults to "repeat anything transient", which is right for reads. */
  retryStatus?: (status: number) => boolean;
  /** Written to the Worker log when an attempt is repeated. */
  label?: string;
}

/**
 * `fetch` with a bounded retry.
 *
 * The last response is returned even when it is a failure: the caller decides
 * what a 400 or a 404 means, and only the retryable statuses are absorbed here.
 * A transport error on the final attempt is rethrown unchanged.
 */
export async function fetchWithRetry(
  url: string,
  init: RequestInit = {},
  options: FetchRetryOptions = {},
): Promise<Response> {
  const policy = options.policy ?? READ_RETRY;
  const retryStatus = options.retryStatus ?? isTransientStatus;
  const attempts = Math.max(1, policy.attempts);
  let transportError: unknown = null;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, init);
      if (attempt < attempts && retryStatus(response.status)) {
        const delay = backoffDelayMs(attempt + 1, policy);
        if (options.label) {
          console.warn(`${options.label} retried`, JSON.stringify({ status: response.status, attempt, delay }));
        }
        await discard(response);
        await sleep(delay);
        continue;
      }
      return response;
    } catch (error) {
      transportError = error;
      if (attempt >= attempts) break;
      if (options.label) {
        console.warn(`${options.label} transport retry`, JSON.stringify({ attempt, message: String(error) }));
      }
      await sleep(backoffDelayMs(attempt + 1, policy));
    }
  }
  throw transportError ?? new Error("fetch failed");
}
