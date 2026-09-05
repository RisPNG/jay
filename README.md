<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="104" height="104" alt="Jay app icon">
</p>

<h1 align="center">Jay</h1>

<p align="center">
  <strong>A social clock app for Android.</strong><br>
  Alarms and timers, for yourself and the people around you.
</p>

<p align="center">
  <a href="https://github.com/RisPNG/jay/releases/latest">Download</a> ·
  <a href="#what-you-can-do">Features</a> ·
  <a href="#getting-started">Get started</a> ·
  <a href="#self-hosting">Self-host</a> ·
  <a href="#contributing">Contribute</a>
</p>

---

Jay is your run-of-the-mill clock app for Android, with groups, shared alarms, and shared timers. It is built on [Clock You by You Apps](https://github.com/you-apps/ClockYou), so you still get the personal alarms, world clocks, stopwatch, and widgets you would expect. Sharing is there for when you want it.

**No login. No account registration. No telemetry\*. Self-hostable.**

Jay creates a profile for you automatically, so getting started is basically choosing a name, creating a group, and inviting someone. If you use more than one device, you can bring the same profile over and continue as the same member.

\* **If you run your own server and control its logging and integrations.** There is a bit more to this than changing the server address: groups need data to synchronise, and a Firebase-enabled app can still contact Firebase. The [privacy section](#privacy-and-your-data) explains what that means in practice.

## What you can do

| Together | On your own |
| --- | --- |
| **Share alarms.** Set the schedule once and synchronise it to your group. | **Keep your daily routine.** Personal alarms, advanced recurrence, world clocks, a stopwatch, and clock widgets come from Clock You. |
| **Start a group countdown.** Hold the timer start button to choose a group, or use a saved group timer. | **Save your usual timers.** Keep a duration, label, sound, vibration pattern, and add-time amount together. |
| **Answer as one.** Let an alarm response apply to the group, or keep responses individual. | **Control timers where you are.** Pause, resume, add time, or reset from the app and notification controls. |
| **See what happened.** Check shared alarm activity, membership changes, and responses. | **Choose how it rings.** Configure full-screen timer alerts, gradual volume increase, vibration, and volume-button actions. |
| **Choose who can edit.** Allow everyone or only group leaders to change shared alarms and timers. | **Use more than one device.** Export and import your profile to continue as the same member. |

### For people in different places

People in a group might live in different time zones, so Jay lets you choose what a shared alarm actually means:

- **Each person's local time:** a 7:00 AM alarm rings at 7:00 AM wherever each member lives.
- **One group time zone:** schedule a shared moment across time zones, such as a call or study session.

The alarm's recurrence, label, snooze settings, vibration, and sound selection are shared too. Timers work from one common expiry time, so everyone is counting down to the same moment.

### Answering together

With **Answer as one** enabled, dismissing or snoozing an alarm also answers the corresponding occurrence for the group. An ignored outcome is shared too. For snoozing, the current ring stops for everyone, but the later snooze ring only happens on the device that answered. This is useful when one person taking care of the alarm is enough.

For a group timer, a member with edit permission can dismiss it for everyone when this setting is enabled. Otherwise, dismissing its ring stays local. Adding time, resetting, and cancelling a shared timer always follow the group's edit permissions.

### Shared sounds[*](#a-note-on-shared-sounds)

You can use each device's default sound, keep it silent, or send a custom sound to the group. Jay prepares the audio and downloads it to the other devices so they can play it locally.

## Download

**[Download the latest release](https://github.com/RisPNG/jay/releases/latest)**

You will need **Android 6.0 or newer**. Open **Assets** on the release page and choose the production APK. There are a few files there, so here is what each one is for:

| File | Who it is for |
| --- | --- |
| `jay-<version>.apk` | Normal installation on your Android phone. |
| `jay-<version>-debug.apk` | Troubleshooting; installs as a separate app. |
| `jay-<version>.aab` | Google Play distribution, not direct installation. |

Download the APK, open it on your phone, and allow installation from the app you used to download it if Android asks. Complete Jay's permission setup so Android can schedule alarms, show notifications, and display ringing alerts as intended.

If you want to try work that is still being tested, look for prereleases in [all releases](https://github.com/RisPNG/jay/releases). Otherwise, the latest production APK is the one to get.

## Getting started

1. **Open Jay and choose your name.** Your profile is created automatically; no email address or password is needed.
2. **Create a group in Groups.** Invite the people you want to share alarms or timers with. Invitations are single-use and expire after 24 hours by default.
3. **Share an alarm.** Choose the group in the alarm editor and set its schedule. Members receive it through synchronisation.
4. **Try a countdown.** Hold the timer start button to choose the group. Save a group timer if you will use it again.
5. **Choose the group's rules.** Decide who can make changes and whether members answer alarms individually or together.

For example, a household can share a wake-up alarm and let whoever gets up first answer it. A study group can save a timer and reuse it for the next session. You can keep your own alarms and timers alongside those; they do not all have to belong to a group.

### Using another device

Use **Export profile** on your existing device, then open the profile link on the other one. Jay asks before importing because this replaces the receiving device's current identity, along with the groups and shared alarms shown there.

Keep that link private, since anyone with it can use the profile. If you are inviting a friend, send a group invitation instead. Devices using the same profile count as one member, so their shared-alarm responses apply across those devices too.

<details>
<summary><strong>How shared links and offline use work</strong></summary>

Invitations and profile exports use ordinary HTTPS links. A verified installation opens them in Jay. Without the app, the browser redirects to Jay's Google Play listing; that fallback requires the listing to be available to the recipient. If it is unavailable, install the APK from GitHub and reopen the original link. Installation does not automatically redeem an invitation.

Group changes require a connection. Already synchronised alarms use local Android scheduling. Jay receives live updates while open, uses Firebase for prompt background synchronisation when configured, and also synchronises on launch, after local operations, on manual refresh, and periodically in the background. Offline devices receive changes when they reconnect; immediate delivery is not guaranteed.

</details>

## Privacy and your data

There is no email-and-password account to register, but the server still needs to know which member is making a request. The generated identity does that. So while there is no login, this does not make group activity anonymous.

Groups need a server to keep track of members, shared alarms and timers, activity, alarm outcomes, and when a device was last seen. Other members can see the activity and responses relevant to their group. The operator and hosting provider also control who can access the infrastructure and what gets logged.

You can use the default service if you do not want to look after a server. If you do host it yourself, you get to decide what is logged and how long it is kept, including the services you connect it to. That is the condition behind **no telemetry\***.

There is one more detail: a Firebase-enabled app still contacts Firebase for push messaging, even if you change the server address. Production builds also check Play access when the server requires it. Basic synchronisation works without those server integrations, but using your own server alone does not mean the app stops contacting every third party. The [integration guide](docs/entitlements.md) explains what each service is used for.

You can reset your identity to leave your groups and start again. Groups where you are the only leader are deleted as part of that reset. By default, the server also removes identities inactive for 120 days; operators can configure that interval.

## Self-hosting

You can run your own Jay server. Read the **[full server guide](server/README.md)** for setup, storage requirements, and configuration.

## Contributing

If something does not work as expected, or you have an idea that could make Jay more useful, please [open an issue](https://github.com/RisPNG/jay/issues). Include your Jay version, Android version, what you did, and what you expected to happen. It makes the problem much easier to understand and reproduce. Keep profile links, invitation tokens, and credentials out of the report.

Jay builds directly on Clock You's source, so improvements to the clock itself should go back upstream. Group features, synchronisation, and the server are developed here. The branches follow that same separation:

```text
Clock You → main → main-canary → jay
```

| Guide | What it covers |
| --- | --- |
| [Upstream workflow](docs/upstream.md) | Branch ownership, contribution flow, and integration boundaries. |
| [Server development](server/README.md) | Local setup, configuration, migrations, and tests. |
| [Releases](docs/releases.md) | Build variants, versioning, signing, and publishing. |
| [Entitlements](docs/entitlements.md) | Shared sound access and optional service integrations. |

## Built on Clock You

A lot of what makes Jay useful is already there because of [You Apps](https://github.com/you-apps) and [Clock You's contributors](https://github.com/you-apps/ClockYou/graphs/contributors). The alarms, world clocks, stopwatch, widgets, and much of the Android behaviour come from their work. Thank you for making that available for others to build on.

Jay is free and open-source software under the [GNU General Public License v3.0](LICENSE).

## A note on shared sounds

\* The Google Play version of Jay is paid, and uploading and choosing custom shared sounds on the default service requires that version. Those audio files need to be stored and delivered to the group, which comes with ongoing storage costs. That is why this is the only feature gated behind Play access.

I'd like to make it free for everyone too; the storage still needs to be paid for. Everything else is available in the free GitHub APK, and receiving and playing a shared sound is free as well. One person can choose the sound without the whole group having to buy the app.

If you run your own server and provide the storage, you can make shared sounds available to everyone there without a Play purchase. The [full server guide](server/README.md) covers that setup, and the [access guide](docs/entitlements.md) explains the checks.
