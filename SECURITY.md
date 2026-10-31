# Security Policy

SayOVA sits on your wrist with an open microphone, holds the API key of your AI provider account, and
can read your location, heart rate, calendar, contacts and notifications when you ask it to. That is a
lot of trust for a small app, so a private way to report a problem matters.

## Reporting a vulnerability

**Please do not open a public issue for anything that could be used against someone's watch, account
or data.** Use GitHub's private reporting instead:

> **[→ Report a vulnerability privately](https://github.com/DevEmperor/SayOVA/security/advisories/new)**

Only you and I can see the report, and it becomes a published advisory once a fix is out — with credit,
unless you would rather not be named. If you cannot use GitHub, write to
[contact@devemperor.net](mailto:contact@devemperor.net) with "Security" in the subject.

Useful in a report: what you did, what happened, the watch model and Wear OS version, and the SayOVA
version from *Settings → About*. A proof of concept is welcome but never required; a clear description
of the mechanism is worth more than a working exploit.

### What to expect

SayOVA is maintained by one person. You will get an acknowledgement as soon as I see the report —
usually within a few days. I will tell you what I found and, if it is a real issue, when a fix ships.
If I go quiet for longer than that, ping the thread; you will not be ignored on purpose.

Fixes go into the next release. Only the latest version on Google Play is supported — there is no
backporting to older versions. Fixes to the pairing relay and the web pages are deployed as soon as
they are ready.

## In scope

The Wear OS app, the pairing page and relay on `pair.sayova.app` (`pair/`), the share page on the same
host, and the website on `sayova.app` (`site/`). In particular:

- Anything that exposes a stored API key — to another app, to someone on the network, to the relay or
  in a backup.
- **Pairing:** anything that lets the relay or someone in between read the key, slip a key of their own
  onto a watch without the user's code comparison, reuse a code, or keep data on the relay beyond the
  ten minutes the [privacy policy](PRIVACY_POLICY.md) promises.
- Anything that sends audio, conversation text, memory or watch data somewhere other than the provider
  the user chose (and, for the weather, MET Norway with rounded coordinates).
- Anything that lets another app make SayOVA listen, read watch data or act — a call, an app opened,
  something prepared on the phone — without the user, or that gets past the confirmation SayOVA asks
  for before a call or a message.
- Anything that reads the conversation history or the memory from outside the app.
- **Pages:** script injection on the pairing, share or website pages, a way around their
  Content-Security-Policy, or content in the part of a share link after `#` reaching a server.
- The test hooks (routes, typed questions, demo mode) being reachable in a release build without
  developer access over adb.

## Not vulnerabilities

These are how the app is built, and reporting them will get you this section quoted back:

- **Your voice and conversation go to the AI provider you chose.** That is what SayOVA is for; the
  [privacy policy](PRIVACY_POLICY.md) describes exactly what leaves the watch and when. How the provider
  handles it is governed by its own terms — on Google's free tier, Google may use it to improve its
  products.
- **Your API key is stored on the watch**, encrypted with a key in the Android Keystore. Someone who
  already has root, an unlocked bootloader or developer access over adb to your watch can attack it —
  that is the Android security model, not a flaw specific to SayOVA. The same goes for the diagnostic
  log, which only adb can switch on.
- **The relay sees IP addresses.** Cloudflare processes them to deliver the connection, as every web
  service does; the relay itself keeps no logs and only ever holds ciphertext.
- **What the AI says** — a wrong, made-up or offensive answer is a model problem, not a security one.
  Report it from the watch: open the conversation in the history and choose *Report a response*.
- Reports produced by an automated scanner with no explanation of how the finding is reachable in
  SayOVA. Send the reasoning, not the tool output.

## Third-party components

SayOVA talks to Google's Gemini API and OpenAI's Realtime API, runs its relay on Cloudflare, and uses
upstream libraries such as Tink, OkHttp and the AndroidX libraries. A vulnerability in one of those is
best reported to that project — but tell me too, so SayOVA can move to a fixed version.
