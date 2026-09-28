# csync menu-bar app and bulb toggles: direction and plan

## The class of problem

The through-line the owner named: all the devices are not close enough to each
other. csync already pulls the phone, the Mac, and the Pi onto one tailnet with a
shared assistant. This direction extends that to two more surfaces: a macOS
menu-bar presence so the mesh is one click from anywhere on the Mac, and the Wiz
bulbs so the physical room joins the same control plane on both phone and Mac.

## Part 1: the macOS menu-bar app

### Shape
A native Swift app with a SwiftUI `MenuBarExtra` (macOS 13+), living in the top
bar. Its window is a compact popover, not a port of the five Android surfaces. The
menu bar rewards glance and one-tap actions, so the content is chosen for that.

### What it shows (adapted, not one-to-one)
- Status header: three dots for Tailscale, the Mac itself, and the Pi, from the
  same reachability probes the Android Home uses.
- Bulbs: a row of toggles per named bulb or room, the new shared feature.
- Quick share: a small field and a peer picker to send text or the clipboard to a
  named device, the Android Share boiled down to its one action.
- Quick chat: a one-line ask that opens a small results view, or a menu item that
  opens a proper chat window. Full chat is a window, not a menu item.
- Settings: the tailnet peer names, the mesh token, and the assistant provider,
  in the app's Settings window rather than the popover.

Dropped from the phone on purpose: the xkcd widget and the Shizuku process
monitor are Android-only and have no place on the Mac.

### How it talks to the mesh
Same wire as the phone: the mesh agent on 8790 and the assistant on 8791 over the
tailnet, authed with the mesh token. The Mac already runs csync core, so the app
reads the token from the existing csync config rather than asking again. A thin
Swift client mirrors `MeshClient` (whoami, peers, chatTurns, providers, config,
send).

## Part 2: Wiz bulbs and smart switches on both apps

The owner also has three smart switches (plugs) to bring into the same control
plane as the bulbs. They are the same class of problem: a physical device in the
house that should be one tap away from the phone and the Mac. The plan is to treat
switches exactly like bulbs, an on/off toggle per named device, behind the same
home-server endpoint and the same tailnet trust boundary. Open item to settle when
we build this: which protocol the switches speak (Wiz plugs use the same UDP
setPilot as the bulbs, TP-Link Kasa and others differ), and whether home-server
already discovers them or needs a small adapter. If they are Wiz plugs, the
existing `/api/lights` path covers them with no new work; otherwise home-server
grows one adapter and exposes them the same way. The apps do not care either way,
they render toggles from whatever the server lists.

## Part 2a: Wiz bulbs, the verified mechanism

### The mechanism already exists
home-server controls the bulbs over the WiZ LAN UDP protocol and exposes HTTP
routes for it (`findings-wiz.md`). Two ways to reach them from the apps:

- Option A, call home-server over the tailnet. The apps do `GET /api/lights` and
  `PUT /api/lights/:ip {"state": true}`. This reuses the discovery, naming, rooms,
  and presets that home-server already has. It needs home-server reachable on the
  tailnet, and it should carry the mesh token so the endpoints are not open.
- Option B, speak WiZ UDP directly from each app. Android and macOS each
  reimplement discovery and `setPilot`. This works without home-server running,
  but only when the device is on the same LAN as the bulbs (UDP does not cross the
  tailnet), and it duplicates the naming and preset logic in two languages.

Recommendation: Option A. It keeps one brain for the bulbs, matches the csync
"talk to a home server over the tailnet" model, and works from anywhere on the
tailnet, not just the home LAN. The cost is small: add token auth to the three
`/api/lights` routes in home-server, and confirm where it runs and on what port.

### Client work
- Android: a Settings or Home section listing bulbs from `/api/lights`, each a
  Material switch that PUTs the new state. Reuse the accent and card idioms.
- macOS: the bulb toggle row in the popover.

## Part 3: repo structure

Three repos exist or are implied: `csync` (core plus mesh plus assistant),
`csync-hub` (Android), and a new `csync-menubar` (macOS). home-server is its own
existing project.

- Separate repos, the current pattern. Low regret, each ships on its own, matches
  what is already there.
- Monorepo. One place for the shared ideas (the mesh wire, the bulb client), but
  the clients are Kotlin and Swift and share no code in practice, so the benefit
  is mostly organisational, and it costs a migration of two live repos now.

Recommendation: keep separate repos for now and revisit a monorepo only if a
genuinely shared package appears. This is the owner's call, flagged below.

## Phased build (after the decisions below)

1. home-server: add mesh-token auth to the `/api/lights` routes. Small, unblocks
   both clients safely.
2. Android: bulb toggles calling `/api/lights`. Ships inside the existing app.
3. macOS: scaffold the `MenuBarExtra` app, the Swift mesh client, and the status
   header. First real surface is status plus bulbs.
4. macOS: quick share, then the chat window, then Settings.
5. Verify each on the real devices, same as the phone build.

## Decisions needed from the owner

1. Repo: separate `csync-menubar`, or fold everything into a monorepo now?
   (Recommended: separate.)
2. Bulb path: call home-server over the tailnet (recommended), or native WiZ UDP
   in each app?
3. home-server runtime: which machine does it run on, and on what port, so the
   apps can reach it over the tailnet? Is adding mesh-token auth to `/api/lights`
   acceptable?
4. macOS popover scope: is the set above right (status, bulbs, quick share, chat
   window, settings), or trim or add anything?
