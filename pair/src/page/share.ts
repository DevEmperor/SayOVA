/*
 * The page a conversation opens on the phone when it is sent from the watch (history → Send to phone).
 * The text comes in the address fragment (#t=…, UTF-8 in base64url), which browsers never send to a
 * server, and is removed from the address at once. Share hands it to the phone's share sheet
 * (messengers, notes, mail), Copy puts it on the clipboard.
 */

import { t, translatePage } from "./i18n.ts";
import { startOrb } from "./orb.ts";

const $ = <T extends HTMLElement>(selector: string) => document.querySelector(selector) as T;

function show(section: "missing" | "text") {
  document.querySelectorAll<HTMLElement>("[data-state]").forEach((el) => {
    el.hidden = el.dataset.state !== section;
  });
}

/** Like crypto.ts's, kept apart so this page does not load the pairing's cryptography. */
function base64UrlDecode(text: string): Uint8Array {
  const base64 = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function readText(): string | null {
  const params = new URLSearchParams(location.hash.slice(1));
  if (location.hash) history.replaceState(null, "", location.pathname + location.search);
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(base64UrlDecode(params.get("t") ?? ""));
    return text.trim() ? text : null;
  } catch {
    return null;
  }
}

function main() {
  const text = readText();
  if (text === null) {
    show("missing");
    return;
  }
  $<HTMLElement>("#share-text").textContent = text;
  show("text");

  const share = $<HTMLButtonElement>("#share");
  const copy = $<HTMLButtonElement>("#copy");
  if (typeof navigator.share === "function") {
    // Closing the share sheet without choosing rejects; nothing to tell the user then.
    share.addEventListener("click", () => navigator.share({ text }).catch(() => undefined));
  } else {
    // A browser without a share sheet: Copy is the one thing left to do, so it takes the main button.
    share.hidden = true;
    copy.className = "primary";
  }

  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(text);
      copy.textContent = t("copied");
    } catch {
      // No clipboard access: select the text, so the phone's own copy is one tap away.
      window.getSelection()?.selectAllChildren($<HTMLElement>("#share-text"));
    }
  });
}

translatePage("share_page_title", "share_page_description");
startOrb($<HTMLElement>(".orb"));
main();
