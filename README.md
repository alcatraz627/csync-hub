# csync hub

The phone side of csync: one Android app to reach your own machines over
Tailscale, talk to the assistant on your home server, and see at a glance whether
the mesh is up.

It pairs with the mesh agent and the Gemini-backed assistant in the
[csync](https://github.com/alcatraz627/csync) repo. The phone needs nothing but
the shared mesh token and a device on the tailnet to talk to. Devices are
referenced by name and resolved by MagicDNS, so no IP ever appears in the UI.

## The five surfaces

A bottom nav switches between five screens, built to the ratified Slate design
(light by default, dark available, four accent colours).

- **Home**: the map. Three status dots for Tailscale, the Mac, and the Pi, the
  assistant's current tool list, and the two primary actions.
- **Share**: send typed text or the clipboard to a device you pick by name from a
  persisted roster, each with an online dot, plus an inbox of what has arrived.
- **Chat**: a real conversation with the Pi assistant. Collapsible thinking,
  expandable tool-call chips, markdown answers with code syntax highlighting, and
  a notification when a reply lands while the app is in the background.
- **Tools**: the xkcd home-screen widget's companion page and a live Shizuku
  process monitor.
- **Settings**: theme and accent, the connection config (home peer, assistant
  peer, mesh token, receiver), and the assistant's provider, model, and thinking
  effort, driven by the server's own registry.

## What talks to what

- Mesh send and receive use the agent on port 8790. The assistant uses port 8791.
- The assistant's provider, model, and effort come from `GET /providers` and are
  changed with `POST /config`. A new provider added by a `providers.json` on the
  Pi shows up with no app change.
- A backgrounded chat request runs in a foreground service holding a wakelock, so
  a slow reply still lands and raises a notification, then renders in the chat on
  the next resume.
- Receiving files runs as a foreground service. The shell-command tool stays gated
  behind a flag on the Pi and shows as read-only in Settings.

## Build and install

JDK 17 and the Android SDK. Debug build and install to a device on the tailnet:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Seed the connection in Settings: the home peer's name or tailnet IP, the assistant
peer's name (for example `raspberrypi`), and the mesh token shared with the agent.

## Layout

- `app/src/main/java/com/csync/hub/`: `MainActivity` (surfaces and chat rendering),
  `MeshClient` (the tailnet HTTP calls), `MeshService` and `ChatService` (the
  foreground services), `PeerStore` and `Prefs` (persistence).
- `app/src/main/res/`: `layout/page_*.xml` per surface, the Slate colour tokens in
  `values/` and `values-night/`, and the theme in `themes.xml`.
- `docs/ui-design-brief.md`: the ratified design and peer model.
