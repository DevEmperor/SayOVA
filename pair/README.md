# Parley pairing page and relay

Hands an API key from a browser to the Parley watch app, end-to-end encrypted.

```
watch ── POST /v1/pair/:id/open ──────────────►┐
      ◄── GET  /v1/pair/:id?wait=25 (long poll) ┤  Worker + one Durable Object per pairing id
browser ── GET  /v1/pair/:id/status ───────────►│  (holds ciphertext ≤ 10 min, deletes on pickup)
        ── POST /v1/pair/:id {"sealed": …} ─────►┘
```

- `src/page/crypto.ts` — HPKE (RFC 9180, DHKEM-X25519, HKDF-SHA256, AES-128-GCM) in the
  browser. The watch side is `core/src/main/kotlin/…/pairing/Pairing.kt`; the test vector in
  `core/src/test/resources/pairing-vector.json` (made with `npm run vector`) proves both agree.
- `public/orb.png` — a still of the watch's cloud orb (orb-ui Cloud theme, MIT), rendered offline;
  `src/page/orb.ts` draws it live. `favicon.png` and `apple-touch-icon.png` are the app icon (the orb
  in its glow on a dark disc), rendered from the same still.
- `src/page/main.ts`, `public/` — the page, built to `public/app.js` with esbuild. No third-party
  scripts; a strict Content-Security-Policy is set in `public/_headers`.
- `src/worker.ts` — the relay. It sees only ciphertext and keeps no logs.

## Local development

```bash
npm ci
npm run dev        # wrangler dev, page and relay on http://localhost:8787
npm run typecheck
```

## Deploying

```bash
npx wrangler login
npm run deploy     # prints the address, e.g. https://parley-pair.<account>.workers.dev
```

Then build the watch app against that address:
`./gradlew :wear:assembleRelease -Pparley.pairBaseUrl=https://parley-pair.<account>.workers.dev`
(or set `parley.pairBaseUrl` in `gradle.properties`). A custom domain can be added later in
`wrangler.jsonc` under `routes`; old app versions keep the address they were built with.

The free Workers plan is enough: one pairing costs a handful of requests and a few seconds of a
Durable Object.
