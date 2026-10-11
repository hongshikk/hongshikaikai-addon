# HongShiKaiKai

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.4 - 1.21.11**, providing the
**ElytraFly+**, **Scaffold+**, **BlockIn**, **Sprint+**, **KillAura+**, **CrystalAura+**,
**PistonCrystal**, **AutoAnchor**, **Breaker**, **TpAura+**, **NoFall+**, **FakeLag**, **Derp**,
**HandDerp**, **Phase**, **XCarry**, **PacketMine+** and **PacketEat** modules in the
`hongshikaikai` category, the **ChatCopy**, **DirectionControl**, **SmoothCamera**, **AutoPot** and
**HSKK-HUD** modules in `hskk-tools`, plus a `scaffold-blocks` HUD element and the `hskk` loading
screen.

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
./gradlew build               # 1.21.4 (default) -> build/libs/hongshikaikai-1.4.jar
./gradlew build -Pmc=1.21.8   # -> build/libs/hongshikaikai-1.21.8-1.4.jar
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
`velocity-legacy|modern`, `api-legacy|mid|modern`); `build.gradle.kts` picks the directories from
`-Pmc`. Minecraft 26.1 dropped Yarn mappings, so supporting it is a separate port on its own branch,
not a build flag.

## Modules

| Module | What it does |
|---|---|
| **ElytraFly+** | Elytra assistant: the keys steer the rotation the server is sent while the camera stays free. Takeoff, rocket chain, `lock-view`, level/up/down/diagonal pitch; every synthetic-velocity path is gated by `vanilla-only`. |
| **Scaffold+** | Grim-safe scaffold: aims the pitch only (`rotation=pitch`) and places on the next tick, so the ray the server checks is the ray the client traced. `max-reach` capped at vanilla's 4.5. |
| **BlockIn** | Fills the ring around you - floor, the four sides at both heights and the ceiling - and then turns itself off. Ring order starts at the direction you face and runs clockwise or counter-clockwise, picked at random; `place-order` is `normal`/`random`/`bottom-top`/`top-bottom`. `avoid-broken-blocks` leaves a position you just mined alone for `broken-block-window`, `disable-on-move` stops it the moment you step out of the block you started in, and the block choice takes the hardest block in the hotbar so the wall is the strongest thing you own. Every click is ray traced from the eye with the exact aim that goes into the movement packet and checked against `range` first. |
| **Sprint+** | Presses the sprint key whenever sprinting is legal, with a guard for every Grim `SprintA`-`SprintG` case. Pauses while KillAura+ or Scaffold+ owns the tick. |
| **KillAura+** | One target per tick; the aim is written one tick before the swing and the reach is verified against the real hitbox. Optional `no-wall` line of sight so nothing is hit through a block (off by default). Real-jump crits, optional key-driven `orbit`, large render group. |
| **CrystalAura+** | Every `update-delay` it searches existing crystals to break, cells to place on and base places, costing each candidate explosion against every enemy's predicted position and against your own, and keeping the one that hurts the enemy most inside the self-damage, health-reserve and balance limits. The settings are grouped by topic; `timeout`/`lethal-override`/`armor-breaker` are the damage fallbacks, `interact-in-calc` acts from inside the search, and the target/placement renders plus the damage text are all configurable. |
| **PistonCrystal** | Builds a piston crystal trap on the nearest enemy - piston, redstone, crystal, optional fire - with the four stage pairs deciding which part goes down on which stage of the cycle. `piston-packet` places the piston as a packet, `moving-pause`/`self-ground` gate when it runs, and `inventory-swap` takes the blocks out of the inventory. |
| **AutoAnchor** | Runs the whole respawn-anchor cycle on the nearest enemy - place, charge with glowstone, detonate - choosing the cell by simulating the anchor's power-10 blast against the target's predicted position and your own, inside the self-damage, `no-suicide` and `smart` limits. `spam` cycles without waiting for the charge to be seen and `only-mining` answers a burrow-break by spamming only while the cell is being mined; `assist` places the one obsidian that makes an otherwise unplaceable cell work; `prefer-crystal` stands down while CrystalAura+ has a cell. The settings are grouped by topic, `target-esp` is Box/Fill/None, and the cell render keeps the fade/slide animation. |
| **Breaker** | Feeds the cells around the closest enemy into PacketMine+ - head, burrow, face, down and the surround cells - so it is an anti-burrow tool rather than a general auto-mine. Only hard blocks count (obsidian, ender chest, netherite, crying obsidian, respawn anchor, ancient debris, anvil), `force-double` keeps cycling while a second cell is in flight, and `surround` only breaks a cell a crystal could use. |
| **TpAura+** | Every movement packet is rewritten to a desync position and attacks resolve from there. `Immediate` and `AStar` modes, wireframe render of where the server thinks you are. |
| **NoFall+** | Saves a fall with a real elytra glide instead of an `onGround` claim: deploy plus level pitch, `arrest-dive` for a glide already pointed at the ground, and an optional `in-place` drop that lands where you fell. |
| **FakeLag** | Holds outgoing packets while an enemy is near, then flushes them in one burst. `Constant`/`Dynamic` modes, recoil window, flush-on-hit settings, and the ghost render - `Box`/`Wireframe` ghost at the held position plus the trail of positions the server has not been told about. |
| **Derp** | Turns the body to a silly angle every tick. The body really turns, so the server, the physics and the movement packet all agree - and F5 shows you derping - while the camera is rendered from a view of the module's own, so your first-person look stays where you point it. `yaw-mode` is `Static`/`Offset`/`Random`/`Jitter`/`Spin`, `pitch-mode` is `Static`/`Offset`/`Random`, with `safe-pitch` and `not-during-sprint`. |
| **HandDerp** | Rebuilds the client's options packet with the other main arm and sends it - on the `Delay` timer, or once per arm swing in `Swing` mode. The server broadcasts the flipped arm to everyone else, and the module puts the same flipped arm on your own entity, so F5 shows it too. `silent` leaves your own model on the arm your settings say. |
| **Phase** | Throws an ender pearl at a corrected, steep angle to carry you through the block in front of or below you, then turns itself off. `correct` aims from the neighbouring cell centre, `dynamic-pitch` derives that angle from where the nearest hard block actually is (with `factor`/`only-hard`), and `yaw-step` turns to it over several ticks and throws only once the server's aim is inside `fov`. Scaffolding inside you and crystals, item frames and paintings in your own cell are attacked first. The pearl helpers live in the module's own `Pearl` group (`inventory-swap`, `swing`, `rotation`). |
| **XCarry** | Cancels the `CloseHandledScreen` the client sends when the inventory screen goes away, so the server keeps the handler and the 2x2 crafting grid and armour slots survive. It remembers the last real screen and ignores the chat screen, the game menu and Meteor's client screens. |
| **PacketMine+** | Sends the `START`/`STOP_DESTROY_BLOCK` packets itself and cancels the vanilla interaction, so the break runs on the module's own timer. `double-break` mines a second cell in the same tick with an automatic fastest-tool swap, `instant`/`wait`/`mine-air`/`max-break` decide when a cell may be re-broken, `bypass-ground` claims `onGround` on the movement packets sent near the end of a break, and the `Place` group attacks the surrounding crystals and drops obsidian or a crystal onto the cell as it opens. The aim is a silent look packet, stepped by `yaw-step`/`steps`/`only-looking`, and `no-collide` stops a ghost block from colliding. The break progress box, its animation/easing/colours and the percentage text are all configurable. |
| **PacketEat** | `de-sync` re-sends the start-eat packet every tick with the server's current aim, and `no-release` cancels the `RELEASE_USE_ITEM` packet so the server never sees the meal finish. |
| **AutoPot** | Throws the splash potion that is missing - resistance below level three, speed, strength or slow falling - straight down, out of the hotbar or silently out of the inventory. Each slot's potion type is picked in Meteor's own `Select Potion` screen (`speed-potion`, `resistance-potion`, `strength-potion`, `slow-falling-potion`), so swiftness long, turtle master strong and the rest are distinct choices and only that exact potion is thrown; the slot's effect decides when to throw, the chosen potion decides the level it waits for. Each effect also has its own keybind, and each press throws once. |
| **ChatCopy** | Right click a chat line to copy it; Ctrl + right click copies the whole log (`filter`, `lines`, `seed-from-log`). |
| **DirectionControl** | Arrow keys (by default) turn to fixed yaws 0/90/180/270 at pitch 0, all rebindable. The turn is written to the entity, so it is the turn the server is told about. |
| **SmoothCamera** | The rendered camera trails behind the player with a per-axis filter (`horizontal-factor`, `vertical-factor`), so the view glides over small movements. Render only - the entity, the packets and the server are untouched. Off in first person by default, and the filter restarts on a perspective change. |
| **HSKK-HUD** | Apple-style frosted glass skin for the whole client: every Meteor screen - the ClickGUI, the module list, module settings, the HUD editor - and every HUD element is drawn as a translucent panel with rounded corners, a highlight off the top edge, a one-pixel light border and a soft drop shadow, instead of the flat rectangles the theme draws. On by default. `corner-radius`, `glass-opacity`, `frost`, `tint-strength` and the four colours decide the look, `min-panel-size` keeps separators, scrollbars and the accent bar of an active module crisp, and `screens`/`hud` decide which half of the client it covers. Because frosted glass needs something to blur, it asks Minecraft for the same blur post-effect the vanilla menus use - it does not touch Meteor's `Blur` module - see the note below. |

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
- HandDerp leaves your own main-hand setting alone. The flip is written to your own entity as well,
  because the server does not send your own arm back to you, so third person shows it too; `silent`
  is what keeps your own model on the setting instead, and it is off by default. Nothing here
  changes which hand actually swings or holds the item.
- Grim's `PacketOrder` and `BadPackets` checks are experimental and only run when the server sets
  `experimental-checks: true`.
- CrystalAura+ and PistonCrystal both aim with a silent
  `LookAndOnGround` packet sent immediately before the interaction, because that is the rotation the
  server resolves the placement against - Meteor's own rotation queue would put the interaction ahead
  of the rotation that justifies it. Both also share the same per-tick placed-cell list, so two
  placements cannot be sent into the same cell, and both read their world through the same
  override/terrain-ignore switch (`utils.crystal.CombatUtil.getBlockState`), which is what lets a base
  place be costed before the obsidian exists. The few fixed client-wide values the crystal code needs
  live in `utils.crystal.CrystalConfig`, and air placement is driven by CrystalAura+'s own `air-place`
  setting.
- Phase aims the pearl with a silent `LookAndOnGround` straight before the throw, then the
  `PlayerInteractItem` carries that aim. XCarry's tick handler is `TickEvent.Post`, and it ignores the
  chat screen, the game menu and Meteor's whole client GUI family when it remembers the last real
  screen.
- PacketMine+ writes its aim as a silent `LookAndOnGround` just before the break interaction, sends
  its break packets through the same sequenced packet helper the crystal modules use, and writes the
  `onGround` claim through a mixin accessor of its own; `no-collide` is a second mixin that empties
  the collision shape of the ghost cell. The modules it consults are Meteor's Blink and AnchorAura,
  the addon's CrystalAura+ (for `wait-place`, and the crystal it places with) and Meteor's Criticals
  for the `smart` mining penalty. CrystalAura+ reads PacketMine+'s cell and progress for its own
  `ignore-mine` and `anti-surround` logic, falling back to Meteor's PacketMine when PacketMine+ is not
  the one running. PacketEat's tick handler is `TickEvent.Pre`.
- AutoAnchor runs its cell search and render on the module's own tick (the aim is a silent look
  packet, stepped inline by `RotationHelper`), and `prefer-crystal` gates the CrystalAura+ stand-down
  and defaults to on. It keeps its own map of the server's `BlockBreakingProgress` packets for
  `only-mining`/`detect-mining`, and its target ESP offers Box/Fill/None. Breaker drives PacketMine+
  and drops the `CevPause`/`AntiCrawl` guards, whose modules are not part of this addon; its "is a
  pickaxe held" test uses the vanilla `pickaxes` item tag, because `PickaxeItem` stops existing at
  1.21.5. AutoPot runs on `TickEvent.Pre` and hands the aim back with `RotationHelper` after the
  throw. Its four potion settings are Meteor `PotionSetting`s, so the `Select Potion` screen comes
  from Meteor itself; an inventory stack counts only when its potion contents - effect, amplifier and
  duration - match the chosen type, and the effect the slot keeps up is what the throw condition
  reads its required amplifier from.
- HSKK-HUD draws into the same renderer and the same batch as the widget that asked for a panel, so
  it follows Meteor's scissor boxes (a half-scrolled settings page still clips its panels) and costs
  no extra draw call. The corners are real geometry - three bands and four triangle fans per panel -
  rather than a texture, because Meteor's texture atlas is packed once at startup and a corner radius
  cannot be baked into it and stay a live setting. The blur is Minecraft's own: while a Meteor screen
  is open, HSKK-HUD calls the same `GameRenderer.renderBlur` the vanilla menus use, from
  `RenderAfterWorldEvent` - the tail of the world render, before the HUD and before the screen - so
  the world is blurred and the HUD and the panels stay sharp on top of it. Meteor's `Blur` module is
  never read, switched on or switched off by this module, and the two can run side by side; what
  HSKK-HUD does honour is the vanilla `Menu Background Blurriness` video setting, so a player who set
  it to 0 keeps a sharp world and still gets the translucent glass. `blur` off does the same without
  touching that option. Neither the drawing nor the blur needs a Minecraft or Meteor version check,
  which is why it is one implementation for all seven Minecraft versions. On its first launch
  HSKK-HUD adds itself to the saved config so that switching it off keeps it off - the
  `internal-default-on` flag is the whole mechanism.
- If something still flags, check the old jar is not still in `mods/` and that Meteor's own
  ElytraFly/Scaffold is off, then read the module's `debug` chat output - NoFall+ in particular names
  every reason a press was skipped.
