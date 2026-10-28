/*
 * The page in the visitor's language: the browser's preferred languages pick one of the files in
 * locales/ (the same languages as the watch app), English otherwise; `?lang=de` overrides, for
 * testing. Every new text goes into all of them.
 *
 * Templates mark highlighted parts as {name}; [fill] builds those as elements, never as HTML, so
 * nothing in a translation can inject markup.
 */
import de from "./locales/de.json";
import en from "./locales/en.json";
import es from "./locales/es.json";
import fr from "./locales/fr.json";
import it from "./locales/it.json";
import ja from "./locales/ja.json";
import ko from "./locales/ko.json";
import nl from "./locales/nl.json";
import pl from "./locales/pl.json";
import ptBR from "./locales/pt-BR.json";
import sv from "./locales/sv.json";
import tr from "./locales/tr.json";
import zhCN from "./locales/zh-CN.json";

export type Key = keyof typeof en;
type Messages = Record<Key, string>;

const LOCALES: Record<string, Messages> = {
  en, de, fr, es, it, "pt-BR": ptBR, nl, pl, sv, tr, ja, ko, "zh-CN": zhCN,
};

/** A language tag of the browser's matched to one of ours: exact, then by its base language. */
function match(tag: string): string | null {
  const lower = tag.toLowerCase();
  for (const code of Object.keys(LOCALES)) if (code.toLowerCase() === lower) return code;
  const base = lower.split("-")[0];
  if (base === "pt") return "pt-BR";
  // Simplified Chinese only; Traditional (zh-TW, zh-HK, zh-Hant) reads English rather than the wrong script.
  if (base === "zh") return /hant|tw|hk|mo/.test(lower) ? null : "zh-CN";
  return base in LOCALES ? base : null;
}

function pick(): string {
  const forced = new URLSearchParams(location.search).get("lang");
  const wanted = [forced ?? "", ...(navigator.languages ?? [navigator.language])];
  for (const tag of wanted) {
    const code = tag && match(tag);
    if (code) return code;
  }
  return "en";
}

export const lang = pick();
const messages: Messages = { ...en, ...LOCALES[lang] };

/** The text for [key], with plain {name} values filled in. */
export function t(key: Key, values: Record<string, string> = {}): string {
  return messages[key].replace(/\{(\w+)\}/g, (all, name: string) => values[name] ?? all);
}

/** Puts [key] into [el]: {name} parts become a <tag> element holding the given text. */
export function fill(el: Element, key: Key, parts: Record<string, [tag: string, text: string]> = {}) {
  el.replaceChildren();
  for (const piece of messages[key].split(/(\{\w+\})/)) {
    const name = /^\{(\w+)\}$/.exec(piece)?.[1];
    const part = name ? parts[name] : undefined;
    if (part) {
      const node = document.createElement(part[0]);
      node.textContent = part[1];
      el.append(node);
    } else if (piece) {
      el.append(piece);
    }
  }
}

/** Translates the static page: [data-i18n] elements, [data-i18n-placeholder], the title and language. */
export function translatePage() {
  document.documentElement.lang = lang;
  document.title = t("page_title");
  document.querySelector('meta[name="description"]')?.setAttribute("content", t("page_description"));
  document.querySelectorAll<HTMLElement>("[data-i18n]").forEach((el) => {
    // Elements with highlighted parts are filled by the code that knows the parts.
    if (!el.dataset.i18nParts) el.textContent = t(el.dataset.i18n as Key);
  });
  document.querySelectorAll<HTMLInputElement>("[data-i18n-placeholder]").forEach((el) => {
    el.placeholder = t(el.dataset.i18nPlaceholder as Key);
  });
  const strong = (key: Key): [string, string] => ["strong", t(key)];
  const em = (text: string): [string, string] => ["em", text];
  const parts: Partial<Record<Key, Record<string, [string, string]>>> = {
    missing_text: { provider: strong("watch_choose_provider"), phone: strong("watch_open_on_phone") },
    sent_text: { matches: strong("watch_matches") },
    // Menu names of Google AI Studio and the OpenAI platform, which are English there.
    help_gemini_1: { menu: em("API Keys") },
    help_gemini_2: { button: em("Create API key") },
    help_gemini_3: { menu: em("Projects → Set up billing") },
    help_openai_1: { menu: em("API keys") },
    help_openai_2: { button: em("Create new secret key") },
    help_openai_3: { menu: em("Billing") },
  };
  document.querySelectorAll<HTMLElement>("[data-i18n-parts]").forEach((el) => {
    const key = el.dataset.i18n as Key;
    fill(el, key, parts[key] ?? {});
  });
}
