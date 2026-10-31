/*
 * The website's script: the drifting sky behind the page (sky.ts), the watch at the top, which plays
 * SayOVA's example conversation with the app's own texts and voices, and the language menu. The living
 * orb is the pairing page's own WebGL shader
 * (pair/src/page/orb.ts); a CSS-scaled still image stutters in Firefox, which moves such a slow scale in
 * whole pixels.
 */
import { startOrb } from "../../pair/src/page/orb.ts";
import { startSky } from "./sky.ts";

startSky();

interface Line {
  who: "q" | "a";
  text: string;
  audio: string;
}

interface DemoData {
  lang: string;
  lines: Line[];
  example: string;
  listening: string;
  speaking: string;
  ended: string;
  remembered: string;
  soundOff: string;
  timer: string;
}

const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));

/** The watch's clock, without AM or PM, as the watch shows it. */
function clock(lang: string): string {
  return new Intl.DateTimeFormat(lang, { hour: "numeric", minute: "2-digit" })
    .formatToParts(new Date())
    .filter((part) => part.type !== "dayPeriod")
    .map((part) => part.value)
    .join("")
    .trim();
}

function elapsed(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/**
 * How much of [text] is shown at [progress]: by characters, like the watch's highlight; for the user's
 * words up to the last whole word, as a transcription arrives.
 */
function cut(text: string, progress: number, whole: boolean): number {
  const count = Math.round(Math.min(Math.max(progress, 0), 1) * text.length);
  if (!whole || count >= text.length || !text.includes(" ")) return count;
  const space = text.lastIndexOf(" ", count);
  return space < 0 ? 0 : space;
}

class Conversation {
  private readonly watch: HTMLElement;
  private readonly data: DemoData;
  private readonly orb: HTMLButtonElement;
  private readonly time: SVGTSpanElement;
  private readonly status: SVGTSpanElement;
  private readonly meta: HTMLElement;
  private readonly notice: HTMLElement;
  private readonly box: HTMLElement;
  private readonly inner: HTMLElement;
  private readonly textButton: HTMLButtonElement;
  private readonly soundButton: HTMLButtonElement;

  private readonly audio = new Audio();
  private readonly ext: string;
  private context: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private gain: GainNode | null = null;
  private samples = new Uint8Array(512);
  private loudness = 0;

  /** Raised on every start and end, so a conversation that was ended stops at its next step. */
  private run = 0;
  private skip: (() => void) | null = null;
  private started = 0;
  private scroll = 0;
  private noticeTimer = 0;
  private muted = false;

  constructor(watch: HTMLElement, data: DemoData) {
    const find = <T extends Element>(selector: string) => watch.querySelector(selector) as T;
    this.watch = watch;
    this.data = data;
    this.orb = find(".orb");
    this.time = find(".arc-time");
    this.status = find(".arc-status");
    this.meta = find(".meta");
    this.notice = find(".notice");
    this.box = find(".lines");
    this.inner = find(".lines-inner");
    this.textButton = find(".ctl-text");
    this.soundButton = find(".ctl-sound");
    this.ext = this.audio.canPlayType('audio/ogg; codecs="opus"') ? "ogg" : "m4a";
  }

  init() {
    this.audio.preload = "none";
    this.tick();
    setInterval(() => this.tick(), 5000);
    startOrb(this.orb, () => this.level());

    this.orb.addEventListener("click", () => (this.playing ? this.skip?.() : this.start()));
    this.watch.querySelector(".start")?.addEventListener("click", () => this.start());
    this.watch.querySelector(".ctl-end")?.addEventListener("click", () => this.end());
    this.textButton.addEventListener("click", () => {
      const on = this.textButton.getAttribute("aria-pressed") !== "true";
      this.textButton.setAttribute("aria-pressed", String(on));
      this.watch.classList.toggle("orb-only", !on);
    });
    this.soundButton.addEventListener("click", () => {
      this.muted = !this.muted;
      this.soundButton.setAttribute("aria-pressed", String(!this.muted));
      if (this.gain) this.gain.gain.value = this.muted ? 0 : 1;
      else this.audio.muted = this.muted;
      if (this.muted) this.flash(this.data.soundOff, 2000);
    });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden && this.playing) this.end();
    });
  }

  private get playing() {
    return this.watch.dataset.state === "playing";
  }

  private tick() {
    this.time.textContent = clock(this.data.lang);
  }

  private setStatus(text: string, kind: string) {
    this.status.textContent = text ? ` · ${text}` : "";
    this.status.setAttribute("class", `arc-status ${kind}`);
  }

  /** The voice's loudness for the orb, smoothed so the cloud swells and settles rather than flickers. */
  private level(): number {
    let target = 0;
    if (this.analyser && this.playing && !this.audio.paused) {
      this.analyser.getByteTimeDomainData(this.samples);
      let sum = 0;
      for (const sample of this.samples) sum += ((sample - 128) / 128) ** 2;
      target = Math.min(1, Math.sqrt(sum / this.samples.length) * 4);
    }
    this.loudness += (target - this.loudness) * (target > this.loudness ? 0.3 : 0.06);
    return this.loudness;
  }

  /** Routes the voice through Web Audio, for its loudness and a mute that keeps the orb moving. */
  private connect() {
    if (this.context) return;
    try {
      this.context = new AudioContext();
      const source = this.context.createMediaElementSource(this.audio);
      this.analyser = this.context.createAnalyser();
      this.analyser.fftSize = 512;
      this.gain = this.context.createGain();
      source.connect(this.analyser).connect(this.gain).connect(this.context.destination);
      this.gain.gain.value = this.muted ? 0 : 1;
    } catch {
      this.analyser = null;
      this.gain = null;
    }
  }

  private flash(text: string, ms = 0) {
    clearTimeout(this.noticeTimer);
    this.notice.textContent = text;
    this.watch.classList.toggle("has-notice", !!text);
    if (ms) this.noticeTimer = window.setTimeout(() => this.flash(""), ms);
  }

  private async start() {
    if (this.playing) return;
    const run = ++this.run;
    this.connect();
    void this.context?.resume();
    // Started inside the tap, so that browsers which ask for one let every later line play too.
    this.audio.src = `${this.data.lines[0].audio}.${this.ext}`;
    const first = this.audio.play().catch(() => undefined);

    this.inner.replaceChildren();
    this.scroll = 0;
    this.inner.style.transform = "";
    this.flash("");
    this.watch.dataset.state = "playing";
    this.started = performance.now();
    this.frame(run);

    for (const [index, line] of this.data.lines.entries()) {
      if (run !== this.run) return;
      if (index === 1) this.flash(this.data.remembered, 2600);
      if (index === 5) this.flash(this.data.timer);
      await this.say(line, run, index === 0 ? first : null);
      await sleep(line.who === "q" ? 300 : 650);
    }
    if (run !== this.run) return;
    this.setStatus(this.data.ended, "ended");
    await sleep(2200);
    if (run === this.run) this.reset();
  }

  /** One line of the conversation: its voice, and its text as the watch shows it meanwhile. */
  private async say(line: Line, run: number, playing: Promise<void> | null) {
    const user = line.who === "q";
    this.setStatus(user ? this.data.listening : this.data.speaking, user ? "listening" : "speaking");
    for (const old of this.inner.querySelectorAll(".line")) if (user) old.classList.add("old");

    const element = document.createElement("p");
    element.className = `line ${line.who}`;
    const heard = document.createElement("span");
    const cursor = document.createElement("span");
    const rest = document.createElement("span");
    heard.className = "heard";
    cursor.className = "cursor";
    rest.className = "rest";
    element.append(heard, cursor, rest);
    this.inner.append(element);

    if (!playing) {
      this.audio.src = `${line.audio}.${this.ext}`;
      playing = this.audio.play().catch(() => undefined);
    }
    await playing;
    // Without sound (blocked or failed), the text still runs, at about a speaking pace.
    const voiced = !this.audio.paused;
    const begun = performance.now();
    const estimate = line.text.length * 70;

    await new Promise<void>((done) => {
      let over = false;
      const finish = () => {
        if (over) return;
        over = true;
        this.skip = null;
        this.audio.removeEventListener("ended", finish);
        this.audio.removeEventListener("error", finish);
        done();
      };
      this.skip = () => {
        this.audio.pause();
        finish();
      };
      this.audio.addEventListener("ended", finish);
      this.audio.addEventListener("error", finish);
      const step = () => {
        if (over || run !== this.run) return finish();
        const progress =
          voiced && this.audio.duration > 0
            ? this.audio.currentTime / this.audio.duration
            : (performance.now() - begun) / estimate;
        const count = cut(line.text, progress, user);
        heard.textContent = line.text.slice(0, count);
        // The answer stands there at once, dim, and brightens as it is spoken; the user's words appear.
        rest.textContent = user ? "" : line.text.slice(count);
        if (!voiced && progress >= 1) return finish();
        requestAnimationFrame(step);
      };
      step();
    });
    heard.textContent = line.text;
    rest.textContent = "";
  }

  /** Every frame while playing: the elapsed time, and the transcript kept at the spoken words. */
  private frame(run: number) {
    if (run !== this.run || !this.playing) return;
    this.meta.textContent = `${this.data.example} · ${elapsed(performance.now() - this.started)}`;
    const cursor = this.inner.querySelector<HTMLElement>(".line:last-child .cursor");
    if (cursor) {
      const line = parseFloat(getComputedStyle(this.inner).lineHeight) || 20;
      const room = this.box.clientHeight;
      const target = Math.max(0, cursor.offsetTop + line - room * 0.62);
      this.scroll += (target - this.scroll) * 0.12;
      this.inner.style.transform = `translateY(${-this.scroll.toFixed(1)}px)`;
    }
    requestAnimationFrame(() => this.frame(run));
  }

  private end() {
    this.run++;
    this.audio.pause();
    this.skip = null;
    this.reset();
  }

  private reset() {
    this.watch.dataset.state = "idle";
    this.setStatus("", "");
    this.flash("");
    this.meta.textContent = "";
  }
}

const watch = document.querySelector<HTMLElement>(".watch");
const data = document.getElementById("demo-data");
if (watch && data?.textContent) new Conversation(watch, JSON.parse(data.textContent) as DemoData).init();

// The language menu: a language picked there wins over the browser's from then on (see lang.js).
const menu = document.querySelector<HTMLDetailsElement>(".lang");
menu?.querySelectorAll<HTMLAnchorElement>("a[data-lang]").forEach((link) =>
  link.addEventListener("click", () => {
    try {
      localStorage.setItem("lang", (link.dataset.lang ?? "en").toLowerCase());
    } catch {
      // Without storage the browser's language decides on the next visit, which is fine.
    }
  }),
);
document.addEventListener("click", (event) => {
  if (menu?.open && !menu.contains(event.target as Node)) menu.open = false;
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && menu?.open) {
    menu.open = false;
    menu.querySelector("summary")?.focus();
  }
});
