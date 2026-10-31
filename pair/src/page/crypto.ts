/*
 * The browser half of SayOVA's key handover (the watch half is core/.../pairing/Pairing.kt).
 *
 * HPKE (RFC 9180) base mode with DHKEM-X25519-HKDF-SHA256, HKDF-SHA256 and AES-128-GCM, encrypting to
 * the watch's one-time public key from the QR code. The sealed message is the 32-byte encapsulated key
 * followed by the ciphertext; the info string binds it to the pairing id. Kept free of DOM code so the
 * test-vector script can run it under Node.
 */

import { Aes128Gcm, CipherSuite, HkdfSha256 } from "@hpke/core";
// The noble-based X25519 works in every browser, also those without X25519 in WebCrypto.
import { DhkemX25519HkdfSha256 } from "@hpke/dhkem-x25519";

export const PAIRING_VERSION = 1;

export const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes128Gcm(),
});

export interface Payload {
  v: number;
  provider: "gemini" | "openai";
  apiKey: string;
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(text: string): Uint8Array {
  const base64 = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  const binary = atob(base64);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

export function info(pairingId: string): Uint8Array {
  return new TextEncoder().encode(`sayova-pair-v${PAIRING_VERSION}|${pairingId}`);
}

/**
 * Six digits both screens show: SHA-256 over the watch's public key and the encapsulated key, first
 * four bytes as an unsigned big-endian number, modulo one million.
 */
export async function verificationCode(publicKey: Uint8Array, enc: Uint8Array): Promise<string> {
  const data = new Uint8Array(publicKey.length + enc.length);
  data.set(publicKey, 0);
  data.set(enc, publicKey.length);
  const digest = new DataView(await crypto.subtle.digest("SHA-256", data));
  return String(digest.getUint32(0) % 1_000_000).padStart(6, "0");
}

export async function seal(
  publicKey: Uint8Array,
  pairingId: string,
  payload: Payload,
): Promise<{ sealed: Uint8Array; code: string }> {
  const recipientPublicKey = await suite.kem.deserializePublicKey(publicKey);
  const sender = await suite.createSenderContext({ recipientPublicKey, info: info(pairingId) });
  const ciphertext = new Uint8Array(await sender.seal(new TextEncoder().encode(JSON.stringify(payload))));
  const enc = new Uint8Array(sender.enc);
  const sealed = new Uint8Array(enc.length + ciphertext.length);
  sealed.set(enc, 0);
  sealed.set(ciphertext, enc.length);
  return { sealed, code: await verificationCode(publicKey, enc) };
}
