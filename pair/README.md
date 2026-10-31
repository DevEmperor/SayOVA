# Talova pairing page and relay

Hands an API key from a browser to the Talova watch app, end-to-end encrypted.

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
  `src/page/orb.ts` draws it live. `favicon.png`, `apple-touch-icon.png` and `icon.png` are the app
  icon: the orb edge to edge, rendered from the same shader.
- `src/page/main.ts`, `public/` — the page, built to `public/app.js` with esbuild. No third-party
  scripts; a strict Content-Security-Policy is set in `public/_headers`.
- `src/page/share.ts`, `public/share.html` — the share page at `/share`, built to `public/share.js`:
  the watch's "Send to phone" opens it with a conversation as UTF-8 in base64url after `#t=`, which
  never reaches the server; the page shows it with Share (the phone's share sheet) and Copy. With a
  calendar event after `#e=` (JSON, see `PhoneLinks.event` in `core/`) it shows the event, and Add to
  calendar hands the phone's own calendar app an iCalendar file; Google Calendar's page is offered too.
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
npm run deploy     # prints the address, e.g. https://talova-pair.<account>.workers.dev
```

Then build the watch app against that address:
`./gradlew :wear:assembleRelease -Ptalova.pairBaseUrl=https://talova-pair.<account>.workers.dev`
(or set `talova.pairBaseUrl` in `gradle.properties`). A custom domain can be added later in
`wrangler.jsonc` under `routes`; old app versions keep the address they were built with.

The free Workers plan is enough: one pairing costs a handful of requests and a few seconds of a
Durable Object.

## Languages

The page speaks the watch app's languages: `src/page/locales/*.json`, one flat file per language, picked
from the browser's preferred languages (English otherwise; `?lang=de` overrides, for testing). Every new
text goes into all of them; `{name}` marks a highlighted part that `i18n.ts` fills in as an element, and
`watch_*` keys must read exactly like the watch's own buttons.
