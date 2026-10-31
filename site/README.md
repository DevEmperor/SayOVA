# SayOVA website

The website on [sayova.app](https://sayova.app) (and www.sayova.app), in the watch app's 13 languages: static
files served by the Cloudflare Worker `sayova-site` (assets only, no Worker code). It is kept apart from the
pairing page and relay on pair.sayova.app ([pair/](../pair)).

- `src/index.html` is the page, with `{{key}}` for a text from `src/locales/<language>.json` (en.json is the
  source; a text missing in a language falls back to English) and `{{@name}}` for what the build fills in.
- `public/` holds the files served as they are: style, fonts, screenshots, the video, and the legal notice
  (`impressum/`, German and English, linked from every page's footer and kept out of search results).
- `build.mjs` puts it all together in `dist/` (not committed): one page per language (English at `/`, the
  others at `/de/`, `/fr/`, …), `lang.js` that sends a visitor to the page in their browser's language, the
  sitemap, and `main.js` bundled from `src/main.ts`.

The watch at the top plays the app's own example conversation: its texts come from the app's strings.xml,
its voices from `wear/src/main/assets/sample/` (as Ogg Opus, plus AAC made with ffmpeg for browsers without
Opus). The orb is drawn live with WebGL by the pairing page's [orb.ts](../pair/src/page/orb.ts) and stirs
with the voice; without WebGL the still `orb.png` stays.

Behind the page, `src/sky.ts` draws the orb's sky over the whole window, dimmed and drifting (WebGL at a
fifth of the window's size, 30 frames a second, one still frame for reduced motion).

The Google Play badge (top and bottom) is Google's own, per language in `public/badges/`, its transparent
margin trimmed. With `PLAY_LIVE` in `build.mjs` set to false the page says "Coming soon" instead.

The privacy policy is not on the website but in the repository ([PRIVACY_POLICY.md](../PRIVACY_POLICY.md)),
as for DevEmperor's other apps. Contact: contact@devemperor.net. Donations: paypal.me/DevEmperor.

Fonts, both under the SIL Open Font License: [Newsreader](https://fonts.google.com/specimen/Newsreader) for
headings ([OFL-Newsreader.txt](public/fonts/OFL-Newsreader.txt)) and
[Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) for the rest
([OFL.txt](public/fonts/OFL.txt)), Latin only; other scripts use the system's fonts. Icons: Material Symbols
(Apache 2.0) and Simple Icons (CC0); the flags in the language menu: flag-icons (MIT,
[public/flags/LICENSE.txt](public/flags/LICENSE.txt)).

## Building and deploying

```bash
cd site
node build.mjs                         # needs ffmpeg and pair/'s node_modules
npx --prefix ../pair wrangler deploy   # runs build.mjs itself first
```

The custom domains sayova.app and www.sayova.app are set under `routes` in `wrangler.jsonc`; Cloudflare
creates their DNS records and certificates itself.
