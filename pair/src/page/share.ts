/*
 * The page the watch opens on the phone. With #t=… it holds a conversation sent from the watch
 * (history → Send to phone): Share hands it to the phone's share sheet (messengers, notes, mail), Copy
 * puts it on the clipboard. With #e=… it holds a calendar event Parley prepared: no calendar app
 * takes a link for a new event, so Add to calendar hands the phone's own calendar app a calendar file.
 * Both come in the address fragment (UTF-8 in base64url), which browsers never send to a server, and
 * are removed from the address at once.
 */

import { lang, t, translatePage } from "./i18n.ts";
import { startOrb } from "./orb.ts";

const $ = <T extends HTMLElement>(selector: string) => document.querySelector(selector) as T;

function show(section: "missing" | "text" | "event") {
  document.querySelectorAll<HTMLElement>("[data-state]").forEach((el) => {
    el.hidden = el.dataset.state !== section;
  });
}

/** Like crypto.ts's, kept apart so this page does not load the pairing's cryptography. */
function base64UrlDecode(text: string): Uint8Array {
  const base64 = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function readFragment(): URLSearchParams {
  const params = new URLSearchParams(location.hash.slice(1));
  if (location.hash) history.replaceState(null, "", location.pathname + location.search);
  return params;
}

function decode(value: string | null): string | null {
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(base64UrlDecode(value ?? ""));
    return text.trim() ? text : null;
  } catch {
    return null;
  }
}

// ── Conversation ──────────────────────────────────────────────────────────────────────────────

function showText(text: string) {
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

// ── Calendar event ────────────────────────────────────────────────────────────────────────────

/**
 * As the watch writes it (PhoneLinks.event): local times "2026-11-03T15:00", or dates "2026-12-24"
 * for an all-day event whose end is its last day. [zone] is the watch's, for Google Calendar's link.
 */
interface CalendarEvent {
  title: string;
  start: string;
  end: string;
  place?: string;
  details?: string;
  zone?: string;
}

const WHEN = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$/;

function parseEvent(json: string | null): CalendarEvent | null {
  if (json === null) return null;
  try {
    const event = JSON.parse(json) as CalendarEvent;
    const sameKind = event.start?.includes("T") === event.end?.includes("T");
    return typeof event.title === "string" && WHEN.test(event.start) && WHEN.test(event.end) && sameKind ? event : null;
  } catch {
    return null;
  }
}

const allDay = (event: CalendarEvent) => !event.start.includes("T");

/** A local date or date and time; the phone is where the watch is, so its own time zone applies. */
function local(value: string): Date {
  const [y, m, d, h = 0, min = 0] = value.split(/[-T:]/).map(Number);
  return new Date(y, m - 1, d, h, min);
}

function dayAfter(date: string): Date {
  const day = local(date);
  day.setDate(day.getDate() + 1);
  return day;
}

const pad = (n: number) => String(n).padStart(2, "0");
const icsDay = (d: Date) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
const icsUtc = (d: Date) => d.toISOString().replace(/[-:]/g, "").replace(/\.\d+/, "");

/** Text for a calendar file: backslash, semicolon, comma and line breaks escaped. */
const icsText = (text: string) => text.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");

/** Lines of at most 75 bytes, longer ones folded onto lines that start with a space. */
function fold(line: string): string {
  const encoder = new TextEncoder();
  const parts: string[] = [];
  let part = "";
  let bytes = 0;
  for (const char of line) {
    const size = encoder.encode(char).length;
    if (bytes + size > (parts.length ? 74 : 75)) {
      parts.push(part);
      part = "";
      bytes = 0;
    }
    part += char;
    bytes += size;
  }
  parts.push(part);
  return parts.join("\r\n ");
}

/** The event as an iCalendar file, which every calendar app imports; timed events in UTC. */
function calendarFile(event: CalendarEvent): string {
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Parley//Watch//EN",
    "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT",
    `UID:${Date.now()}-${Math.random().toString(36).slice(2)}@parley`,
    `DTSTAMP:${icsUtc(new Date())}`,
    ...(allDay(event)
      ? [`DTSTART;VALUE=DATE:${icsDay(local(event.start))}`, `DTEND;VALUE=DATE:${icsDay(dayAfter(event.end))}`]
      : [`DTSTART:${icsUtc(local(event.start))}`, `DTEND:${icsUtc(local(event.end))}`]),
    `SUMMARY:${icsText(event.title)}`,
    ...(event.place ? [`LOCATION:${icsText(event.place)}`] : []),
    ...(event.details ? [`DESCRIPTION:${icsText(event.details)}`] : []),
    "END:VEVENT",
    "END:VCALENDAR",
  ];
  return lines.map(fold).join("\r\n") + "\r\n";
}

/** Google Calendar's page for a new event, for those who keep their calendar there. */
function googleLink(event: CalendarEvent): string {
  const dates = allDay(event)
    ? `${icsDay(local(event.start))}/${icsDay(dayAfter(event.end))}`
    : `${event.start.replace(/[-:]/g, "")}00/${event.end.replace(/[-:]/g, "")}00`;
  const params = [`text=${encodeURIComponent(event.title)}`, `dates=${dates}`];
  if (event.zone && !allDay(event)) params.push(`ctz=${encodeURIComponent(event.zone)}`);
  if (event.place) params.push(`location=${encodeURIComponent(event.place)}`);
  if (event.details) params.push(`details=${encodeURIComponent(event.details)}`);
  return `https://calendar.google.com/calendar/render?action=TEMPLATE&${params.join("&")}`;
}

/** "Tuesday, 3 November 2026, 15:00–16:00" in the page's language; whole days with "All day". */
function whenText(event: CalendarEvent): string {
  if (allDay(event)) {
    const days = new Intl.DateTimeFormat(lang, { dateStyle: "full" });
    const range = event.start === event.end ? days.format(local(event.start)) : days.formatRange(local(event.start), local(event.end));
    return `${range} · ${t("event_all_day")}`;
  }
  return new Intl.DateTimeFormat(lang, { dateStyle: "full", timeStyle: "short" }).formatRange(local(event.start), local(event.end));
}

/** A file name from the title, safe on every phone. */
const fileName = (title: string) => `${title.replace(/[^\p{L}\p{N} _-]+/gu, "").trim().slice(0, 40) || "event"}.ics`;

function showEvent(event: CalendarEvent) {
  $<HTMLElement>("#event-name").textContent = event.title;
  $<HTMLElement>("#event-when").textContent = whenText(event);
  const place = $<HTMLElement>("#event-place");
  place.textContent = event.place ?? "";
  place.hidden = !event.place;
  const details = $<HTMLElement>("#event-details");
  details.textContent = event.details ?? "";
  details.hidden = !event.details;
  $<HTMLAnchorElement>("#event-google").href = googleLink(event);
  show("event");

  // A download the browser hands on: the phone opens a calendar file with its calendar app.
  $<HTMLButtonElement>("#event-add").addEventListener("click", () => {
    const url = URL.createObjectURL(new Blob([calendarFile(event)], { type: "text/calendar" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName(event.title);
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  });
}

function main() {
  const params = readFragment();
  const event = parseEvent(decode(params.get("e")));
  if (event) return showEvent(event);
  const text = decode(params.get("t"));
  if (text === null) return show("missing");
  showText(text);
}

translatePage("share_page_title", "share_page_description");
startOrb($<HTMLElement>(".orb"));
main();
