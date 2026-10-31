# SayOVA website

The website on [sayova.app](https://sayova.app) (and www.sayova.app), in the watch app's 13 languages: static
files served by the Cloudflare Worker `sayova-site` (assets only, no Worker code). It is kept apart from the
pairing page and relay on pair.sayova.app ([pair/](../pair)).

- `src/index.html` is the page, with `{{key}}` for a text from `src/locales/<language>.json` (en.json is the
  source; a text missing in a language falls back to English) and `{{@name}}` for what the build fills in.
- `public/` holds the files served as they are: style, fonts, screenshots, the video.
- `build.mjs` puts it all together in `dist/` (not committed): one page per language (English at `/`, the
  others at `/de/`, `/fr/`, …), `lang.js` that sends a visitor to the page in their browser's language, the
  sitemap, and `main.js` bundled from `src/main.ts`.

The watch at the top plays the app's own example conversation: its texts come from the app's strings.xml,
its voices from `wear/src/main/assets/sample/` (as Ogg Opus, plus AAC made with ffmpeg for browsers without
Opus). The orb is drawn live with WebGL by the pairing page's [orb.ts](../pair/src/page/orb.ts) and stirs
with the voice; without WebGL the still `orb.png` stays.

Until Google Play has published SayOVA, `PLAY_LIVE` in `build.mjs` is false and the page says it is coming.
To switch, set it to true and put Google's official badge for every language into `public/badges/` (named
like `de.png`).

The privacy policy is not on the website but in the repository ([PRIVACY_POLICY.md](../PRIVACY_POLICY.md)),
as for DevEmperor's other apps. Contact: contact@devemperor.net.

Font: [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) (weight and roundness axes,
Latin), under the SIL Open Font License ([public/fonts/OFL.txt](public/fonts/OFL.txt)).

## Building and deploying

```bash
cd site
node build.mjs                         # needs ffmpeg and pair/'s node_modules
npx --prefix ../pair wrangler deploy   # runs build.mjs itself first
```

The custom domains sayova.app and www.sayova.app are set under `routes` in `wrangler.jsonc`; Cloudflare
creates their DNS records and certificates itself.
