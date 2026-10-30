# Changelog

## 1.0.0 — unreleased

First version.

- Realtime conversations with Google Gemini Live (`gemini-3.8-live`, `gemini-3.8-live-extended-thinking`)
  and OpenAI Realtime (`gpt-realtime-2.1-mini`, `gpt-realtime-2.1`), with your own API key.
- Interrupt by talking over the answer or by tapping the orb. On the watch speaker Parley keeps the
  microphone on the watch during an answer and tells your voice from its own echo; talking over the
  very start of an answer works too, from the second answer on.
- Talk right away: Parley listens from the tap on, while it still connects, and the home screen wakes
  the watch's Wi-Fi while it shows, so the connection is usually ready sooner.
- The thorough Gemini model answers at once and keeps thinking in the background; the status shows
  "Thinking" until it has said everything, and its thinking counts in the cost.
- Orb-only conversation view by default; a text button shows the live transcript of both sides,
  with the heard part of the answer highlighted, and the choice is remembered.
- Parley's own volume, set with the bezel or the crown (or a long press on the sound button) and
  remembered between conversations; the media volume is handed back when a conversation ends.
- Sound button: switches Parley's voice off for quiet places; the transcript shows instead.
- Usage statistics: cost and talk time this month, the last two weeks, every month and in total.
- Monthly budget ($1 to $50): from 80% the home screen shows the month's cost against it in amber,
  and the statistics show a bar; once it is spent the line turns coral and Parley asks before every
  conversation. On request Parley talks with the Fast model from 80% on. Crossing either mark during
  a conversation shows a short note; the conversation carries on.
- The personality sets how long Parley talks: Friendly explains a how or why in four to six sentences,
  Concise keeps to one, the new Detailed explains with background and examples, the language partner
  keeps its turns short.
- Send to phone: from a conversation in the history, or for several chosen with a long press, Parley
  opens a page on the paired phone with the conversation as plain text and Share (any app: messengers,
  notes, mail) and Copy. The text sits only in the page address after "#", never sent to a server.
  Without a phone the watch says so, instead of claiming to continue on the phone (this also holds
  for reporting, the pairing link and the links under About).
- Interpreter: the third button on the home screen. Pick two of 21 languages (remembered), hold the
  watch between two people, and Parley says everything in the other language, nothing else; both
  sides show as text, the pair in the info line ("DE ⇄ ES").
- Settings grouped anew: a Costs section (usage, monthly budget, show cost), vibration under General.
- Volume: one step below the lowest switches the sound off and reads 0 (the call volume over the
  watch speaker never reaches 0 by itself); turned with the bezel, the volume panel goes after a
  second instead of two and a half.
- Choosing from a list in the settings goes straight back, also with a quick tap; only the voices stay
  open to listen to several.
- Tools: timers, alarms, notes, battery and time, web search.
- Voice samples: tapping a voice in the settings chooses it and plays a short sample, spoken once by
  the provider with your key (well under a cent, counted in the statistics) and then kept on the watch.
- Memory: "Remember that …" keeps a fact about you (up to 30) for every later conversation;
  read, delete or turn it off under Settings → Memory.
- Over the watch speaker a conversation runs as a call, so the watch's echo canceller takes Parley's
  voice out of the microphone; once the echo is quiet enough, talking over an answer is left to the
  provider, which hears the user directly.
- Tapping the orb or Talk on the home screen grows the orb into the conversation; at the end it shrinks
  back onto the home screen's orb.
- Large round buttons on the home screen, and in the conversation three larger controls along the round
  edge (end in the middle, lowest).
- History: hold a conversation to select several and delete them together.
- Watch features: location (weather and places nearby), heart rate, today's steps and the
  calendar as tools. Each asks for its permission when switched on under Settings → Watch
  features or when a conversation first needs it; its icon shows while it is in use. With steps on,
  Parley keeps Health Services counting through the day, since on Galaxy watches it counts only for
  registered apps. "Allow now"
  asks again after a "no"; only once Android stops showing its dialog (after the second "no") does
  the button lead to the watch's settings instead.
- More watch features: the weather straight from MET Norway (here or for a place you name, without a
  web search), air pressure and altitude from the barometer, the next alarm, music control, contacts
  and calls (only after you confirmed name and number; the call starts when the conversation ends),
  opening apps, and reading the notifications (needs notification access in the system settings).
- QR pairing: the key is encrypted in the browser and relayed end to end to the watch; compare the
  six-digit code and tap the check mark.
- Wi-Fi or LTE during conversations, resumed Gemini sessions after network changes.
- History on the watch, tile, complication, ongoing-activity icon on the watch face; a conversation
  from the history can be continued where it left off.
- In 13 languages: English, German, French, Spanish, Italian, Portuguese (Brazil), Dutch, Polish,
  Swedish, Turkish, Japanese, Korean and Chinese (simplified); Parley's language can be set apart from
  the watch's, right in Parley's settings (App language, Wear OS 4 and newer).
