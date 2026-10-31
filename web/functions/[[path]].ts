/**
 * Cloudflare Pages entry point: every request this project's static files do not
 * answer lands here.
 *
 * The file is deliberately three lines. Everything it does lives in `web/src/`,
 * which the worker's typechecker reads and `worker/test/pages.test.ts` runs —
 * a Pages entry point that cannot be tested is a Pages entry point that is only
 * ever tested in production.
 */
import { servePagesRequest, type PagesContext } from "../src/serve";

export async function onRequest(context: PagesContext): Promise<Response> {
  return servePagesRequest(context);
}
