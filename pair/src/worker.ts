/*
 * Talova pairing relay.
 *
 * Hands one sealed API key from the pairing page to a watch. The page encrypts the key in the browser
 * with the watch's public key (HPKE, see src/page/main.ts); this Worker only ever sees the ciphertext,
 * keeps it for at most ten minutes and deletes it the moment the watch has fetched it. Nothing is
 * logged, nothing is stored anywhere else.
 *
 *   POST /v1/pair/:id/open        the watch announces a fresh pairing code
 *   GET  /v1/pair/:id/status      the page asks whether the code is still valid / was picked up
 *   POST /v1/pair/:id             the page delivers {"sealed": "<base64url>"} (once)
 *   GET  /v1/pair/:id?wait=25     the watch waits for it (long poll): 200 {"sealed"}, 204, or 410
 *
 * Every pairing id gets its own Durable Object, so delivery is consistent and instant: the watch's
 * waiting request is answered the moment the page posts.
 */

import { DurableObject } from "cloudflare:workers";

export interface Env {
  ASSETS: Fetcher;
  PAIR: DurableObjectNamespace;
}

interface SlotApi {
  open(): Promise<Response>;
  status(): Promise<Response>;
  deliver(sealed: string): Promise<Response>;
  take(waitSeconds: number): Promise<Response>;
}

const ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;
const SEALED_PATTERN = /^[A-Za-z0-9_-]{60,4000}$/;
const SLOT_LIFETIME_MS = 10 * 60 * 1000;
const MAX_WAIT_S = 25;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/v1\/pair\/([^/]+)(\/open|\/status)?$/);
    if (!match) {
      if (url.pathname.startsWith("/v1/")) return json({ error: "not_found" }, 404);
      return env.ASSETS.fetch(request);
    }
    const [, id, action] = match;
    if (!ID_PATTERN.test(id)) return json({ error: "bad_id" }, 400);
    // The generated RPC stub type is too deep for tsc; the slot's few methods are declared by hand.
    const slot = env.PAIR.get(env.PAIR.idFromName(id)) as unknown as SlotApi;

    if (action === "/open" && request.method === "POST") return slot.open();
    if (action === "/status" && request.method === "GET") return slot.status();
    if (!action && request.method === "POST") {
      const body = await readSealed(request);
      return body ? slot.deliver(body) : json({ error: "bad_body" }, 400);
    }
    if (!action && request.method === "GET") {
      const wait = Math.min(MAX_WAIT_S, Math.max(0, Number(url.searchParams.get("wait") ?? "0") || 0));
      return slot.take(wait);
    }
    return json({ error: "method_not_allowed" }, 405);
  },
} satisfies ExportedHandler<Env>;

async function readSealed(request: Request): Promise<string | null> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > 8192) return null;
  try {
    const body = (await request.json()) as { sealed?: unknown };
    return typeof body.sealed === "string" && SEALED_PATTERN.test(body.sealed) ? body.sealed : null;
  } catch {
    return null;
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function empty(status: number): Response {
  return new Response(null, { status, headers: { "cache-control": "no-store" } });
}

type State = "open" | "sealed" | "done";

/** One pairing code. Lives at most ten minutes, then its alarm wipes it. */
export class PairSlot extends DurableObject<Env> {
  private waiting: Array<(sealed: string | null) => void> = [];

  private async read(): Promise<{ state: State; sealed?: string } | null> {
    const expires = await this.ctx.storage.get<number>("expires");
    if (!expires || expires < Date.now()) return null;
    const state = await this.ctx.storage.get<State>("state");
    return state ? { state, sealed: await this.ctx.storage.get<string>("sealed") } : null;
  }

  async open(): Promise<Response> {
    if (!(await this.read())) {
      const expires = Date.now() + SLOT_LIFETIME_MS;
      await this.ctx.storage.deleteAll();
      await this.ctx.storage.put({ state: "open", expires });
      await this.ctx.storage.setAlarm(expires);
    }
    return empty(204);
  }

  async status(): Promise<Response> {
    const slot = await this.read();
    return json({ state: slot?.state ?? "unknown" });
  }

  async deliver(sealed: string): Promise<Response> {
    const slot = await this.read();
    if (!slot) return json({ error: "expired" }, 410);
    if (slot.state !== "open") return json({ error: "used" }, 409);
    const waiter = this.waiting.shift();
    if (waiter) {
      // The watch is listening right now: hand it over without ever writing it to storage.
      await this.ctx.storage.put("state", "done");
      waiter(sealed);
    } else {
      await this.ctx.storage.put({ state: "sealed", sealed });
    }
    return empty(204);
  }

  async take(waitSeconds: number): Promise<Response> {
    const slot = await this.read();
    if (!slot || slot.state === "done") return empty(410);
    if (slot.state === "sealed" && slot.sealed) {
      await this.ctx.storage.delete("sealed");
      await this.ctx.storage.put("state", "done");
      return json({ sealed: slot.sealed });
    }
    if (waitSeconds === 0) return empty(204);
    const sealed = await new Promise<string | null>((resolve) => {
      this.waiting.push(resolve);
      setTimeout(() => {
        const index = this.waiting.indexOf(resolve);
        if (index >= 0) this.waiting.splice(index, 1);
        resolve(null);
      }, waitSeconds * 1000);
    });
    return sealed ? json({ sealed }) : empty(204);
  }

  async alarm(): Promise<void> {
    this.waiting.forEach((resolve) => resolve(null));
    this.waiting = [];
    await this.ctx.storage.deleteAll();
  }
}
