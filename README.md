<div align="center">

<img src="pair/public/favicon.svg" alt="Parley" width="96">

# Parley

### Talk to AI from your wrist.

A realtime voice assistant for Wear OS: speak naturally, interrupt whenever you like,
hear the answer and read it along on your watch — with Google Gemini or OpenAI and
your own API key.

</div>

---

## Features

- **Real conversations, not commands.** Parley streams your voice live to a
  speech-to-speech model (Gemini Live or OpenAI Realtime) and plays the answer as it is
  generated. Speak over it to interrupt, or tap the orb.
- **Live transcript.** Both sides of the conversation appear as you talk; the part of an
  answer you have already heard is bright, the rest is still dim.
- **Silent mode.** Answers as text only, with push-to-talk — for lectures, meetings or a
  quiet room.
- **Does things on the watch.** Timers, alarms, notes, battery and time, and web search
  for anything current.
- **Your provider, your key, your data.** Choose Google Gemini (from about a cent a
  minute) or OpenAI. Audio goes straight from the watch to the provider; there is no
  Parley server in between.
- **Easy, secure setup.** Scan a QR code on the watch, paste your key on your phone, and
  it arrives end-to-end encrypted. No companion app, no typing on a tiny keyboard.
- **Made for the watch.** Wi-Fi or LTE is used during a conversation instead of the slow
  Bluetooth link; conversations continue with the screen off; tile, complication and
  watch-face icon; personalities, 40 voices, answer language, haptics.
- **History on the watch only**, kept as long as you like, never backed up to the cloud.
- **Runs on every Wear OS 3+ watch with a microphone.**

## Getting an API key

**Google Gemini (recommended)**

1. Sign in to [Google AI Studio](https://aistudio.google.com/apikey) and choose
   **Create API key**.
2. In the EU, the UK and Switzerland, turn on billing (*Projects → Set up billing*).
   Prepaying $5 lasts for many hours of talking, and on the paid tier Google does not
   use your conversations to improve its products.
3. Optional: set a monthly limit under *Spend*.

**OpenAI**

1. Create a key on the [OpenAI platform](https://platform.openai.com/api-keys).
2. Make sure the account has credit.

Then open Parley on the watch, choose the provider, scan the QR code and paste the key.

## What it costs

Parley is a one-time purchase on Google Play. Talking is billed by your provider, at
its own prices — roughly:

| Provider | Model in Parley | Approximate cost |
| --- | --- | --- |
| Google | `gemini-3.8-live` (Fast), `gemini-3.8-live-extended-thinking` (Thorough) | 1–2 ¢ per minute |
| OpenAI | `gpt-realtime-2.1-mini` (Fast) | 2–5 ¢ per minute |
| OpenAI | `gpt-realtime-2.1` (Best) | 6–11 ¢ per minute |

Parley shows a running estimate during the conversation and the month's total in the
settings.

## Privacy

Your voice and your conversations go only to the provider you chose. Your key is
encrypted on the watch, the history never leaves it, and the pairing relay only ever
sees data it cannot read. Details: [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Project layout

| Path | What lives there |
| --- | --- |
| `core/` | Provider protocols (Gemini Live, OpenAI Realtime), event model, tools, cost meter, pairing crypto — pure Kotlin, unit-tested |
| `wear/` | The Wear OS app: audio, network selection, conversation service, Compose UI, tile, complication |
| `pair/` | The pairing page and relay (Cloudflare Worker + Durable Object) |

### Building

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:testDebugUnitTest :wear:testDebugUnitTest :wear:lintDebug :wear:assembleRelease
```

The address of the pairing page is compiled into the app from `parley.pairBaseUrl` in
`gradle.properties` (currently `https://parley-pair.flat-pine-028f.workers.dev`). See [pair/README.md](pair/README.md)
for the relay.

Debug builds can play a scripted conversation for screenshots:

```bash
adb shell am start -n net.devemperor.parley.debug/net.devemperor.parley.MainActivity --ez demo true
```

## Credits

The orb is adapted from the Cloud theme of [orb-ui](https://github.com/exprmntl/orb-ui) by
Alexander Chen (MIT License), recoloured in Parley's palette.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
