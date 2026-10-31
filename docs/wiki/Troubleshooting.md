## Messages on the watch

| Message | What it means | What to do |
| --- | --- | --- |
| **Key not accepted** | Your provider rejected the API key: deleted, revoked or incomplete. | Tap **Connect** and hand over a new key ([API keys](API-keys)). |
| **No credit left** | The provider account has no credit, no billing set up, or reached its spending limit. | Top up, turn on billing or raise the limit at your provider. |
| **Too many requests** | The provider asks to slow down (rate limit, often on a free tier). | Try again in a minute. Turning on billing raises the limits. |
| **Connection lost** | Wi-Fi or mobile data dropped. | Check the connection and tap **Try again**. |
| **The provider ended the call** | Can happen after a very long conversation or a problem on the provider's side. | Start a new conversation — or **Continue** the old one from the history. |
| **Request refused** | The provider declined the request. | Try rephrasing; if it keeps happening, report it from the history. |
| **No key for … yet** | The chosen provider has no key on this watch. | Connect one, or switch the provider in the settings. |
| **The microphone is not available** | Another app holds the microphone (a call, a recording). | End that and try again. |
| **SayOVA needs the microphone** | The microphone permission was taken away. | Open SayOVA and allow it again. |
| **Budget reached** | The month's [budget](Costs-and-budget) is spent. | Talk anyway, or raise the budget in the settings. |

## Pairing

**The QR code doesn't appear / "No connection – check Wi-Fi".** The watch needs the internet to prepare
a code. Check that Wi-Fi or mobile data is on, then try again.

**The page says "Code no longer valid".** A code is valid for ten minutes and only once. Go back on the
watch, choose the provider again and scan the new code.

**"That does not look like a complete API key."** Copy the key again from your provider — completely,
without spaces.

**The codes don't match.** Tap **Doesn't match** on the watch. The key is discarded and a fresh QR code
appears; scan it and send your key again. If it happens again, someone else may have sent a key with your
code — keep the QR code to yourself.

**"The provider rejected this key".** The provider says the key is not valid: copied incompletely,
deleted, or from an account without access. Create a new one.

**"The key is saved, but … could not be reached to check it".** The watch lost the connection right
then. The key is kept; SayOVA checks it with your first conversation.

## Conversations

**SayOVA keeps interrupting itself.** On the watch speaker the microphone hears SayOVA. Make sure
**Settings → Interrupt by voice** is on **Automatic**; in loud places choose **Tap only**.

**SayOVA doesn't react when I talk over it.** With **Tap only** it can't — tap the orb, or switch to
**Automatic**. Speak up a little: SayOVA needs to tell your voice from its own.

**Answers are slow to start.** Check the network under the orb: *via phone* (Bluetooth) is much slower
than *Wi-Fi* or *LTE*. Gemini's *Fast* model answers quickest; *Thorough* thinks first.

**It's too quiet / too loud.** Turn the bezel or crown during a conversation, or hold the sound button.
SayOVA's volume is separate from your media volume. With the **sound off** (tap the sound button) you
only see text.

**The conversation ends by itself.** That is **End after silence** (default 20 seconds). Choose a longer
time or *Never* in the settings.

**SayOVA answers in the wrong language.** Set **Settings → Answer language** to *Automatic* to follow the
language you speak, or fix the language you want.

## Watch features

**"Not allowed" / SayOVA says it may not use something.** You declined the permission once. Open
**Settings → Watch features** and use **Allow in watch settings**, or the watch's settings under
*Apps → SayOVA → Permissions*.

**Notifications can't be read.** That needs notification access, which only the watch's settings can
grant: **Settings → Watch features → Allow in watch settings**, then switch SayOVA on.

**"Not available on this watch".** The watch lacks the sensor or app — for example a barometer for air
pressure.

**Prepare on phone does nothing.** The phone must be connected to the watch (Bluetooth on, nearby). For a
calendar event, open the downloaded file with your calendar app if the phone asks.

## Still stuck?

[Open an issue](https://github.com/DevEmperor/SayOVA/issues) or write to
[contact@devemperor.net](mailto:contact@devemperor.net) — with your watch model and what you tried. For
a wrong or offensive answer, use **Report a response** in the [history](History).
