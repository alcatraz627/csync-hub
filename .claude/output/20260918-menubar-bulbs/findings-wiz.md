# Wiz bulb control in home-server (verified)

Source: `/Users/alcatraz627/Code/Personal/home-server`, verified against the code,
not just the agent abstract.

## Control mechanism
- Hand-rolled WiZ LAN protocol over UDP (the pywizlight protocol), no library.
  `src/lib/server/wiz.ts` uses `node:dgram` on port 38899.
- Methods: `getPilot` (read state), `setPilot` (write state), plus discovery via
  broadcast and `getSystemConfig`.
- `setBulbState(ip, params)` at `wiz.ts:84`, params typed as
  `{ state?: boolean; dimming?: number; r?: number; g?: number; b?: number; temp?: number; sceneId?: number }`.
  On/off is `{ state: true|false }`; brightness is `dimming` 0..100; colour is r/g/b
  or `temp` (kelvin); `sceneId` for scenes.
- Success is `response.result.success === true`.

## Server API (what a client would call)
- `GET /api/lights` lists discovered bulbs with state.
- `GET /api/lights/:ip` reads one bulb.
- `PUT /api/lights/:ip` sets one bulb; JSON body is the `setBulbState` params, e.g.
  `{"state": true}` to turn on. (`src/routes/api/lights/[ip]/+server.ts:11`.)
- `GET/POST /api/lights/config` reads and writes names, rooms, and presets.

## Identity and config
- Bulbs are not hardcoded. They are discovered live by UDP broadcast, then matched
  to user-assigned names, rooms, and presets stored in `~/.home-server/lights.json`.
- Bulbs are addressed by IP on the LAN.

## Security boundary
- Control is strictly LAN-local UDP; there is no cloud API.
- The `/api/lights` HTTP endpoints have no auth. Over a tailnet the tailnet is the
  boundary, same as csync, but adding the mesh token would make it consistent.
