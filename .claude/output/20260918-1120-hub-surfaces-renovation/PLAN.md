# csync-hub UI renovation: the five surfaces

Renovation class: surface conversion across five surfaces, two of them new
(Home, Settings) and three converted (Chat, Share, Tools). It runs against the
fixed backend contract in
/Users/alcatraz627/Code/Claude/csync/.claude/output/20260918-1115-hub-contract-change/plan.md
and the ratified decisions in /Users/alcatraz627/Code/csync-hub/docs/ui-design-brief.md.

The app has no design system today (plain framework Material theme, plain
`Activity` with a `FrameLayout` page-swap in `MainActivity.java`). So the
inheritance sweep returns mostly empty and this plan establishes the design
language as new precedent, flagged for owner sign-off.

## The goal, as the decision the user leaves with

Per surface, the decision the user makes there, not the data shown:

- Home: "is my mesh healthy and what can I do right now." Today: does not exist.
- Chat: "get a real answer from my home assistant and see how it got there." Today: flat text, feels like a toy.
- Share: "send this to a named device of mine and know it arrived." Today: raw-IP send, no target picker, no inbox.
- Tools: "check the widget and the phone's processes." Today: two separate tabs.
- Settings: "configure peers, token, provider, and the shell gate." Today: config is scattered on the Devices tab.

Work triggers, at least one per surface, all fire: affordance gap (Home and
Settings absent; Share has no target picker; Chat hides tool calls and thinking)
and information gap (no Tailscale status anywhere). Dialect drift does not apply,
there being no prior dialect. No surface returns `no build`.

## The problems, each falsifiable

| # | Observation | Cost | Check |
|---|---|---|---|
| 1 | Chat renders replies as a single `TextView` (`MainActivity.replaceLastReply`); no markdown, no tool calls, no thinking. | The assistant reads as a toy; the owner cannot see what it did. | Chat shows a markdown-formatted answer, a tool-call entry, and a collapsible thinking entry for a health question. |
| 2 | No status of Tailscale, the home peer, or the assistant anywhere in the app. | A cold or off link is invisible until an action fails, the exact confusion the owner hit. | Home shows three status dots reflecting real reachability, and one reads red when Tailscale is off. |
| 3 | Share target is the saved `home_ip` only; no way to pick a device (`page_devices.xml`). | The owner cannot send to a chosen device by name. | Share shows the persisted named roster; tapping one sets the send target. |
| 4 | Config (peer IP, token, assist IP, receiver) sits on the action tab (`page_devices.xml`). | Actions and setup are tangled; the tab does too much. | Settings holds peers, token, provider, run_command toggle, assist peer; Share holds only actions. |
| 5 | No Home surface; the app opens on the xkcd info page. | No orientation, no "where is what", no at-a-glance state. | Home is the first tab and shows status, capabilities, and the two primary actions. |

Non-claims (watch, do not chase): the System monitor's 3s refresh cadence looks
heavy but is a deliberate existing choice; leave it.

## Inheritance ledger

The sweep across the app returns empty for every design decision (no tokens, no
card idiom, no status idiom), so these are new precedents needing owner sign-off,
not adopted values.

| Decision | Inherited value | Evidence | Adopted or new precedent |
|---|---|---|---|
| Color tokens | none | no colors.xml or theme tokens in the tree | new precedent |
| Card idiom | none | plain LinearLayouts in `page_*.xml` | new precedent |
| Status dot | none | absent | new precedent |
| Chat bubble | none | single TextView | new precedent |
| Tool-call chip | none | absent | new precedent |
| Nav | plain Button row | `activity_main.xml` | convert to a bottom nav (new precedent) |
| Markdown render | none | absent | new precedent (add Markwon) |

New-precedent design language proposed, for owner sign-off:

- Colors: bg 121212, surface 1E1E1E, surface-2 262626, text ECECEC, dim 9E9E9E, accent 4C9BE8 (the launcher blue), online 46C46A, offline/error E5534B, border 333333. Dark only for now.
- Spacing scale: 4, 8, 12, 16, 24.
- Type: title 20sp, section 16sp bold, body 15sp, mono 13sp, caption 12sp dim.
- Idioms: card (surface, 12dp radius, 12dp pad), status dot (10dp circle in online/offline/dim), chat bubble (user right on accent, assistant left on surface), tool-call chip (surface-2, rounded, mono label, tap to expand args and result), section header (16sp bold dim).
- Framework: adopt com.google.android.material for BottomNavigationView, Chip, and MaterialCardView. Justified: it is standard Android, not heavy, and gives the nav, chips, and cards this design needs. The app already sets useAndroidX=true for Shizuku.

## Semantic element descriptions

Each entry: role, content source, component, state behavior.

Home:
1. Status row. role information-display. source TailStatus {tailscale, homePeerReachable, assistReachable}. component three status dots + labels in a card. states: boot disabled-real (dots dim), loaded real colors, error offline red.
2. Capabilities list. role information-display. source GET /capabilities tools[]. component a card listing tool names + descriptions. states: honest bone while fetching, loaded list, empty "assistant unreachable".
3. Primary actions. role control-surface. source static. component two large buttons Share and Chat. states: static.

Chat (the embryo surface):
1. Turn list. role information-display primary, control-surface secondary. source /chat turns[] {thinking, tool_call, text}. component a scrolling list: thinking as a collapsible dim block, tool_call as a Chip expanding to args+result, text as Markwon-rendered markdown in an assistant bubble; user message in an accent bubble. states: boot empty, sending shows a live "thinking" placeholder (disabled-real, not a bone), loaded turns, error a red inline row with the Tailscale-aware message.
2. Capabilities hint. role information-display. source /capabilities. component a one-line "ask me to: health, devices, message a device" under the input. states: static once fetched.
3. Composer. role control-surface. source user input. component input + send + New. states: static; send disabled while a reply is in flight.

Share:
1. Peer picker. role control-surface. source PeerStore peers[] {name, role, online}. component a card list of named devices each with a status dot; tap selects the target. states: honest bone on first scan, loaded roster, empty "scan to find devices".
2. Send controls. role control-surface. source user. component text field + Send text + Send clipboard, with the selected target named on the button. states: static; in-flight shows a spinner.
3. Inbox. role information-display. source getExternalFilesDir/inbox. component a list of received items with open/copy. states: empty "nothing received", loaded list.

Tools:
1. xkcd panel. role information-display. source static copy. component a card with the widget instructions. states: static.
2. System monitor. role information-display. source Shizuku top. component the existing mono scroll in a card. states: existing behavior preserved.

Settings:
1. Peers editor. role control-surface. source PeerStore. component list with add-by-name. states: loaded.
2. Token, assist peer, provider switch, run_command toggle. role control-surface. source Prefs + provider state. component labeled fields and switches. states: loaded; the run_command toggle reflects the Pi flag and warns it enables shell.

## Directives, with checks

| ID | Directive | Check |
|---|---|---|
| D-COLOR-01 | All surfaces use the token set (no hardcoded hex outside the one tokens file). | `rg '#[0-9A-Fa-f]{6}' app/src/main/res/layout` returns nothing; colors live in colors.xml. |
| D-STATUS-01 | Home status dots reflect real reachability from TailStatus. | with Tailscale off on the phone, the tailscale dot renders offline color. |
| D-CHAT-01 | Assistant text renders as markdown via Markwon. | a reply containing `**bold**` and a list renders formatted, not literal. |
| D-CHAT-02 | Tool calls render as expandable chips showing name, args, result. | a health question shows a chip labeled home_health that expands to the result. |
| D-CHAT-03 | Thinking renders as a collapsible dim block, collapsed by default. | a reply with a thinking turn shows a "thinking" toggle. |
| D-CHAT-04 | In-flight send shows a real placeholder, not a bone, and disables send. | sending shows a "thinking..." row and a disabled send button until the reply lands. |
| D-SHARE-01 | Share target is chosen from the named roster; no raw-IP field on this surface. | `rg 'inputType' app/src/main/res/layout/page_share.xml` shows no IP entry; the picker lists names. |
| D-PEER-01 | The roster persists across app restarts and shows online/offline. | kill the app, reopen, roster is present; a downed peer shows offline within one refresh. |
| D-NAV-01 | Bottom nav has five items and highlights the current surface. | all five tabs switch content and the active one is visually marked. |
| D-NOTIFY-01 | A backgrounded assistant reply posts a notification with the reply text. | send, background the app, reply arrives as a notification. |

On approval these become `app/.claude/ui/checks/hub-surfaces.sh`, committed with the change.

## Structural skeleton

Structural skeleton, meaning the region cast. Not a loading skeleton; loading
placeholders are specified per region as readiness classes above.

Shell: a single Activity with a Material BottomNavigationView (5 items: Home,
Share, Chat, Tools, Settings) over a content FrameLayout that swaps five page
views, replacing the current 4-button row. Dark theme tokens applied app-wide.

Region cast per surface, rendered with real copy and kit components, zero data:
- Home: status card (3 dim dots + labels), capabilities card (placeholder rows), two action buttons.
- Chat: scroll list (one sample user bubble, one assistant bubble with markdown, one tool chip, one collapsed thinking block), capabilities hint line, composer row.
- Share: peer card (placeholder rows with dim dots), send controls row, inbox card (empty state).
- Tools: xkcd card, monitor card (empty mono view).
- Settings: labeled fields and switches, all inert.

Static-first pass: the capabilities hint, the action buttons, the section
headers, and the xkcd copy are known at build time and render as themselves in
the skeleton, not as placeholders.

## Embryo

Embryo is Chat, taken fully alive: real /chat call returning structured turns,
Markwon markdown, expandable tool chips, collapsible thinking, the in-flight
placeholder, the error row with the Tailscale-aware message, and the background
notification path. Chat is chosen because it alone exercises every state class:
boot, in-flight, loaded, tool-call, thinking, error, and background delivery.
Once the owner signs off on Chat, the other four surfaces are repetition of the
card, dot, list, and state idioms it establishes.

## Parity ledger

The build is not done until every row carries a verified result.

| Must still hold | Check | Result |
|---|---|---|
| xkcd home-screen widget refreshes | add the widget, it fetches a comic | pending |
| Shizuku system monitor shows live top | System surface shows process output | pending |
| Mesh send and receive work | csync-agent send to the phone still lands | pending |
| Share sheet delivers to a peer | a Gallery share still arrives on the Mac | pending |
| Existing chat replies still show | a reply renders (now as markdown) | pending |
| run_command stays gated | the disabled negative test still refuses | pending |
| The token is still required | an unauthorized call still fails | pending |

## Other instructions

- Executor: on approval, build through /bloop, seeding the validator's attack list from the D-* checks verbatim.
- Post-build gate: /ui-categorical-check over the full catalog on the built surfaces.
- Evidence: the validation report path lands in this plan's dispositions.
- Commit scope: this change owns the csync-hub app tree; commit with a scoped git add, never git add -A.
- Surface claim: this session holds these files; no other session is editing csync-hub.
- Build order: contract first (the P0/P1 plan), then this UI shell and the Chat embryo, then the other four surfaces, then fold in Later (run_command enable, provider switch, push) as Settings controls.
- What not to touch: the mesh agent wire protocol, the share-sheet intent handler, the widget provider and job service, the Shizuku path, the run_command gate.

## Owner sign-off needed

- The new-precedent design language (colors, idioms, adopting Material Components). This is the one ruling that gates the build, since the app has no prior design to inherit.
