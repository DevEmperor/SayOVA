# SayOVA website

The website on [sayova.app](https://sayova.app) (and www.sayova.app): for now a single placeholder page,
static files in `public/` served by the Cloudflare Worker `sayova-site` (assets only, no Worker code). It
is kept apart from the pairing page and relay on pair.sayova.app ([pair/](../pair)).

The orb is drawn live with WebGL by the pairing page's own [orb.ts](../pair/src/page/orb.ts), bundled from
`src/main.ts` into `public/orb.js` before every deploy (not committed). Without WebGL the still `orb.png`
stays.

The privacy policy is not on the website but in the repository ([PRIVACY_POLICY.md](../PRIVACY_POLICY.md)),
as for DevEmperor's other apps. Contact: contact@devemperor.net.

## Deploying

```bash
cd site
npx --prefix ../pair wrangler deploy   # uses the wrangler installed for pair/
```

The custom domains sayova.app and www.sayova.app are set under `routes` in `wrangler.jsonc`; Cloudflare
creates their DNS records and certificates itself.
