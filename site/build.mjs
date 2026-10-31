/*
 * Builds SayOVA's website into dist/: the files in public/ as they are, one page per language from
 * src/index.html and src/locales/, the example conversation from the watch app (its texts in the app's
 * strings.xml, its voices in the app's assets), and the script bundle. wrangler runs it before every
 * deploy (build.command in wrangler.jsonc); by hand: node build.mjs
 */
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const SITE = fileURLToPath(new URL(".", import.meta.url));
const DIST = join(SITE, "dist");
const APP = join(SITE, "../wear/src/main");
const ORIGIN = "https://sayova.app";
const PLAY = "https://play.google.com/store/apps/details?id=net.devemperor.sayova";

// Before Google Play had published SayOVA, the page said it was coming instead of linking there.
const PLAY_LIVE = true;

// Icons: Material Symbols "menu_book" (Apache 2.0) for the guide; GitHub and PayPal from Simple Icons (CC0).
const ICONS = {
  icon_guide:
    "M248-300q53.57 0 104.28 12.5Q403-275 452-250v-427q-45-30-97.62-46.5Q301.76-740 248-740q-38 0-74.5 9.5T100-707v434q31-14 70.5-20.5T248-300Zm264 50q50-25 98-37.5T712-300q38 0 78.5 6t69.5 16v-429q-34-17-71.82-25-37.82-8-76.18-8-54 0-104.5 16.5T512-677v427Zm-30 79q-7.54 0-14.27-1.5Q461-174 456-178q-47-29-99.85-45-52.84-16-108.15-16-36.54 0-71.77 9T106-208q-23.1 11-44.55-3Q40-225 40-251v-463q0-15 7-27.5T68-761q42-20 87.39-29.5 45.4-9.5 92.61-9.5 63 0 122.5 17T482-731q51-35 109.5-52T712-800q46.87 0 91.93 9.5Q849-781 891-761q14 7 21.5 19.5T920-714v463q0 27.89-22.5 42.45Q875-194 853-208q-34-14-69.23-22.5Q748.54-239 712-239q-54.27 0-106.14 16Q554-207 508-178q-5 4-11.73 5.5T482-171Z",
  icon_github:
    "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12",
  icon_paypal:
    "M7.016 19.198h-4.2a.562.562 0 0 1-.555-.65L5.093.584A.692.692 0 0 1 5.776 0h7.222c3.417 0 5.904 2.488 5.846 5.5-.006.25-.027.5-.066.747A6.794 6.794 0 0 1 12.071 12H8.743a.69.69 0 0 0-.682.583l-.325 2.056-.013.083-.692 4.39-.015.087zM19.79 6.142c-.01.087-.01.175-.023.261a7.76 7.76 0 0 1-7.695 6.598H9.007l-.283 1.795-.013.083-.692 4.39-.134.843-.014.088H6.86l-.497 3.15a.562.562 0 0 0 .555.65h3.612c.34 0 .63-.249.683-.585l.952-6.031a.692.692 0 0 1 .683-.584h2.126a6.793 6.793 0 0 0 6.707-5.752c.306-1.95-.466-3.744-1.89-4.906z",
};

/** A PNG's width and height, from its header. */
function pngSize(file) {
  const header = readFileSync(file).subarray(16, 24);
  return [header.readUInt32BE(0), header.readUInt32BE(4)];
}

// The watch app's languages: the page's path, the app's resource folder, the name in the menu.
const LANGS = [
  { code: "en", path: "/", res: "values", name: "English" },
  { code: "de", path: "/de/", res: "values-de", name: "Deutsch" },
  { code: "fr", path: "/fr/", res: "values-fr", name: "Français" },
  { code: "es", path: "/es/", res: "values-es", name: "Español" },
  { code: "it", path: "/it/", res: "values-it", name: "Italiano" },
  { code: "pt-BR", path: "/pt-br/", res: "values-pt-rBR", name: "Português (Brasil)" },
  { code: "nl", path: "/nl/", res: "values-nl", name: "Nederlands" },
  { code: "pl", path: "/pl/", res: "values-pl", name: "Polski" },
  { code: "sv", path: "/sv/", res: "values-sv", name: "Svenska" },
  { code: "tr", path: "/tr/", res: "values-tr", name: "Türkçe" },
  { code: "ja", path: "/ja/", res: "values-ja", name: "日本語" },
  { code: "ko", path: "/ko/", res: "values-ko", name: "한국어" },
  { code: "zh-CN", path: "/zh-cn/", res: "values-zh-rCN", name: "简体中文" },
];

// The example conversation in the order it is played; q is the user's voice, a is SayOVA's.
const SAMPLE = ["q1", "a1", "q2", "a2", "q3", "a3"];

const escape = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** The app's strings for one language: name → text, with Android's escapes undone. */
function appStrings(res) {
  const xml = readFileSync(join(APP, "res", res, "strings.xml"), "utf8");
  const strings = {};
  for (const [, name, raw] of xml.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    strings[name] = raw
      .replace(/\\'/g, "'")
      .replace(/\\"/g, '"')
      .replace(/\\n/g, " ")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
  }
  return strings;
}

const english = JSON.parse(readFileSync(join(SITE, "src/locales/en.json"), "utf8"));
const englishApp = appStrings("values");
const template = readFileSync(join(SITE, "src/index.html"), "utf8");

rmSync(DIST, { recursive: true, force: true });
cpSync(join(SITE, "public"), DIST, { recursive: true });

const alternates = [
  ...LANGS.map((l) => `<link rel="alternate" hreflang="${l.code}" href="${ORIGIN}${l.path}">`),
  `<link rel="alternate" hreflang="x-default" href="${ORIGIN}/">`,
].join("\n  ");

for (const lang of LANGS) {
  const file = join(SITE, "src/locales", `${lang.code}.json`);
  const own = existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : {};
  const missing = Object.keys(english).filter((key) => !(key in own));
  if (lang.code !== "en" && missing.length) console.warn(`${lang.code}: ${missing.length} texts in English`);
  const t = { ...english, ...own };
  const app = { ...englishApp, ...appStrings(lang.res) };

  // What the demo on the watch needs, as data the script reads (never run as code).
  const demo = {
    lang: lang.code,
    lines: SAMPLE.map((id) => ({ who: id[0], text: app[`sample_${id}`], audio: `/sample/${lang.code}/${id}` })),
    example: app.sample_label,
    listening: app.status_listening,
    speaking: app.status_speaking,
    ended: app.status_closed,
    remembered: app.notice_remembered,
    soundOff: app.notice_sound_off,
    // As the app's sample shows it (ToolRunner.formatDuration(20 * 60)).
    timer: app.note_timer.replace("%1$s", "20 min"),
  };

  const menu = LANGS.map((l) =>
    l === lang
      ? `<li><a href="${l.path}" lang="${l.code}" aria-current="page">${escape(l.name)}</a></li>`
      : `<li><a href="${l.path}" lang="${l.code}" hreflang="${l.code}" data-lang="${l.code}">${escape(l.name)}</a></li>`,
  ).join("\n          ");

  // Google's own badge in the page's language, its transparent margin cut off.
  const [badgeWidth, badgeHeight] = pngSize(join(SITE, "public/badges", `${lang.code}.png`));
  const play = PLAY_LIVE
    ? `<a class="play-badge" href="${PLAY}"><img src="/badges/${lang.code}.png" alt="${escape(t.play_get)}" width="${badgeWidth}" height="${badgeHeight}"></a>`
    : `<p class="soon"><span class="dot" aria-hidden="true"></span>${escape(t.play_soon)}</p>`;

  const built = {
    lang: lang.code,
    path: lang.path,
    url: ORIGIN + lang.path,
    alternates,
    menu,
    langname: escape(lang.name),
    play,
    closing: escape(PLAY_LIVE ? t.closing_live : t.closing_soon),
    demo: JSON.stringify(demo).replace(/</g, "\\u003c"),
    ...ICONS,
  };

  const html = template.replace(/\{\{(@?)(\w+)\}\}/g, (all, raw, key) => {
    const value = raw ? built[key] : t[key];
    if (value === undefined) throw new Error(`${lang.code}: no value for ${all}`);
    return raw ? value : escape(value);
  });
  mkdirSync(join(DIST, lang.path), { recursive: true });
  writeFileSync(join(DIST, lang.path, "index.html"), html);

  // The example's voices, as the app plays them (Ogg Opus) and as AAC for browsers without Opus.
  const voices = join(DIST, "sample", lang.code);
  mkdirSync(voices, { recursive: true });
  for (const id of SAMPLE) {
    const ogg = join(APP, "assets/sample", lang.code, `${id}.ogg`);
    cpSync(ogg, join(voices, `${id}.ogg`));
    execFileSync("ffmpeg", ["-v", "error", "-y", "-i", ogg, "-c:a", "aac", "-b:a", "64k", join(voices, `${id}.m4a`)]);
  }
}

// Before anything is drawn on the English start page: a visitor whose browser prefers another of the
// site's languages goes there, unless they picked a language in the menu (remembered by main.ts).
const paths = Object.fromEntries(LANGS.map((l) => [l.code.toLowerCase(), l.path]));
writeFileSync(
  join(DIST, "lang.js"),
  `(function () {
  document.documentElement.classList.add("js");
  if (location.pathname !== "/") return;
  var paths = ${JSON.stringify(paths)}, saved = null;
  try { saved = localStorage.getItem("lang"); } catch (e) {}
  var wanted = saved ? [saved] : navigator.languages || [navigator.language];
  for (var i = 0; i < wanted.length; i++) {
    var tag = String(wanted[i]).toLowerCase(), base = tag.split("-")[0];
    var code = paths[tag] ? tag : base === "pt" ? "pt-br" : base === "zh" ? (/hant|tw|hk|mo/.test(tag) ? "" : "zh-cn") : paths[base] ? base : "";
    if (code) { if (paths[code] !== "/") location.replace(paths[code]); return; }
  }
})();
`,
);

writeFileSync(
  join(DIST, "sitemap.xml"),
  `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${LANGS.map((l) => `  <url><loc>${ORIGIN}${l.path}</loc></url>`).join("\n")}
</urlset>
`,
);
writeFileSync(join(DIST, "robots.txt"), `User-agent: *\nAllow: /\nSitemap: ${ORIGIN}/sitemap.xml\n`);

// The page script, with the esbuild installed for the pairing page (pair/).
const esbuild = createRequire(join(SITE, "../pair/package.json"))("esbuild");
esbuild.buildSync({
  entryPoints: [join(SITE, "src/main.ts")],
  bundle: true,
  minify: true,
  format: "esm",
  target: "es2022",
  outfile: join(DIST, "main.js"),
});

console.log(`Built ${LANGS.length} pages into dist/ (${readdirSync(DIST).length} entries).`);
