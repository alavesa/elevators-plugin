# Elevators — modelled elevators with in-cabin buttons for Paper 26.x

Configurable elevator **types** (cabin model, size, speed, doors, sounds, permission), any number of
elevator **instances** with named floors, right-clickable **buttons** inside the cabin or on the
landings (interaction entities, no GUIs), smooth rides, and a built-in `.bbmodel` → resource-pack
exporter for the cabin and button models.

Target: Paper 26.2 / 26.3 (Java 25+ at runtime; the jar is compiled against `paper-api 1.21.4`, Java 21).

## Install

1. Drop `Elevators-<version>.jar` into `plugins/` and start the server once. It creates
   `plugins/Elevators/` with `config.yml`, `types.yml` (two example types), `models/` and `pack/`.
2. Put your Blockbench files into `plugins/Elevators/models/` — `glass_cabin.bbmodel` becomes the
   model name `glass_cabin`. Textures should be embedded (Blockbench default) or placed next to the
   file (or in `models/textures/`).
3. Run `/elevator pack` (also runs automatically at start-up). It writes
   `plugins/Elevators/pack/Elevators-pack.zip`, `Elevators-pack.sha1` and `pack-report.txt`.
4. Host the zip (any HTTP host) and set in `server.properties`:
   `resource-pack=<url>` and `resource-pack-sha1=<contents of Elevators-pack.sha1>`.
   Update the sha1 every time you re-export, or clients keep the old pack.
   If you already run a server pack, merge: copy `assets/elevators/` into it and add the cases from
   `assets/minecraft/items/<base-item>.json` to your own items file for that item.
5. Build an elevator in game (below). Players need a real floor block to stand on at every level —
   the cabin model is visual only; riders are carried by invisible seats while the cabin moves.

## Build an elevator

```
/elevator type create glass glass_cabin           (or edit types.yml; two examples ship)
/elevator type set glass model-open glass_cabin_open
/elevator create lobby glass ground               stand on the cabin floor at the lowest level
/elevator floor add lobby 1                       stand at the next level, repeat per floor
/elevator floor add lobby roof 92                 or give the Y explicitly
/elevator button add lobby b_ground floor:ground &aGround     stand where the button should be (inside the cabin)
/elevator button add lobby b_1 floor:1 &a1
/elevator button add lobby b_roof floor:roof &aRoof
/elevator button add lobby stop stop &cSTOP
/elevator button add lobby call_1 call:1 --floor 1 Call       a landing button fixed at floor 1
/elevator button set lobby b_1 model button_green              visual from models/button_green.bbmodel
/elevator button set lobby b_1 size 0.25x0.25
/elevator info lobby
```

The elevator **origin** is the block you stand on when you `create` it (its centre); the cabin model's
Blockbench (0,0,0) is placed there, so model the cabin with its floor at y=0 and the origin in the
middle. The **cabin volume** (`cabin.width/depth/height` of the type) is the box around the origin whose
players ride along and that is checked for obstructions at every floor.

## Commands

Player (`elevators.use`, default true):

| Command | What |
|---|---|
| `/elevator call [elevator] [floor]` | call the nearest elevator (24 blocks) to the floor you are on, or a named floor |
| `/elevator go <floor>` | choose a floor while standing inside the cabin |
| `/elevator floors [elevator]` | list floors |

Admin (`elevators.admin`, default op; aliases `/elev`, `/lift`):

| Command | What |
|---|---|
| `/elevator type create <id> <model>` · `type set <id> <key> <value>` · `type list` · `type info <id>` · `type remove <id>` | manage types; keys: `model model-open speed acceleration door-time door-close-delay cabin-width cabin-depth cabin-height sound-start sound-move sound-move-interval sound-arrive sound-doors-open sound-doors-close music music-interval volume permission capacity button-cooldown call-cooldown` |
| `/elevator create <id> <type> [firstFloor]` · `remove <id>` · `list` · `info <id>` · `stop <id>` | manage instances (`stop` = emergency stop at the next floor) |
| `/elevator floor add <elev> <name> [y]` · `floor remove <elev> <name>` | floors (the Y is the block surface riders stand on) |
| `/elevator button add <elev> <id> <floor:F\|call:F\|open\|close\|stop> [--floor F] [label…]` · `button set <elev> <id> <key> <value>` · `button move` · `button remove` · `button list` | buttons; keys: `action floor label model space size cooldown permission offset` |
| `/elevator pack` · `/elevator models` · `/elevator reload` | export the pack (async) · list model files · reload config/types/elevators |

Permissions: `elevators.use`, `elevators.admin`, plus whatever you put in a type's `permission`
(e.g. `elevators.type.freight`) or a button's `permission`.

## How riding works

* Choosing a floor (button, `/elevator go`, `/elevator call`) queues it. When the doors have closed
  (`door-close-delay`) the cabin departs; everybody standing inside the cabin volume who is allowed
  to ride (and fits `capacity`) is seated on an invisible marker that moves with the cabin, so nobody
  falls down the shaft. Riders cannot dismount while moving; fall/suffocation damage is cancelled.
* Motion is a trapezoid: accelerate at `acceleration` to `speed`, cruise, decelerate. The cabin is an
  interpolated display entity, so the ride looks smooth on the client.
* On arrival riders are placed back on the floor at the spot they stood, after checking for solid
  ground (`ride.safe-exit-search` blocks down); otherwise at the origin. Doors open for `door-time`.
* **Obstructed destination**: if the cabin volume at the chosen floor contains solid blocks the request
  is refused (or dropped when it comes up in the queue) and the player is told which block.
* **Invalid floors** are rejected with the list of valid ones. Choosing the current floor re-opens the doors.
* **Disconnect** while riding: the player is put down at the nearest floor before they leave.
  **Plugin shutdown / reload**: every cabin parks at its nearest floor, riders are put down, all
  entities are removed; `elevators.yml` stores the resting floor.
* Multiple requests are served in order; `stop` buttons brake to the nearest floor ahead and clear the queue.

## Buttons

Each button is an `Interaction` entity (the clickable box) plus an optional model (`ItemDisplay` using a
`.bbmodel` from `models/`) and an optional floating label (`TextDisplay`, `&` colour codes). `space`
is `cabin` (the button rides with the cabin; offset relative to the cabin floor centre) or a floor name
(a landing button fixed at that level). Right-clicking triggers the action only for players allowed to
use the elevator (and the button's own `permission`), with a per-player per-button cooldown
(`cooldown`, or the type's `button-cooldown`). Entities are never saved to disk: they are rebuilt on load,
removed on unload, and stale ones (after a crash or `/reload`) are swept at start. A click on an entity
whose elevator/button no longer exists removes it and logs a warning.

## Files

* `config.yml` — pack options, ride tuning, button defaults, message prefix.
* `types.yml` — types (example: `glass`, `freight`).
* `elevators.yml` — instances, floors, buttons. Example:

```yaml
elevators:
  lobby:
    type: glass
    world: world
    x: 100.5
    z: -20.5
    yaw: 0.0
    floors: {ground: 64.0, '1': 70.0, roof: 92.0}
    current: ground
    buttons:
      b_1:
        action: floor
        floor: '1'
        offset: {x: 1.2, y: 1.3, z: 0.0}
        space: cabin
        label: '&a1'
        model: button_green
        width: 0.3
        height: 0.3
        cooldown: -1
      call_1:
        action: call
        floor: '1'
        offset: {x: 2.1, y: 1.3, z: 0.0}
        space: '1'
        label: Call
```

* `models/*.bbmodel` — your source models (never modified).
* `pack/` — generated: `Elevators-pack.zip`, `Elevators-pack.sha1`, `pack-report.txt`.

## Model pipeline: what is supported

Supported `.bbmodel` content: Generic / Java block-item / Bedrock / GeckoLib formats; **cube**
elements with per-face UV or box UV (`mirror_uv`, `inflate` honoured); **groups** with pivot and
rotation on any axes (nested groups fine); embedded or sibling-file PNG textures (several textures per
model). Every group becomes an exactly-rotated part, so any angle is rendered exactly.

Not supported — the exporter **fails that model with an error** (others still export):
* **Mesh** elements (cylinders, bevels…) — `pack.mesh-policy: bounding-box` exports each mesh as its
  bounding box instead, with a warning.
* Missing textures (not embedded, not found next to the model) — `pack.missing-texture: warn` exports
  anyway (purple faces).
* Malformed JSON, files without cubes, duplicate model names (case-insensitive), names whose
  sanitised pack path collides.

Ignored with a warning: **animations** (rest pose only), element types other than cube/mesh (locators,
null objects, texture meshes), per-face tint. Client limits: a part's pivot can be at most 80 px
(5 blocks) from the model origin and each group must fit ~48 px around its pivot (bigger groups are
scaled with compensation, costing precision) — keep the cabin centred on (0,0,0) and split large walls
into groups. The report lists every error and warning with the model name.

Generated layout: `assets/<namespace>/models/item/<model>__g<n>.json` (geometry),
`<model>__p<n>.json` (wrapper with the exact transform), `assets/<namespace>/textures/item/<model>/*.png`,
and `assets/minecraft/items/<base-item>.json` selecting by `custom_model_data` string = model name.

## Configuration (`config.yml`)

| Key | Default | Meaning |
|---|---|---|
| `pack.base-item` | turtle_scute | item whose model is replaced per model name |
| `pack.namespace` | elevators | asset namespace |
| `pack.min-format` / `max-format` | 46 / 999 | pack.mcmeta range |
| `pack.mesh-policy` | fail | `fail` or `bounding-box` |
| `pack.missing-texture` | fail | `fail` or `warn` |
| `pack.export-on-start` | true | export when the plugin starts |
| `ride.seat-offset` | 0.0 | tune if riders float/sink while moving |
| `ride.teleport-duration` | 1 | client interpolation ticks |
| `ride.safe-exit-search` | 3 | blocks searched down for ground on exit |
| `ride.obstruction-check` | true | refuse obstructed floors |
| `ride.lock-riders-while-moving` | true | cancel shift-dismount mid-ride |
| `buttons.debounce-ticks` | 3 | ignore the client's duplicate click packet |
| `buttons.default-size` | 0.4 | new buttons' hit box |
| `buttons.label-scale` | 0.5 | label text size |

Invalid values are reported in the console at start and replaced by defaults; invalid types or
elevators are skipped with a message naming the key and the fix.

## Build & test

```
mvn package          # runs the JUnit 5 suite (parser/converter/pack, motion, state machine, types, buttons)
                     # -> target/Elevators-<version>.jar
```

## Limitations / decisions to review

* Rider mechanics use a marker armor stand per rider (`ride.seat-offset` may need tuning on your client
  version so riders stand exactly on the cabin floor). This is the same approach as other vehicle
  plugins in this family but has not been verified on a client for this plugin yet.
* The cabin model has no collision: each floor needs a solid block surface for players to stand on while
  the doors are open. There is no automatic shaft or door block manipulation.
* Door "opening" is a model swap (`model` ↔ `model-open`); there is no door animation (animations are
  not exported).
* Riders are detected by the cabin volume at departure; a player who walks into the cabin while it moves
  is not picked up.
* One items file per base item: if another plugin already overrides the same base item's model, pick a
  different `pack.base-item` or merge the items file by hand.

## Troubleshooting

* **Models show as the base item / purple**: the client did not load the pack (sha1 not updated, URL
  wrong) or the model is in `pack-report.txt` under errors. Check the console after `/elevator pack`.
* **"Floor X is obstructed by stone at …"**: the cabin volume at that floor contains blocks — enlarge
  the shaft or shrink `cabin.width/depth/height` of the type.
* **Riders fall during the ride**: they were outside the cabin volume at departure, or lack
  `elevators.use` / the type permission (they are told so).
* **Button does nothing**: the player lacks permission (message), the cooldown is running (action bar),
  or the button's floor was removed (`/elevator button list <elev>` shows it; fix with `button set … floor`).
* **"update did nothing"**: an old `Elevators-*.jar` is still in `plugins/`; remove it and check the
  console for `Enabling Elevators v<version>`.
