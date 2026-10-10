# HongShiKaiKai

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.4 - 1.21.11**, providing the
**ElytraFly+**, **Scaffold+**, **BlockIn**, **Sprint+**, **KillAura+**, **TpAura+**, **NoFall+**,
**FakeLag**, **Derp** and **HandDerp** modules in the `hongshikaikai` category, the **ChatCopy**,
**DirectionControl** and **SmoothCamera** modules in `hskk-tools`, plus a `scaffold-blocks` HUD
element and the `hskk` loading screen.

Every module follows one rule: **whatever the server is told, the client must actually have done** -
real rotations, real key presses, real packets - so Grim's movement, reach and simulation checks see
the same client the player does.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.10 or 1.21.11 (no 1.21.9) |
| Fabric Loader | pinned per version by the build script (0.16.10; 0.19.3 from 1.21.10) |
| Meteor Client | the build for the same Minecraft version (1.21.8 -> `1.21.8-SNAPSHOT`) |
| Java | 21+ |

## Build

```bash
./gradlew build               # 1.21.4 (default) -> build/libs/hongshikaikai-0.1.0.jar
./gradlew build -Pmc=1.21.8   # -> build/libs/hongshikaikai-1.21.8-0.1.0.jar
./build-all.sh                # every supported version
```

A jar only works next to the Meteor Client build for **the same** Minecraft version. For a
development client, use the `Minecraft Client` run configuration (it honours `-Pmc`, 1.21.4 by
default).

### Supported versions

| Minecraft | Yarn | Loader | Loom the Meteor jar demands |
|---|---|---|---|
| 1.21.4 | 1.21.4+build.1 | 0.16.10 | 1.9.2 |
| 1.21.5 | 1.21.5+build.1 | 0.16.10 | 1.10.5 |
| 1.21.6 | 1.21.6+build.1 | 0.16.10 | 1.10.5 |
| 1.21.7 | 1.21.7+build.8 | 0.16.10 | 1.10.5 |
| 1.21.8 | 1.21.8+build.1 | 0.16.10 | 1.10.5 |
| 1.21.10 | 1.21.10+build.3 | 0.19.3 | 1.12.7 |
| 1.21.11 | 1.21.11+build.6 | 0.19.3 | 1.14.10 |

Toolchain: Gradle 9.6.1 + Loom 1.17.21. Version-specific code lives in
`src/families/<dimension>-<era>/java` (`splash-legacy|modern`, `compat-legacy|modern`,
`velocity-legacy|modern`); `build.gradle.kts` picks the directories from `-Pmc`. Minecraft 26.1
dropped Yarn mappings, so supporting it is a separate port on its own branch, not a build flag.

## Modules

| Module | What it does |
|---|---|
| **ElytraFly+** | Elytra assistant: the keys steer the rotation the server is sent while the camera stays free. Takeoff, rocket chain, `lock-view`, level/up/down/diagonal pitch; every synthetic-velocity path is gated by `vanilla-only`. |
| **Scaffold+** | Grim-safe scaffold: aims the pitch only (`rotation=pitch`) and places on the next tick, so the ray the server checks is the ray the client traced. `max-reach` capped at vanilla's 4.5. |
| **BlockIn** | Port of LiquidBounce's BlockIn: fills the ring around you - floor, the four sides at both heights and the ceiling - and then turns itself off. Ring order starts at the direction you face and runs clockwise or counter-clockwise, picked at random; `place-order` is `normal`/`random`/`bottom-top`/`top-bottom`. `avoid-broken-blocks` leaves a position you just mined alone for `broken-block-window`, `disable-on-move` stops it the moment you step out of the block you started in, and the block choice takes the hardest block in the hotbar so the wall is the strongest thing you own. Every click is ray traced from the eye with the exact aim that goes into the movement packet and checked against `range` first. |
| **Sprint+** | Presses the sprint key whenever sprinting is legal, with a guard for every Grim `SprintA`-`SprintG` case. Pauses while KillAura+ or Scaffold+ owns the tick. |
| **KillAura+** | One target per tick; the aim is written one tick before the swing and the reach is verified against the real hitbox. Optional `no-wall` line of sight so nothing is hit through a block (off by default). Real-jump crits, optional key-driven `orbit`, large render group. |
| **TpAura+** | Port of LiquidBounce's TpAura: every movement packet is rewritten to a desync position and attacks resolve from there. `Immediate` and `AStar` modes, wireframe render of where the server thinks you are. |
| **NoFall+** | Saves a fall with a real elytra glide instead of an `onGround` claim: deploy plus level pitch, `arrest-dive` for a glide already pointed at the ground, and an optional `in-place` drop that lands where you fell. |
| **FakeLag** | Port of LiquidBounce's FakeLag: holds outgoing packets while an enemy is near, then flushes them in one burst. `Constant`/`Dynamic` modes, recoil window, flush-on-hit settings, and the LiquidBounce render - `Box`/`Wireframe` ghost at the held position plus the trail of positions the server has not been told about. |
| **Derp** | Port of LiquidBounce's Derp: turns the body to a silly angle every tick. The body really turns, so the server, the physics and the movement packet all agree - and F5 shows you derping - while the camera is rendered from a view of the module's own, so your first-person look stays where you point it. `yaw-mode` is `Static`/`Offset`/`Random`/`Jitter`/`Spin`, `pitch-mode` is `Static`/`Offset`/`Random`, with `safe-pitch` and `not-during-sprint`. |
| **HandDerp** | Port of LiquidBounce's HandDerp: rebuilds the client's options packet with the other main arm and sends it - on the `Delay` timer, or once per arm swing in `Swing` mode. The arm you see yourself with does not change; the arm everyone else sees does. `silent` keeps the server's broadcast from flipping your own model back. |
| **ChatCopy** | Right click a chat line to copy it; Ctrl + right click copies the whole log (`filter`, `lines`, `seed-from-log`). |
| **DirectionControl** | Arrow keys (by default) turn to fixed yaws 0/90/180/270 at pitch 0, all rebindable. The turn is written to the entity, so it is the turn the server is told about. |
| **SmoothCamera** | Port of LiquidBounce's SmoothCamera: the rendered camera trails behind the player with a per-axis filter (`horizontal-factor`, `vertical-factor`), so the view glides over small movements. Render only - the entity, the packets and the server are untouched. Off in first person by default, and the filter restarts on a perspective change. |

The `scaffold-blocks` HUD element shows how many blocks Scaffold+ could place; it is not in the
default layout, so add it once in the Meteor HUD editor. The `hskk` loading screen replaces Mojang's
wordmark on the resource-reload splash.

### Notes

- ElytraFly+ and NoFall+ do not pause each other: the falls nobody is gliding through are NoFall+'s
  business, and NoFall+'s `arrest-dive` covers the dive that ElytraFly+ `lock-view` with sneak flies.
- Keep Scaffold+ on `rotation=pitch`, do not run Meteor's own `Scaffold` or a second rotation queue
  alongside it, and turn towards the gap when it stops placing.
- BlockIn aims the full yaw and pitch, so unlike Scaffold+ it is a module to stand still with:
  Grim's walking prediction reads the yaw of the movement packet, which makes a spoofed yaw a
  `Simulation` offset while a movement key is held. That is what `disable-on-move` is for. It also
  pauses while a screen is open, so an inventory does not block it in behind a menu.
- Sprint+ pauses while KillAura+ or Scaffold+ is active, because both drive the tick a sprint would
  shift; KillAura+ itself never stands down.
- Derp turns the body for real and splits the camera off from it, the way ElytraFly+ and KillAura+ do:
  your first-person view and your own crosshair keep the direction you point them in, F5 shows the
  body derping, and the server is sent a rotation the physics actually turned with. The cost is that
  the keys are read against the derped body, so holding forward walks wherever the derp points;
  `not-during-sprint` hands the body back while sprinting. Derp is the weakest view lock there is:
  KillAura+, ElytraFly+ and NoFall+ all take the body off it when they need it.
- HandDerp leaves your own main-hand setting alone. `silent` is what keeps your own model on that
  setting; with it off the server's broadcast flips your model too, which is what the module looks
  like by default. Nothing here changes which hand actually swings or holds the item.
- Grim's `PacketOrder` and `BadPackets` checks are experimental and only run when the server sets
  `experimental-checks: true`.
- If something still flags, check the old jar is not still in `mods/` and that Meteor's own
  ElytraFly/Scaffold is off, then read the module's `debug` chat output - NoFall+ in particular names
  every reason a press was skipped.
