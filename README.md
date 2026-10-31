<div align="center">

<img src="pair/public/icon.png" alt="SayOVA" width="96">

# SayOVA

### Orb Voice Assistant – talk to AI from your wrist.

A realtime voice assistant for Wear OS: speak naturally, interrupt whenever you like,
hear the answer and read it along on your watch — with Google Gemini or OpenAI and
your own API key.

</div>

---

## Features

- **Real conversations, not commands.** SayOVA streams your voice live to a
  speech-to-speech model (Gemini Live or OpenAI Realtime) and plays the answer as it is
  generated. Speak over it to interrupt, or tap the orb.
- **Live transcript, one tap away.** By default SayOVA shows only its orb, pulsing with
  whoever speaks. Tap the text button and both sides of the conversation appear as you
  talk; the part of an answer you have already heard is bright, the rest is still dim.
  SayOVA remembers your choice.
- **Its own volume, and quiet at a tap.** Turn the bezel or crown to set SayOVA's volume; it
  remembers it and gives your media volume back when the conversation ends. The sound button
  switches the voice off for quiet places, and the transcript shows instead.
- **Pick up where you left off.** Continue any conversation from the history; SayOVA remembers what
  was said.
- **Remembers what you ask it to.** Say "Remember that I'm vegetarian" and every later
  conversation knows. Only what you ask for is kept, on the watch, and the settings show
  every fact to read or delete.
- **Interpreter.** Hold the watch between two people: SayOVA says everything in the other
  language (21 languages, the pair is remembered) and shows both sides as text.
- **Speaks your language.** The app is translated into 12 languages besides English, and SayOVA
  answers in whatever language you talk to it.
- **Know what it costs.** Usage statistics show cost and talk time per month and in total,
  and a monthly budget warns at 80% and asks before every conversation once it is spent.
- **Quiet when it has to be.** One tap switches SayOVA's voice off and the answer appears as text;
  push-to-talk keeps the microphone closed until you tap.
- **Does things on the watch.** Timers and alarms (also every weekday; "show my timers" opens the
  clock to stop one or turn an alarm off), notes, battery and time, and web search for anything current.
- **Prepares things on your phone.** "Text Lena on WhatsApp that I'm ten minutes late", "Put the
  dentist on Tuesday at three in my calendar", "Route to the station on foot": SayOVA reads the message
  back, and after your yes it waits on your phone in WhatsApp, your messages or mail app, your own
  calendar app (one tap hands it the event) or your maps app (other messengers through the share menu)
  for you to send, add or start. SayOVA itself never sends anything in your name.
- **Knows the watch it runs on – when you ask.** "What's the weather here?", "What's my
  heart rate?", "How many steps today?", "What's on this afternoon?", "When does my alarm
  ring?", "How high up am I?", "Pause the music", "Call Lena", "Open the flashlight",
  "Anything new?": SayOVA uses the location, weather, heart rate, steps, calendar, air
  pressure, next alarm, music, contacts, apps or notifications only for that question,
  after you allowed it once, and shows an icon while it does. Each one can be switched
  off under Settings → Watch features. Calls are placed only after you confirmed name and
  number.
- **Your provider, your key, your data.** Choose Google Gemini (from about a cent a
  minute) or OpenAI. Audio goes straight from the watch to the provider; there is no
  SayOVA server in between.
- **Hear it before you set it up.** Start on the welcome page first plays a sample conversation
  in your language, both sides spoken (SayOVA in its real voice), with no key and no network; a
  short tour then points out the conversation's controls.
- **Easy, secure setup.** Scan a QR code on the watch, paste your key on your phone, and
  it arrives end-to-end encrypted. No companion app, no typing on a tiny keyboard. The
  introduction then lets you choose and allow the watch features, shows how to start SayOVA with
  a double press of the watch's button (on a Galaxy Watch it opens that setting directly), and tours
  the home screen.
- **Made for the watch.** Wi-Fi or LTE is used during a conversation instead of the slow
  Bluetooth link; conversations continue with the screen off; tile, a complication for every kind
  of watch-face slot (the slots with a value show the month's cost against your budget) and
  watch-face icon; personalities that also set how long SayOVA talks (from Concise to Detailed),
  40 voices to listen to before you pick one, answer language, haptics.
- **History on the watch only**, kept as long as you like, never backed up to the cloud; send a
  conversation (or several) to your phone as text, to share it with any app or copy it.
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

Then open SayOVA on the watch, choose the provider, scan the QR code and paste the key.

## What it costs

SayOVA is a one-time purchase on Google Play. Talking is billed by your provider, at
its own prices — roughly:

| Provider | Model in SayOVA | Approximate cost |
| --- | --- | --- |
| Google | `gemini-3.8-live` (Fast), `gemini-3.8-live-extended-thinking` (Thorough) | 1–2 ¢ per minute |
| OpenAI | `gpt-realtime-2.1-mini` (Fast) | 2–5 ¢ per minute |
| OpenAI | `gpt-realtime-2.1` (Best) | 6–11 ¢ per minute |

SayOVA shows a running estimate during the conversation and the month's total in the
settings. A monthly budget (Settings → Monthly budget, $1 to $50) warns from 80% on, can switch
to the Fast model from there, and asks before every conversation once it is spent. Thorough answers at once and then thinks in the background before it gives its
considered answer; that thinking adds about half a cent to a hard question.

## Privacy

Your voice and your conversations go only to the provider you chose. Your key is
encrypted on the watch, the history and memory never leave it except inside a
conversation with your provider, and the pairing relay only ever
sees data it cannot read. For the weather, MET Norway receives the place's rounded
coordinates. Details: [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

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

The address of the pairing page is compiled into the app from `sayova.pairBaseUrl` in
`gradle.properties` (`https://pair.sayova.app`). See [pair/README.md](pair/README.md) for the relay and
[site/](site/) for the website on sayova.app.

Debug builds open the introduction's sample conversation straight away, for tests and screenshots:

```bash
adb shell am start -n net.devemperor.sayova.debug/net.devemperor.sayova.MainActivity --ez demo true
```

The sample's voices are Gemini's (SayOVA's answers in its default voice Kore, the user's questions in
Puck), recorded once per app language with `--ez record_sample true` (see `SampleVoices`) and kept as
Opus in `wear/src/main/assets/sample/`; when its lines change in the strings, record them again.

## Credits

The orb is adapted from the Cloud theme of [orb-ui](https://github.com/exprmntl/orb-ui) by
Alexander Chen (MIT License), recoloured in SayOVA's palette.

Weather data from the [Norwegian Meteorological Institute (MET Norway)](https://api.met.no/),
under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
