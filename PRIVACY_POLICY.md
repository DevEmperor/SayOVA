# Privacy Policy for Parley

**Effective date:** [date of the first Play release]
**Last updated:** 29 October 2026

This Privacy Policy explains how **Parley** (the "App", application ID
`net.devemperor.parley`), a voice assistant for Wear OS watches, handles your
information. The App is developed and maintained by **Jannis Zahn**, trading as
DevEmperor (the "Developer", "we", "us"), Talstraße 84, 35625 Hüttenberg, Germany —
the data controller for the limited purposes described below.

If you have any questions about this policy, contact us at
**contact@devemperor.net**.

---

## 1. Summary (the short version)

- **Your conversations go straight from your watch to the AI provider you chose**
  (Google Gemini or OpenAI), using **your own API key**. They never pass through a
  server of ours.
- **We do not receive your conversations, your recordings or your API key.** The App
  has no account, no analytics, no advertising and no crash-reporting SDK.
- **Your API key is stored encrypted on your watch** and never leaves it. When you
  hand it over with the pairing page, it is encrypted in your browser for your watch
  alone; our relay only ever sees data it cannot read and deletes it within ten
  minutes (section 2.3).
- **Your conversation history stays on your watch**, for as long as you choose
  (default 30 days), and is never backed up to the cloud.
- **What you ask Parley to remember stays on your watch** too. It is given to your
  provider at the start of each conversation, and you can delete it at any time
  (section 2.4).
- **Your location, heart rate, steps, calendar, contacts, notifications and the other
  watch features are used only when you ask for them in a conversation**, and only after
  you allowed it. What is read goes to your provider as part of that conversation;
  nothing is collected in the background (section 2.2). For the weather, the App asks
  MET Norway with the place's rounded coordinates.
- **The provider you choose processes your audio and text under its own terms**
  (section 4). Read them, in particular what the free tiers allow the provider to do.
- **The purchase is handled by Google Play** (section 5).
- **All of this can be checked**: the App and the relay are open source (section 8).

---

## 2. What information the App processes

### 2.1 Your voice and your conversations

While a conversation is open, the App records the watch's microphone and streams the
audio, in small pieces, **directly to the provider you selected**. The provider
returns spoken audio and the text of both sides of the conversation, which the App
plays and shows on the watch.

- Audio is **only recorded while a conversation is open**. The watch shows this with
  the conversation screen and an icon on the watch face; the conversation ends when
  you tap *End*, when you say goodbye, or after a pause you can set (default 20
  seconds).
- The App **does not store audio**. Recordings exist only in memory while they are
  being sent.
- In silent mode the microphone only listens while you hold it open with a tap.

### 2.2 What the assistant can do on the watch

When you ask for it, the assistant can use a few functions on the watch: read the
date, time, time zone and battery level; set a timer or an alarm in the watch's clock
app; save a note in the App's history; remember or forget a fact about you (section
2.4); end the conversation; and, if web search is on,
look something up. Each of these runs on the watch; the result (for example "timer
set") is sent back to the provider as part of the conversation.

**Watch features.** If you allow them, the assistant can also use the watch's own
sensors and data, each only when your question needs it:

- **Location** – the watch's current position (precise or approximate, as you choose
  in Android's permission dialog), for example for "What's the weather here?". To
  name the place, the App asks the watch's geocoder, which on Wear OS is provided by
  Google Play services and sends the coordinates to Google.
- **Heart rate** – measured for a few seconds with the watch's sensor through Android's
  Health Services. A watch is no medical device; the assistant is told not to diagnose.
- **Steps** – the number of steps the watch has counted today.
- **Calendar** – the events from now up to at most 14 days ahead that are stored on
  the watch: title, start, end and location.
- **Weather** – the forecast for the watch's location or a place you name, from the
  Norwegian Meteorological Institute (MET Norway, api.met.no). The App sends MET Norway
  only the place's coordinates, rounded to about a kilometre, and a fixed app name; like
  every web service, MET Norway receives the watch's IP address. Its terms:
  https://api.met.no/doc/TermsOfService. A place you name is turned into coordinates by
  the watch's geocoder (Google, as above).
- **Air pressure and altitude** – one reading of the watch's barometer. For the
  altitude, the App takes the pressure at sea level from the weather forecast for the
  watch's last known position, if it has one from the last half hour.
- **Next alarm** – the time of the next alarm set on the watch, as Android reports it.
- **Music** – play, pause, next and previous for whatever app plays on the watch, sent
  like a headphone button. Which title plays is read only with notification access
  (below).
- **Calls and contacts** – the names and phone numbers in the watch's contacts that
  match the name you said (at most eight). The assistant is told to call only after you
  confirmed the name and number; the call is placed by the watch's phone app after the
  conversation ended.
- **Apps** – the names of the apps installed on the watch, to open the one you name.
- **Notifications** – the notifications currently on the watch (app, title and text,
  at most ten, without ongoing ones such as music). Android grants this access only in
  its system settings, not through a permission dialog; on some watches it can only be
  granted with developer tools.

Each feature needs an Android permission or access, which the watch asks for when you
switch the feature on under *Settings → Watch features* or when a conversation first
needs it; weather, air pressure, next alarm, music and apps need none. You can switch
every feature off there (the App then also hands the permission back) or withdraw the
permission in the watch's system settings. The App reads these data only during the
conversation in which you ask for them, never in the background, and keeps nothing of
them except the short line the conversation shows (for example "Heart rate · 72 bpm" or
"Location · Giessen"), which is part of the history (section 2.4). While a feature is in
use, the conversation screen shows its icon.

Your heart rate is health data, which the GDPR protects specially (Art. 9). It is read
and sent to your provider only when you explicitly ask for it, after you have allowed
the App to access it (Art. 9(2)(a) GDPR); the same applies to the other features
under Art. 6(1)(a) GDPR. You can withdraw your consent at any time as described above.
Contacts and notifications can contain data about other people; the App reads them only
for your question and passes on only what it needs to answer it.

Web search is performed by the provider: with Google Gemini through Google Search, with
OpenAI through a separate request to OpenAI's web search, made with your key. You can
turn web search off in the settings.

### 2.3 Your API key and the pairing page

To talk to a provider, the App needs an API key from your account with that provider.
The App receives it through the pairing page:

1. The watch creates a one-time key pair and shows a QR code containing the public
   half, the pairing code and the provider. This information is in the part of the
   address after `#`, which browsers never send to any server.
2. On the pairing page, your browser encrypts your API key to the watch's public key
   (HPKE, RFC 9180) **before** anything is sent.
3. Our relay, a Cloudflare Worker, receives only this ciphertext and the random pairing
   code, holds it for at most ten minutes and deletes it as soon as your watch has
   fetched it. It cannot read the key. It keeps no logs of the requests.
4. The watch decrypts the key, shows a six-digit code that your browser also shows, and
   uses the key only after you confirm that both match.

On the watch, the key is encrypted with a key held in the Android Keystore and stored in
the App's private storage. It is excluded from every backup. Only the last four
characters are ever shown.

Like every web service, Cloudflare processes the IP address of the devices that
contact the relay in order to deliver the connection (Cloudflare's privacy policy:
https://www.cloudflare.com/privacypolicy/). Legal basis: our legitimate interest in
offering a convenient and secure way to set up the App (Art. 6(1)(f) GDPR).

### 2.4 History and settings

- **History:** the text of your conversations, the notes the assistant saved, the
  lines naming what a watch feature returned (section 2.2), the
  provider and model used and the estimated cost are stored in the App's private
  storage on the watch. You choose how long they are kept (not at all, 7 days, 30
  days or forever) and can delete single conversations or everything at any time.
- **Memory:** facts you explicitly ask the assistant to remember ("Remember that I am
  vegetarian") are stored in the App's private storage on the watch, at most 30 short
  ones. While the memory is on, they are part of the instructions sent to your provider
  at the start of every conversation, so that the assistant can use them. The assistant
  is told never to remember passwords, PINs, card or account numbers. You can see every
  fact in the settings, delete single ones or all of them, or ask the assistant to
  forget one; turning the memory off stops sending them.
- **Settings and statistics:** your provider, model, voice, language, personality,
  behaviour options and the estimated cost and talk time per day are stored on the
  watch.
- **No backups:** the App opts out of Android's cloud backup and device transfer, so
  neither your history nor your memory nor your key nor your settings leave the watch
  that way.

### 2.5 Reporting a response

If an answer was offensive or wrong, you can report it from the history. The App then
opens an e-mail on your paired phone, addressed to us and containing the answer you
picked and the model that produced it. Nothing is sent unless you send that e-mail
yourself. We use reports only to understand and pass on problems with the providers'
models (Art. 6(1)(f) GDPR) and delete them when they are dealt with.

---

## 3. What we do not do

- We do not run a server that sees your conversations.
- We do not collect analytics, device identifiers or usage statistics.
- We do not show advertising and do not use your data for advertising.
- We do not sell or share any data.

---

## 4. The AI providers

The provider you choose processes your audio, the conversation text and the results of
the functions above in order to answer you. It does so as an independent controller (or
as your processor, depending on your contract with it) under its own terms, with the
API key of **your** account:

- **Google Gemini API** — terms: https://ai.google.dev/gemini-api/terms, privacy:
  https://policies.google.com/privacy. On the **free tier**, Google uses what you send
  and the responses to provide, improve and develop its products, and human reviewers
  may read them; Google asks not to submit sensitive or personal information there. On
  the **paid tier**, Google does not use your prompts or responses to improve its
  products and keeps them only for a limited time to detect abuse. Google's terms also
  require the paid tier for API clients offered to users in the European Economic Area,
  Switzerland and the United Kingdom. We recommend the paid tier.
- **OpenAI API** — terms: https://openai.com/policies/, privacy:
  https://openai.com/policies/privacy-policy/.

The providers may process data in other countries, including the United States.

---

## 5. Google Play

The App is sold through **Google Play**. Google is the seller and an independent
controller for the purchase and its payment; we never see your payment details, your
name or your email address. See https://policies.google.com/privacy.

As the seller's partner, we receive from Google:

- **Order and sales reports** — for example the order number, date, price and the
  buyer's country or region — which we need for accounting, tax and refunds. They do
  not contain your name, email address or payment details.
- **Aggregated statistics** about installs and ratings, and — only if you allow your
  devices to share usage and diagnostics data with Google — anonymous crash and
  performance reports (Android vitals).

**Legal bases:** we process the order reports to fulfil the purchase contract,
including refunds (Art. 6(1)(b) GDPR), and to meet our accounting and tax obligations
(Art. 6(1)(c) GDPR); we keep them for as long as tax law requires. We use the anonymous
statistics and crash reports to fix errors and improve the App (Art. 6(1)(f) GDPR).

---

## 6. Permissions and why they are used

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` (microphone) | To hear you while a conversation is open (section 2.1). |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | To talk to the provider, and to ask for Wi-Fi or LTE during a conversation instead of the slower Bluetooth link to the phone. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK` | To keep a conversation running while the screen is off. |
| `POST_NOTIFICATIONS` | To show that a conversation is running (watch-face icon and notification). |
| `VIBRATE` | Short vibrations when Parley starts listening, answers or does something. |
| `SET_ALARM` | To set timers and alarms in the clock app when you ask for it. |
| `MODIFY_AUDIO_SETTINGS` | To set Parley's own volume and run a conversation on the watch speaker as a call (echo cancelling). |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Location and the weather here (section 2.2), only if you allow it. |
| `BODY_SENSORS` (up to Android 15), `health.READ_HEART_RATE` | Heart rate (section 2.2), only if you allow it. |
| `ACTIVITY_RECOGNITION` | Today's steps (section 2.2), only if you allow it. |
| `READ_CALENDAR` | Calendar (section 2.2), only if you allow it. |
| `READ_CONTACTS`, `CALL_PHONE` | Calls and contacts (section 2.2), only if you allow it. |
| `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_HEALTH` | To use location or heart rate in a conversation that runs with the screen off. |
| Notification access (system setting) | Notifications and the title of the music that plays (section 2.2), only if you grant it. |

The App lists the watch's apps that have a launcher icon, to open the one you ask for.
It requests **no storage, SMS or account permissions**.

---

## 7. Data storage and retention

- **On your watch:** your key until you remove it; your history for the period you
  chose; your settings until you change them. Uninstalling the App or clearing its
  storage removes all of it.
- **On our relay:** a pairing ciphertext for at most ten minutes, deleted on pickup.
- **With MET Norway:** weather requests under its terms (section 2.2).
- **With the Developer:** reports you e-mailed us until they are dealt with; Play order
  reports as long as tax law requires.
- **With your provider:** under its terms (section 4).

---

## 8. You do not have to take our word for any of this

Parley is open source, under the Apache License 2.0:

**<https://github.com/DevEmperor/Parley>**

You can read every line this policy describes: where audio goes
(`core/src/main/kotlin/…/provider/`), how the key is encrypted on the page and on the
watch (`pair/src/page/crypto.ts`, `core/src/main/kotlin/…/pairing/`), what the relay
does and keeps (`pair/src/worker.ts`), and what is stored on the watch
(`wear/src/main/kotlin/…/data/`). You are free to build the App yourself rather than
install ours.

One honest limit: published source is not proof of what is installed or deployed. The
version on Google Play and the relay are built from this repository, but nobody can
verify that byte for byte from outside.

---

## 9. Data security

Connections to the providers and to the relay are encrypted (TLS). The API key is
encrypted end to end on its way to the watch and encrypted at rest with a key from the
Android Keystore. History and settings are kept in the App's private, sandboxed
storage. No method of transmission or storage is 100% secure.

---

## 10. Age

The Gemini API may only be used by people aged 18 or over, and the App is meant for
adults. It is not directed at children.

---

## 11. Your choices and rights

- You can stop a conversation at any time, mute the microphone, or use push-to-talk.
- You can turn off web search, shorten or switch off the history, delete conversations
  and remove your key in the App.
- Under the GDPR you have the rights of access, rectification, erasure, restriction,
  data portability and objection (Art. 15–21), and the right to complain to a
  supervisory authority. As we hold no data about you apart from Play order reports and
  reports you sent us, there is usually nothing we could look up; for your
  conversations, please contact your provider.

---

## 12. Changes to this policy

We may update this Privacy Policy from time to time. Material changes will be reflected
by updating the "Last updated" date above and publishing the new version at this
location.

---

## 13. Contact

**Jannis Zahn** (DevEmperor) — contact@devemperor.net
Talstraße 84, 35625 Hüttenberg, Germany

Project repository: https://github.com/DevEmperor/Parley
