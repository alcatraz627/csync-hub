# csync hub: UI design brief

The brief a design pass (human or subagent) works from. It states what the app
is for, the surfaces it has, and, per Norman's dimensions, what each surface owes
the user and where it currently falls short.

## What the app is for

One app on the phone that reaches the owner's own devices over Tailscale. Three
jobs today:

1. Quick-share text, files, and images to another device (mainly the Mac).
2. Talk to a personal assistant that runs on the Pi (Gemini backed).
3. A couple of on-device utilities: the xkcd widget companion and a system monitor.

The through-line is "reach my other machines from my phone, from anywhere." The
share and the assistant are the load-bearing features; the utilities are extras
that happened to seed the app.

## Surfaces today

| Tab | Job | State shown | Actions |
|---|---|---|---|
| XKCD | explain the widget | static text | none (widget lives on the home screen) |
| System | live process monitor | top output | refresh |
| Devices | mesh send + receive + config | this device, peer scan, receiver status | save config, send text, send clipboard, scan, start/stop receiving |
| Chat | assistant conversation | conversation log | send, new, set assistant IP |

## The gaps, by Norman's dimensions

### Orientation and docs (the owner's explicit ask)
There is no home surface. A new session lands on the xkcd page, which says
nothing about what the app does or where things are. The owner asked for "much
more, the basic docs, where is what."

Proposed: a Home tab as the first surface. It carries a one-line statement of
each capability, a live status row (below), and the two primary actions (share,
chat). It is the map of the app.

### Affordances (what actions are possible)
- A scanned peer is dead text. It should afford acting on it: tap a peer to make
  it the send target, or to send to it.
- A received item is invisible; the file lands in a folder the owner never sees.
  An inbox list would afford opening, copying, or clearing what arrived.

### Signifiers (how the owner knows what is possible and where)
- Buttons are unlabeled-by-icon and flat; nothing signals which action is primary.
- No signifier of reachability. The owner cannot tell if the Mac is up, the
  assistant is running, or the receiver is on, until an action fails.

Proposed: a status row (Mac reachable, assistant reachable, receiver on/off) with
colored dots, visible on Home and Devices. Icons on the primary actions.

### Mappings (control to effect)
- Two peer concepts exist ("home peer" for mesh, "assistant peer" for chat) and
  the relationship is implicit. The owner has to know that share goes to the home
  peer and chat goes to the assistant peer.
- Sending has no visible target selection; it always uses the saved home peer.

Proposed: name the target on the action ("Send to Mac", "Chat with Pi"), and let
a peer picker change it. Fold both peer settings into one Settings surface.

### Feedback (response to every action)
- Sends report a single status line; success and failure look similar.
- No in-flight indicator for a send (chat already shows a "…" placeholder, which
  is the right pattern to copy).
- Received text raises a notification; as of now it shows the content, not the
  filename. A chat reply that arrives while the app is backgrounded raises nothing.

Proposed: consistent toast plus inline status for every action; a spinner while a
send or chat is in flight; a phone notification when an assistant reply lands and
the app is not in front.

### Information display (what state is shown)
- Device identity and tailnet IP: good, keep.
- Peer scan: raw monospace, should be a list of rows with a reachability dot.
- Conversation: prefixed lines ("You:", "csync:"). Bubbles with sender alignment
  and timestamps would read far better.
- No inbox view.

## Proposed surface set after the pass

Home (map + status + primary actions) · Share (was Devices, action-first, peer
picker, inbox) · Chat (bubbles, reply notification) · Tools (xkcd + system,
folded together) · Settings (peers, token, receiver, assistant IP).

## Decisions (ratified by the owner)

1. Five surfaces: Home, Share, Chat, Tools, Settings.
2. Share target is pickable per send, from a persisted roster of named devices.
3. A phone notification fires when an assistant reply arrives in the background.
4. The assistant gets tools now, not chat-only.

## Peer model (the owner's key refinement)

Raw IP entry is wrong for the owner's own in-VPN devices. Devices are referenced
by name, resolved by Tailscale MagicDNS, which is confirmed working from the
phone: `http://raspberrypi:8791/whoami` resolved and answered.

So:
- The app keeps a persisted roster of devices, each a name (raspberrypi,
  aakarshs-m5-pro), a role (peer or assist), and a last-seen time.
- The roster is seeded from an agent's `/peers` and refreshed by probing each
  name's `/whoami`; online or offline is that probe's result.
- The owner picks a device by name. IPs never appear in the UI; names resolve via
  MagicDNS at call time.
- Bootstrap needs one reachable device to read `/peers` from; after that the
  roster persists. The one secret is still the mesh token.

## Assistant tools (phase decided: build now)

The Pi assistant moves from chat-only to tool-capable. Candidate first tools,
all Pi-side and tailnet-bounded:
- run a shell command on the Pi (guarded, output returned)
- home-server health (disk, services, uptime, Jellyfin up)
- mesh awareness (list peers, send a file to a peer)

Design so a tool call is a structured step the model requests and the service
executes, with the result fed back. Start with a small, safe set; every tool is
logged. Security rests on the tailnet plus the mesh token, same as the rest.

## Provider note

The assistant backend is Gemini today (gemini-3.8-flash). A Claude backend
(`CLAUDE_CODE_OAUTH_TOKEN` at `~/.config/csync/claude.key`) is planned as a
provider switch on the Pi (`assist.provider` = gemini or claude), not wired yet;
Gemini stays the daily driver.
