SayOVA is built so that there is as little as possible to trust: no account, no server of ours in your
conversations, no analytics, no ads. This page explains where each kind of data goes. The binding text
is the [privacy policy](https://github.com/DevEmperor/SayOVA/blob/main/PRIVACY_POLICY.md).

## At a glance

| Data | Where it goes | Where it stays |
| --- | --- | --- |
| **Your voice** | Straight from the watch to your provider (Google or OpenAI), only while a conversation is open | Not recorded or kept as audio by SayOVA |
| **Conversation text** | To your provider, as part of the conversation | In the [history](History) on the watch, as long as you choose |
| **API key** | Once, encrypted, from your phone's browser to the watch | Encrypted on the watch, never backed up |
| **Memory** | To your provider at the start of each conversation | On the watch until you delete it |
| **Watch features** (location, heart rate, calendar, …) | To your provider, only in the conversation where you ask | Nowhere — read when needed, not stored |
| **Weather** | The place's rounded coordinates to MET Norway | — |
| **Usage statistics** | Nowhere | On the watch (dates, time and cost only) |

There is **no SayOVA server** in a conversation. The only server of ours is the pairing relay on
`pair.sayova.app`, and it only ever sees encrypted data it cannot read.

## Your provider

Your provider processes your audio and text under its own terms, with the key of **your** account.
Read them, in particular what free tiers allow:

- **Google Gemini:** on the **free tier**, Google may use what you send to improve its products, and
  human reviewers may read it. On the **paid tier** it does not. In the EU, the UK and Switzerland
  Google requires the paid tier anyway. We recommend turning on billing — see [API keys](API-keys).
- **OpenAI:** API data is not used for training by default.

## Your API key

1. The watch creates a one-time key pair and shows its public half in the QR code — in the part of the
   address after `#`, which browsers never send to a server.
2. Your phone's browser **encrypts the key** for that watch alone (HPKE, RFC 9180) before sending anything.
3. The relay holds the ciphertext for at most ten minutes and deletes it once the watch has fetched it.
   It keeps no logs of the requests.
4. The watch decrypts it and uses it only after you confirmed that both show the **same code** — so
   nobody can slip in a key of their own.
5. On the watch the key is encrypted with a key in the **Android Keystore**, which cannot be copied off
   the watch, and excluded from every backup. Only its last four characters are ever shown.

## The history and the memory

Stored in SayOVA's private storage on the watch, which no other app can read, and **excluded from
backups** — they never reach a cloud. You choose how long the history is kept, and can delete any
conversation, any fact or everything at any time.

## Watch features

Each [watch feature](Watch-features) is used only when your question needs it, only after you allowed
it, and shows its icon while it is in use. Nothing is read in the background or between conversations.
Switch off whatever you don't want SayOVA to use; a switched-off feature is not even offered to the AI.

## On your phone

[Prepare on phone](Prepare-on-phone) and *Send to phone* put the text, event or conversation **inside
the link** (after `#`). The page on `pair.sayova.app` builds the calendar file or share sheet right in
your phone's browser — the content is never sent to the server.

## Permissions

SayOVA asks for the microphone at the start and for every other permission only when a watch feature
first needs it. [Permissions at a glance](Watch-features#permissions-at-a-glance) lists them all.

## Open source

The app, the pairing relay and the website are [open source](https://github.com/DevEmperor/SayOVA)
under the Apache License 2.0: every claim on this page can be checked in the code.

## Reporting a security problem

Please don't open a public issue — write to [contact@devemperor.net](mailto:contact@devemperor.net).
