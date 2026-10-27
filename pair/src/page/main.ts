/*
 * The pairing page: reads the watch's code from the address (#v=1&id=…&pk=…), lets the user pick a
 * provider and paste a key, seals it in the browser (crypto.ts) and posts only the ciphertext.
 */

import { PAIRING_VERSION, base64UrlDecode, base64UrlEncode, seal, type Payload } from "./crypto.ts";

const ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;

interface Pairing {
  id: string;
  publicKey: Uint8Array;
  provider: Payload["provider"] | null;
}

const $ = <T extends HTMLElement>(selector: string) => document.querySelector(selector) as T;

function show(section: "missing" | "expired" | "form" | "sent") {
  document.querySelectorAll<HTMLElement>("[data-state]").forEach((el) => {
    el.hidden = el.dataset.state !== section;
  });
}

/** The code lives in the fragment, which browsers never send to a server. Remove it from history too. */
function readPairing(): Pairing | null {
  const params = new URLSearchParams(location.hash.slice(1));
  if (location.hash) history.replaceState(null, "", location.pathname + location.search);
  const id = params.get("id") ?? "";
  const pk = params.get("pk") ?? "";
  if (Number(params.get("v")) !== PAIRING_VERSION || !ID_PATTERN.test(id)) return null;
  try {
    const publicKey = base64UrlDecode(pk);
    const p = params.get("p");
    const provider = p === "gemini" || p === "openai" ? p : null;
    return publicKey.length === 32 ? { id, publicKey, provider } : null;
  } catch {
    return null;
  }
}

async function slotState(id: string): Promise<string> {
  const response = await fetch(`/v1/pair/${id}/status`, { cache: "no-store" });
  if (!response.ok) throw new Error(`status ${response.status}`);
  return ((await response.json()) as { state: string }).state;
}

const providerHelp: Record<Payload["provider"], { url: string; label: string }> = {
  gemini: { url: "https://aistudio.google.com/apikey", label: "Open Google AI Studio" },
  openai: { url: "https://platform.openai.com/api-keys", label: "Open the OpenAI dashboard" },
};

function selectedProvider(): Payload["provider"] {
  return ($<HTMLInputElement>('input[name="provider"]:checked').value as Payload["provider"]) ?? "gemini";
}

function updateProviderHelp() {
  const provider = selectedProvider();
  document.querySelectorAll<HTMLElement>("[data-provider]").forEach((el) => {
    el.hidden = el.dataset.provider !== provider;
  });
  const link = $<HTMLAnchorElement>("#key-link");
  link.href = providerHelp[provider].url;
  link.textContent = providerHelp[provider].label;
}

function formatCode(code: string) {
  return `${code.slice(0, 3)} ${code.slice(3)}`;
}

async function waitForPickup(id: string) {
  const status = $<HTMLElement>("#pickup");
  const deadline = Date.now() + 10 * 60 * 1000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 2000));
    try {
      const state = await slotState(id);
      if (state === "done") {
        status.textContent = "Your watch received the key. Confirm the code there – then you can close this page.";
        status.classList.add("success");
        return;
      }
      if (state === "unknown") break;
    } catch {
      // Keep trying; a phone switching networks is not an error.
    }
  }
  status.textContent = "The watch did not pick up the key in time. Start again on your watch.";
  status.classList.add("error");
}

async function main() {
  const pairing = readPairing();
  if (!pairing) {
    show("missing");
    return;
  }

  try {
    const state = await slotState(pairing.id);
    if (state !== "open") {
      $<HTMLElement>("#expired-reason").textContent =
        state === "unknown"
          ? "This code has expired. Codes are valid for ten minutes."
          : "This code has already been used.";
      show("expired");
      return;
    }
  } catch {
    // Offline or the relay is down: let the user try anyway, sending will report the problem.
  }

  show("form");
  if (pairing.provider) {
    $<HTMLInputElement>(`input[name="provider"][value="${pairing.provider}"]`).checked = true;
  }
  updateProviderHelp();
  document.querySelectorAll('input[name="provider"]').forEach((el) => el.addEventListener("change", updateProviderHelp));

  const keyInput = $<HTMLInputElement>("#api-key");
  $<HTMLButtonElement>("#toggle-key").addEventListener("click", (event) => {
    const button = event.currentTarget as HTMLButtonElement;
    const visible = keyInput.type === "text";
    keyInput.type = visible ? "password" : "text";
    button.textContent = visible ? "Show" : "Hide";
    button.setAttribute("aria-pressed", String(!visible));
  });

  const form = $<HTMLFormElement>("#pair-form");
  const error = $<HTMLElement>("#form-error");
  const submit = $<HTMLButtonElement>("#send");

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.textContent = "";
    const apiKey = keyInput.value.trim();
    if (apiKey.length < 20 || /\s/.test(apiKey)) {
      error.textContent = "That does not look like a complete API key. Copy it again from your provider.";
      keyInput.focus();
      return;
    }
    submit.disabled = true;
    submit.textContent = "Encrypting…";
    try {
      const { sealed, code } = await seal(pairing.publicKey, pairing.id, {
        v: PAIRING_VERSION,
        provider: selectedProvider(),
        apiKey,
      });
      const response = await fetch(`/v1/pair/${pairing.id}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ sealed: base64UrlEncode(sealed) }),
      });
      if (response.status === 410) throw new Error("This code has expired. Start again on your watch.");
      if (response.status === 409) throw new Error("This code has already been used. Start again on your watch.");
      if (!response.ok) throw new Error(`Sending failed (${response.status}). Please try again.`);
      keyInput.value = "";
      $<HTMLElement>("#code").textContent = formatCode(code);
      show("sent");
      void waitForPickup(pairing.id);
    } catch (e) {
      error.textContent = e instanceof Error && e.message ? e.message : "Sending failed. Please try again.";
      submit.disabled = false;
      submit.textContent = "Send to watch";
    }
  });
}

void main();
