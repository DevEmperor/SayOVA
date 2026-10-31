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

// Until Google Play has published SayOVA, the page says it is coming instead of linking there.
const PLAY_LIVE = false;

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

  const play = PLAY_LIVE
    ? `<a class="play-badge" href="${PLAY}"><img src="/badges/${lang.code}.png" alt="${escape(t.play_get)}" height="56"></a>`
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
