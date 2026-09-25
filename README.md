# HongShiKaiKai

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.4 - 1.21.11**, providing the
**ElytraFly+**, **Scaffold+**, **Sprint+** and **KillAura+** modules, plus a `scaffold-blocks` HUD
element and the `hskk` loading screen.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.10 or 1.21.11 |
| Fabric Loader | pinned per version by the build script (0.16.10, 0.19.3 from 1.21.10) |
| Meteor Client | the build for the same Minecraft version (1.21.8 -> `1.21.8-SNAPSHOT`) |
| Java | 21+ |

## Build

```bash
./gradlew build               # 1.21.4 (default) -> build/libs/hongshikaikai-0.1.0.jar
./gradlew build -Pmc=1.21.8   # -> build/libs/hongshikaikai-1.21.8-0.1.0.jar
./build-all.sh                # every supported version
```

Without `-Pmc` the build is what it always was: 1.21.4, no suffix in the name. A jar only works
next to the Meteor Client build for **the same** Minecraft version.

To run a development client, use the `Minecraft Client` run configuration in your IDE (it uses
whatever `-Pmc` you pass, 1.21.4 by default).

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

The toolchain is Gradle 9.6.1 + Loom 1.17.21, the pair Meteor's own main branch uses. Loom refuses
to load a mod built by a newer Loom than itself, and every Meteor jar records the Loom that built
it in its manifest, so the project's Loom has to be at least the largest value in that column.

### Where the per-version differences live

`src/main/java` holds everything that is version independent. Everything that is not lives in
`src/families/<dimension>-<era>/java`, and `build.gradle.kts` picks the directories from `-Pmc`.
Two dimensions exist because the two boundaries do not fall on the same version:

| Directory | Applies to | Difference |
|---|---|---|
| `splash-legacy` | 1.21.5 and older | `SplashOverlay` calls `drawTexture(Function<Identifier, RenderLayer>, ...)`; the 2D stack is a `MatrixStack` |
| `splash-modern` | 1.21.6 and newer | the same call takes a `RenderPipeline`; the 2D stack is a `Matrix3x2fStack` |
| `compat-legacy` | 1.21.4 | `Entity.prevYaw/prevPitch` and `PlayerInventory.selectedSlot` are public fields |
| `compat-modern` | 1.21.5 and newer | those became `lastYaw/lastPitch`, and a private field behind `getSelectedSlot()/setSelectedSlot()` |

Smaller differences are handled by writing code that holds on every version rather than by adding a
dimension: `KillAuraPlus` reads an entity's position through `getX()/getY()/getZ()`, because 1.21.10
renamed `getPos()` to `getEntityPos()` while both still read the same field.

Every name in those tables was read off the named jar Loom produces for each version with `javap`,
not guessed.

### 26.1 and later is a port, not a flag

Minecraft stopped obfuscating with 26.1, and Fabric stopped maintaining Yarn with it (see
[Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings/)). A 26.x jar therefore
cannot come out of this build script by adding a line: the source has to move to Mojang's names
(`MinecraftClient` -> `Minecraft`, `Box` -> `AABB`, `getYaw` -> `getYRot`, `changeLookDirection` ->
`turn`, `Camera.update` -> `alignWithEntity`, and so on), and the toolchain moves to JDK 25 with
Loom 1.17+. That is a separate port on its own branch.


## Module: ElytraFly+

An elytra assistant built around one rule: **whatever the server is told, the client must actually
have done.** While gliding, the keys steer the rotation that goes to the server, and the camera
stays free; nothing is ever spoofed to just one side.

### General

| Setting | Default | Description |
|---|---|---|
| `lock-view` | on | Keys steer the server-side rotation while gliding. The camera stays free. |
| `level-pitch` | 0° | Flight pitch when only a direction key is held. 0 is level flight. |
| `up-pitch` | -70° | Flight pitch when space is held alone. -90 stalls; vanilla elytra climbs best near -60..-85. |
| `diagonal-pitch` | -45° | Flight pitch when space and a direction are held together. |
| `down-pitch` | 45° | Flight pitch when sneak (shift) is held, with or without a direction. Positive looks down, so the elytra dives. Space and sneak together cancel out to `level-pitch`. |
| `rotation-step` | 0° | Max degrees per tick the locked rotation may move toward its target. 0 turns instantly. |
| `restore-view` | on | Hand the camera view back to the body when the lock ends, so the view does not jump. |
| `fake-firework` | off | Skip consuming rockets and synthesise the boost locally. Non-vanilla, flags Grim Simulation. |
| `firework-level` | 1 | Rocket level used for boost strength and duration. |
| `boost-cooldown` | 1.0s | Delay between boosts. |
| `hold-to-boost` | on | Hold a trigger key to keep re-firing on the cooldown. |
| `boost-trigger` | both | Which keys count as the trigger: `jump`, `direction` (WASD), or `both`. |
| `vanilla-only` | on | Master kill switch for every synthetic-velocity path. |

### Movement (all non-vanilla, all gated by `vanilla-only`)

| Setting | Default | Description |
|---|---|---|
| `horizontal-move` | off | WASD horizontal velocity injection. |
| `horizontal-speed` | 0.08 | Speed added per tick. |
| `diagonal-move` | off | Normalise diagonal input so W+A is not faster than W. |
| `vertical-move` | off | Jump ascends, sneak descends, as an injected velocity. Use `lock-view` instead: space climbs and sneak dives with real rotation. |
| `vertical-speed` | 0.5 | Vertical speed added per tick. |

### Takeoff

| Setting | Default | Description |
|---|---|---|
| `auto-takeoff` | off | Swap a hotbar elytra into the chest slot when jump is pressed in the air. |
| `swap-elytra` | on | Allow the hotbar-to-chest swap. |
| `restore-chest` | off | Swap the original chest item back when flight ends. |

### Steering and the rocket chain

While `lock-view` is on, the keys choose where the body points, measured against the current camera
view:

| Keys | Flight direction |
|---|---|
| space | forward and up, at `up-pitch` |
| sneak (shift) | down, at `down-pitch` |
| W / A / S / D | the view's forward / left / right / back, at `level-pitch` |
| space + a direction | diagonally up in that direction, at `diagonal-pitch` |
| sneak + a direction | down in that direction, at `down-pitch` |
| space + sneak | the two cancel out to level flight |
| nothing | the last rotation is held |

Sneak is the same trick as space: the body really does point down, so the elytra physics dive and
the server predicts the same dive - no velocity is injected. A dive also trades height for speed
exactly like a vanilla elytra does, so it doubles as a way to get fast.

`boost-trigger` decides what keeps the firework chain alive. It defaults to `both`, so holding WASD
to steer re-fires rockets on the cooldown exactly like holding jump does. Set it to `jump` for the
vanilla habit, or to `direction` if you want the chain driven purely by the steering keys.

### Why the camera is separate from the rotation that is sent

A movement packet carries the player's rotation, and the server predicts velocity with it. The
client's own elytra physics read the player's rotation too. If the packet carried the flight
rotation while the body kept the mouse rotation, the client would fly one rotation and the server
would predict another - the mismatch is exactly what a Grim Simulation check reports.

So the module writes the flight rotation to the player entity once per tick, from `TickEvent.Pre`,
which runs before the player tick computes movement and before movement packets are built. Physics,
packet and server prediction all use that one value. The camera is rendered from a separate view by
`CameraMixin`, and mouse deltas are routed to that view by `EntityMixin` instead of the entity.
Rendering is local and is never sent anywhere, so the view the player sees and the rotation the
server sees are allowed to differ without any desync on the wire.

When the lock ends - the module is turned off, or the flight does - the view is written back to the
body so the camera carries on where the player was looking. That write has to move
`prevYaw`/`prevPitch` as well, because the camera does not render the plain rotation: `Camera.update`
calls `Entity.getYaw(tickDelta)` and `getPitch(tickDelta)`, which lerp from the previous values.
Those still hold the flight rotation the module has been writing all tick, so without the extra two
assignments the first tick after the lock renders at the *flight* pitch and only then slides to the
view - turning the module off mid-dive snaps the camera down for a tick.

### Why the hotbar swap waits a tick

Firing a rocket needs the rocket selected, and it has to be selected *server side*, because the
server resolves the use-item packet against the slot it thinks you hold. So the module sends
`UpdateSelectedSlot`, uses the rocket, and then has to put the slot back.

Putting it back immediately is what trips Grim's `PacketOrderE`: the server keeps a per-tick
"right clicking" flag, and an `UpdateSelectedSlot` that arrives in the same tick as the use-item
packet looks like a client that used an item and then hid the swap. So the packet is held until the
head of the next tick, where the tick's own movement packet has already cleared that flag - the
same place a vanilla hotbar key press sits.

This one matters more than a flag: `PacketOrderE` calls `setbackIfAboveSetbackVL()` when the tick
was a **use**, and a rocket *is* a use. Swapping back in the same tick therefore armed a setback
alongside every alert, which is the rubber-band that shows up after roughly twenty-five boosts.

Only the packet is delayed. The client-side selection is handed back to the HUD the moment the
rocket is used, so the hotbar does not flicker for a tick.

### Why the yaw is never normalised

Vanilla's `Entity.setYaw` stores whatever it is handed - only `setPitch` wraps, and it does so with
`pitch % 360` before clamping. A vanilla client that keeps turning therefore sends 179, 181, 183 and
so on, and never snaps back.

Grim's `AimModulo360` exists to catch clients that do snap back: it flags a yaw delta over 320
degrees that follows a normal one, while the yaw is still inside ±360. Wrapping the flight yaw into
-180..180 - which `MathHelper.wrapDegrees` invites - is exactly that signature, and the wrap lands
the *same direction* on the wire, so nothing else complains. The module writes the raw accumulated
yaw instead, and hands it back raw when the lock ends.

### Why auto-takeoff needs a fresh jump press

`auto-takeoff` only fires when the jump key goes from up to down in that tick, not while it is
merely held.

The client sends `PlayerInput` **only when the input changes**, so a key that is still held leaves
Grim's `knownInput.jump()` true from the previous tick. `ElytraB` reads exactly that when the
`START_FALL_FLYING` command arrives and calls it "started gliding without releasing jump" - a flag
that also sets back. Requiring the press edge puts the command in the window ElytraB expects, where
the old input had jump released and the new one has it pressed.

### Why one flag turns into constant rubber-banding

When Grim sets back, it stores the setback and re-sends it until the client "accepts" it. Its
`MovementCheckRunner` has this:

```java
if (requiredSetBack != null && requiredSetBack.getTicksComplete() == 1) {
    ...
    if (!player.predictedVelocity.isKnockback() && requiredSetBack.getVelocity() != null) {
        // And then send it again!
        player.getSetbackTeleportUtil().executeForceResync();
    }
}
```

The re-send happens whenever the player's predicted velocity is not the setback's velocity. While
gliding, the elytra physics rewrites the velocity every tick from the look vector, so the setback
velocity never survives - and the position packet goes out again, every tick, with no new alert.
That is the "constantly rubber-banded while flying" symptom: it is one bad flag followed by a
resend loop, not a repeating flag.

It also means the way out of a loop is to stop gliding for a tick or two.

### If you still get flagged

Meteor persists module settings, and removed keys such as `lock-pitch` / `only-look-in-server` /
`server-pitch` are simply ignored by the new code (unknown settings are dropped on load). Make sure
the old jar is not still sitting in `mods/`, and check that Meteor's own `ElytraFly` module is not
enabled alongside this one.

Note that Grim's `PacketOrder` and `BadPackets` checks are experimental and only run when
`experimental-checks: true` is set in the server's `config.yml`; they will still be enabled on a
server that has that on.


## Module: Scaffold+

A scaffold built around the same rule as ElytraFly+: **the ray the server checks must be the ray
the client actually used.** It places blocks the way a vanilla right click does, so GrimAC's
placement checks have nothing to report.

### The one-tick rule

A block placement packet carries no rotation. The server validates the click against the rotation
and position of the **last movement packet it received**. In a vanilla tick the order on the wire is

```
[interact] ... [look]        handleInputEvents() runs before ClientWorld.tickEntities()
```

so when the server handles the interact the player is still at the previous tick's position,
looking in the previous tick's direction. Grim reproduces exactly that: `RotationPlace` ray traces
from `player.x/y/z` with `player.yaw/pitch`.

Scaffold+ therefore never aims and places in the same tick:

1. While `ClientPlayerEntity.sendMovementPackets` is building this tick's packet, the entity has
   already moved, so its position and eye height are the ones about to be serialised. The module
   traces a ray from the eye, resolves the placement and queues that rotation
   (`SendMovementPacketsEvent.Pre`, highest priority so Meteor's `Rotations` picks it up in the
   same tick).
2. At the head of the **next** client tick - before `handleInputEvents`, the same slot a vanilla
   right click occupies - it sends the interact for the placement resolved in step 1.

At that moment the server's copy of the player is positionally and rotationally identical to the
copy the aim was computed from, so the ray the server traces is the ray the module traced. The
placement packet also still goes out *before* this tick's movement packet, which is what Grim's
`Post` check wants.

### Settings

| Setting | Default | Description |
|---|---|---|
| `blocks` / `blocks-filter` | empty, blacklist | Which blocks may be used. |
| `only-on-click` | off | Only work while the use key is held. |
| `auto-switch` | on | Switch to a block in the hotbar before placing. The offhand is used first when it holds one. |
| `swap-back` | off | Off: the module switches to whichever hotbar slot holds the blocks and **leaves it there**, so the block really is in your hand and you change back yourself, like pressing a hotbar key. On: the original slot is given back after every placement. |
| `swing` | on | Render the client-side swing. Off: only the swing packet is sent. |
| `place-delay` | 0 ticks | Ticks between placements. 0 is one placement per tick, which is all vanilla allows anyway. |
| `pause-in-vehicle` | on | Do nothing while riding an entity. |
| `chat-feedback` | off | Print a line every time a block is placed. |
| `rotation` | pitch | `pitch` aims only the pitch and leaves the player's yaw alone (Grim-safe); `full` aims the yaw as well, which flags `Simulation` while a movement key is held. |
| `max-reach` | 4.5 | How far from the eye a placement may be. 4.5 is vanilla's block interaction range; the setting cannot be raised past it. |
| `ahead-distance` | 0 | Blocks to push the target forward along the held WASD keys. |
| `search-radius` | 2 | How far around the spot under the player the fallback search looks. |
| `render` | on | Master switch for the render group. |
| `shape-mode` | both | How the shapes are drawn: `both`, `sides`, `lines`. |
| `target` | on | Highlight the block the next placement will fill, while it is being aimed at. |
| `face` | on | Also highlight the face of the neighbour that will be clicked. |
| `side-color` / `line-color` | purple | Fill and outline of the target block and the clicked face. |
| `pulse` | on | Breathe the target: it pulls in and dims, then pushes out and brightens. |
| `pulse-speed` | 0.8/s | Pulse cycles per second. |
| `glow` | on | A wider, fainter outline around the target that breathes with the pulse. |
| `placed` | on | Fade out the block that was actually placed, shrinking as it goes. |
| `placed-duration` | 8 ticks | How long the placed block stays highlighted. |
| `placed-side-color` / `placed-line-color` | green | Fill and outline of a placed block. |

The target box is drawn from the placement that is already armed, not from a fresh search, so it is
exactly the block the next interact will fill. Nothing is drawn when there is no reachable spot -
an empty screen means the module has nowhere to put a block, which is the moment to turn towards
the gap.

### HUD element: `scaffold-blocks`

A HUD element that shows how many blocks are left, so you can see a bridge running out before you
walk off it. It is not in the default layout, so it has to be added once: open the Meteor GUI
(Right Shift), go to the **HUD** tab, press **Edit** to open the HUD editor, then **right-click empty
space** and pick **Scaffold Blocks** from the `hongshikaikai` section. Left-click an element in the
editor toggles it on and off; right-click one to open its settings.

The count uses the module's own `blocks` / `blocks-filter`, so it is what Scaffold+ could actually
place - not every block-shaped item you happen to carry. Only the hotbar is counted, because that
is all the module can reach; the number reaching zero is the moment it stops placing.

Because of `hide-when-inactive` there is normally nothing to see while Scaffold+ is off, so the
element is deliberately drawn **in the HUD editor whatever the module is doing**. A hidden element
collapses to a `0x0` box, and a `0x0` box cannot be found or dragged - adding it and then hunting for
it in the editor looked exactly like the element not working. In game it still disappears when the
module is off, unless `hide-when-inactive` is turned off.

Two switches have to be on before any of this is visible in game, and both are Meteor's rather than
this element's: the HUD itself must be **active** (the `active` checkbox at the bottom of the HUD
tab, or the `bind` key in the same tab if one is set), and the element has to have been **added** -
being present in the Add list is not the same as being on the HUD.

| Setting | Default | Description |
|---|---|---|
| `show-total` | off | Break the number down as `hotbar / total`. |
| `low-blocks` | 16 | Colour the count as a warning at or below this many hotbar blocks. 0 disables it. |
| `hide-when-inactive` | on | Hide the element in game while Scaffold+ is off. The HUD editor always shows it. |
| `text-color` / `low-color` / `background-color` | | Colours of the count, the warning state and the background. |

### Why the yaw is never aimed

This is the part that decides whether Grim's `Simulation` check stays quiet, and it is not about
velocity at all.

Grim predicts the player's horizontal movement with

```java
getMovementResultFromInput(player, input, speed, player.yaw)
```

— the WASD input rotated by the **yaw of the movement packet**. The client's own physics ran
earlier in the same tick, with the yaw the mouse gave it. So if the packet carries a yaw the client
did not walk with, the client walks one way and the server predicts another; the offset shows up as
soon as a movement key is held, and it is reported as `Simulation`.

The **pitch is never read by that prediction**. Reading the whole prediction engine, `player.pitch`
is only used by `PredictionEngineWater` (swimming), `PredictionEngineElytra` (gliding) and the
firework uncertainty box — all states the module simply steps aside for.

So `rotation` defaults to `pitch`: the module aims only the pitch and leaves the yaw exactly as the
player's own, which means the ray the server traces is still the ray the module traced, while the
walk stays in sync. `full` is the old behaviour (aim the yaw too) and is kept for servers that do
not run Grim — it does flag `Simulation`.

Sub-degree pitch corrections are held rather than applied, so a pitch change is either absent or a
whole degree or more. That is deliberate: Grim's `Baritone` check is built around "yaw delta of
exactly zero plus a pitch delta under one degree".

### Why the rotation queue is kept fed

Meteor's `Rotations` re-sends the **last queued rotation** for `rotation-hold` ticks (four by
default) whenever nothing is queued. That held rotation is whatever the module last aimed with - a
yaw the client is no longer walking with the moment you turn a corner. The packet would then carry
one yaw and the client would have walked with another, which is the same `Simulation` offset the
pitch-only mode exists to avoid, just reintroduced through Meteor's queue.

So the module queues the player's **own** rotation on every tick it is not aiming. That changes
nothing on the wire - the client rotation is set to the value it already had, so vanilla sends no
look packet unless the mouse moved - but the queue never runs dry, so nothing stale is ever
replayed. With that, the yaw in the movement packet is always the yaw the client actually walked
with.

### Why the placement swap waits a tick

By default `swap-back` is off, so switching to the block is a plain, visible hotbar switch: one
`UpdateSelectedSlot` before the placement, and nothing after. That is exactly what pressing a
hotbar key does, and after the first switch the module is already holding the block, so later
placements send no slot packets at all.

With `swap-back` on it has to put the slot back as well, and putting it back immediately trips
Grim's `PacketOrderE`: the server keeps a per-tick "right clicking" flag, and an
`UpdateSelectedSlot` that arrives in the same tick as the placement looks like a client that placed
a block and then hid the swap. So in that mode the packet is held until the head of the next tick,
where the tick's own movement packet has already cleared that flag - the same place a vanilla
hotbar key press sits.

Only the packet is delayed. The client-side selection is handed back to the HUD the moment the
block is placed, so the hotbar does not flicker for a tick. ElytraFly+'s rocket swap works the same
way, for the same reason - and there it also disarms `PacketOrderE`'s setback, since a rocket is a
use.

With the yaw left alone, the ray sweeps a vertical plane instead of pointing at the block, so the
pitch cannot simply be aimed at the face centre — it has to be **solved for**. For each clickable
face the module intersects the ray's horizontal path with that face and derives the pitch from the
crossing point, sampling a few points just inside the reachable stretch. That is what lets it still
place when you are walking at up to roughly 20° off the gap; a naive "aim at the block" pitch runs
out at about 10°. Beyond that the ray simply clips the block you are standing on, which no pitch
can fix — turn towards the gap and it places again.

### Why every placement is a real click

The hit comes from `World.raycast` from the player's own eye along exactly the `(yaw, pitch)` that
will be sent, and it is accepted only when the ray ends up filling the intended block. The rest
follows from that:

| Grim check | Why it passes |
|---|---|
| `RotationPlace` | the ray is traced from the same eye position and the same rotation the server holds. |
| `PositionPlace` | the clicked face is the first hit of a real ray trace, so it is genuinely unoccluded. |
| `FabricatedPlace`, `InvalidPlaceA`/`B` | the cursor is a real intersection point on the face, inside the block and within a hair of it, and the face id comes from the trace. |
| `FarPlace` | the cursor is a point on the clicked box, so the box distance can only be shorter; anything past `max-reach` minus a sliver is dropped. |
| `AirLiquidPlace` | supports are required to be non-air, non-liquid, non-replaceable and non-interactive. |
| `MultiPlace` | one `interactBlock` per tick at most, and nothing manual is ever sent. |
| `Post` | the interact goes out before this tick's movement packet, exactly like a vanilla right click. |
| `BadPacketsH` | the 1.19+ sequence id and the swing come from `ClientPlayerInteractionManager.interactBlock`, not from hand-built packets. |
| `Simulation` | the walk prediction is fed the yaw the client actually walked with, and no velocity is ever written. |
| `Baritone` | the pitch either does not change or changes by a degree or more. |

If another module claims the rotation for the same tick (Meteor's `Rotations` is a shared queue),
the rotation that actually went out will not match the aim; the module notices and drops the
placement instead of flagging.

### If you still get flagged

- Do not run Meteor's own `Scaffold` at the same time; it aims and places in the same tick.
- Do not run a kill aura or anything else that queues rotations at a higher priority.
- Keep `rotation` on `pitch`. On `full` the `Simulation` offset is expected, not a bug.
- `pitch` aiming only reaches blocks roughly in front of you. If the module stops placing, turn
  towards the gap — the yaw has to stay yours for the walk prediction to line up.
- Do not bridge while you are actually in water or gliding; the module pauses there on purpose,
  because those predictions do read the pitch.
- Lower `max-reach` on servers that shrink the block interaction range.
- Raise `place-delay` to 1 if you want to be slower than one placement per tick.


## Module: Sprint+

Presses the sprint key for you whenever sprinting is legal, so W never has to be tapped twice.

### It only presses the key

The module writes `sprintKey.setPressed` and nothing else. Vanilla then decides whether that becomes
an actual sprint, and reports it the way it always does - the sprint flag in the `PlayerInput`
packet and the start/stop sprinting action. Client physics and server prediction therefore agree on
the sprint state, and the packets are the ones a player holding the key would have sent.

Driving `player.setSprinting(true)` directly, which is the obvious way to write this, flips the
entity flag while the reported input still says the key is up. That is a disagreement between what
the client did and what it told the server, which is the shape of thing Grim's simulation looks for.

The module also only claims the key if it is not already down: if you are holding sprint yourself, it
leaves it alone and never lifts it on your behalf.

### Guards

Grim checks the sprint state against a list of situations, and most of them cancel the movement
packet. Every guard is on by default:

| Guard | Grim check | Why |
|---|---|---|
| `hunger-guard` | `SprintA` | Food below 7. This one carries `setback = 0`, an immediate rubber-band, so it is not a cosmetic guard. Skipped when flying is allowed, matching Grim. |
| `use-guard` | `SprintC` | Using an item. |
| `sneak-guard` | `SprintB` | Sneaking or crawling. |
| `blind-guard` | `SprintD` | Blindness. |
| `wall-guard` | `SprintE` | Pushed against a wall. |
| `water-guard` | `SprintG` | In water without actually swimming. |
| `glide-guard` | `SprintF` | Gliding. |

Vanilla refuses most of these on its own, which is why the guards are a belt as well as braces: they
make the module's intent checkable against Grim's list instead of relying on the client's rules
happening to line up.

### Pausing for the other modules

KillAura+ and Scaffold+ each drive the tick for their own reasons - the aura aims and attacks with the
rotation the movement packet carries, and the scaffold places with the movement prediction that packet
implies. A sprint changes both, so rather than race them for the same tick the module stops claiming
the key while either is active. The module stays on; it just keeps its hands off the key until the
other one is off.

| Setting | Default | Description |
|---|---|---|
| `pause-on-killaura` | true | Pause while KillAura+ is active, so the aura owns the tick it is aiming and attacking in. |
| `pause-on-scaffold` | true | Pause while Scaffold+ is active, so a sprint cannot shift the movement prediction it places from. |

Both pausing and a guard tripping stop the current sprint too, not just the asking, because the key
alone cannot undo it until vanilla next ticks.

### Settings

| Setting | Default | Description |
|---|---|---|
| `trigger` | moving | `moving`: any direction key asks for a sprint, which also keeps a sprint alive while strafing. `forward`: only the forward key, which is what vanilla starts a sprint from. |

A guard tripping does not just stop asking for a sprint - it stops the current one too, because a
guard can trip for a reason the key alone cannot undo until vanilla next ticks.


## Module: KillAura+

Attacks what it is aiming at, one target per tick, and only when the hit would survive the checks
Grim actually runs on it.

### The attack is one tick behind the aim, on purpose

An attack packet carries no rotation. The server resolves the hit with the rotation and position of
the **last movement packet it received**, so aiming and attacking in the same tick means the server
checks the hit against the aim from *before* that aim existed.

So the module splits it:

1. at `TickEvent.Pre` it writes the aim to the player entity, so this tick's physics and this tick's
   movement packet carry the same rotation;
2. at the next tick's `TickEvent.Pre` it attacks with the rotation that packet carried.

Grim's reach check then sees exactly the aim the module aimed with, from exactly the position the
module aimed from.

### What is verified before anything is sent

The rotation the last movement packet carried is captured at `SendMovementPacketsEvent.Post`, where
it is precisely what went out - not at the point the aim was written, so a mouse move landing in
between cannot make the two disagree.

Before attacking, the module casts that rotation from the eye at the target's hitbox and requires it
to land inside `range`. That is the same test Grim's `Reach` runs, at the same distance, from the
same position. A target that has stepped out of the way is simply not attacked that tick, instead of
being attacked and flagged.

The box is used exactly as the client sees it, with no expansion. Grim adds a small uncertainty
margin of its own, so being stricter than it can only cost attacks, never flags.

### The camera stays yours

The aim goes to the player entity, and the camera is rendered from a separate view - the same
mechanism ElytraFly+ uses, now shared through a small `FreeView` interface that both modules
implement. The body carries the aim, the mouse keeps steering the camera, and the two never have to
agree because the camera is never sent anywhere.

That is what makes the aim "real" instead of silent. The entity rotation is the value the client
physics read, the movement packet carries and the server predicts walking with, so aiming through it
introduces no disagreement anywhere. A silent rotation - the usual way this is written - puts the
packet's yaw at odds with the yaw the player walked with, which is a `Simulation` offset the moment a
movement key is held.

### Why walking is not steered by the aura

The yaw that goes to the server is the same yaw the client walks with - that is not a choice the
module gets to make, it is the thing that keeps the walking prediction in sync. So a body that is
aimed is a body that walks toward the target, and a client that keeps walking by the camera while
aiming by the packet is exactly the `Simulation` offset this addon exists to avoid.

The way out is to aim less. The aim only has to be in a movement packet one tick before an attack,
and an attack can only happen once the 1.9 cooldown is charged - a sword swings about once a second.
So the module takes the body **only on the ticks that feed a swing**, and on every other tick it
writes the player's own view back onto the body. Walking is therefore normal for all but the one tick
per swing, where the body snaps to the target, swings, and is handed straight back.

The free view is what makes those aim ticks invisible: the camera renders the view throughout, so the
body moving under it for a swing cannot be seen or felt.

The lock itself is continuous: it grabs the view at the first aim and hands it back on the last one,
so losing and re-acquiring a target does not move the camera at all.

`aim` can be turned off entirely, which turns the module into a plain triggerbot: it never touches
your rotation and only swings when you are already looking at the target.

### Critting with a real jump

A critical hit is not something a client decides. The server works it out when it resolves the
attack, from the state of the **last movement packet it received**: the player has to be falling,
off the ground, not in water, not climbing, not blind, not riding, and **not sprinting**. So the
module cannot "send" a crit; it can only make that state true and then swing.

`crit` does exactly that, and it is on by default:

1. it holds the swing back, one tick at a time, while a swing is ready;
2. it presses `jumpKey` for exactly one tick - a real jump, so the jump flag travels in the vanilla
   input packet and Grim's prediction simulates the same arc, instead of a velocity written by hand
   that the server never predicted;
3. it takes the sprint flag off. Letting go of the sprint key does **not** stop a sprint that is
   already running - vanilla only gives it up when the forward input, a hard collision, low hunger
   or water takes it away - so the flag has to come off as well.
4. once the state the last movement packet carried is a fall, it swings.

Steps 2 and 3 are what a player does by hand to set up a crit. Neither is something Grim checks:
every sprint check (`SprintA` - `SprintG`) is about *starting* a sprint, and there is no crit check
at all. Stopping one is reported the way running into a wall reports it - a `STOP_SPRINTING` action
ahead of that tick's movement packet - so both sides predict the same tick. The key goes back on the
tick the crit is swung, and vanilla's `START_SPRINTING` for it lands after the attack and before the
movement, so the crit is resolved with the sprint off and the sprint is back immediately afterwards.

Two details keep this from being annoying in the edge cases:

- a swing is only ever held when a crit is still *possible*. Riding, swimming, climbing, gliding,
  flying and blindness rule a crit out entirely, so the swing goes out immediately rather than
  waiting for a fall that is never coming;
- a jump that never left the ground - a low ceiling, a slab, a ladder - is noticed on the next tick
  and the swing goes out right then. `crit-timeout` is only the backstop for anything stranger.

One path the sprint stop cannot close: a player who double-taps `W` instead of holding the sprint key
leaves vanilla's double-tap window open, and vanilla will start the sprint again from it for the few
ticks that window lasts. It clears itself and the crit then lands, so the cost is a delayed swing
rather than a lost one.

Jumping is also just useful on its own: if you jump yourself, the module sees the fall coming and
waits for it, so the hit you were already setting up crits.

Sprint+ stands down while this module is on (`pause-on-killaura`), so it will not put the sprint key
back underneath a crit that is being set up.

### Circling the target

`orbit` (off by default) strafes around the target instead of standing in front of it. It works by
holding a **key** - `A` or `D` for the circle, `W` or `S` to hold the distance - and letting vanilla
turn that into movement. The strafe direction is read off the body yaw, the body yaw is the aim, and
the aim is what the movement packet carries, so the circle the server sees is the circle the client
walked: the input packet describes it and Grim's prediction reproduces it. Writing a velocity
directly would be a movement the server never predicted, which is the whole thing this addon exists
to avoid.

- `orbit-direction` picks the side; `Random` picks per target so every fight does not circle the
  same way.
- `orbit-distance` is the distance from the target's centre that the module tries to hold, with a
  0.35 block deadband so it is not tapping keys every tick. It goes down to **0.1**, which is the
  in-your-face setting: the two hitboxes are about 0.6 apart once they touch, so the distance
  correction cannot close the rest of the gap and `W` is simply held, pressing you into the target
  while the strafe key slides you around its face. The deadband stops mattering down there - every
  error is larger than it.
- `orbit-circle` draws that distance as a ring around the target, so the circle the keys are walking
  is something you can see rather than infer (see [Seeing the range](#seeing-the-range)). It is a
  render setting: nothing about the movement depends on it.
- Keys you are holding yourself are never fought with. If you hold a strafe key you are steering the
  circle and the module leaves it alone; if you hold `W` or `S` the module leaves the distance
  alone, so your own walking decides how close you get. Ownership is decided by the **physical** key,
  not by the pressed flag, so a key the module pressed and you then pressed yourself is handed
  straight over instead of being lifted underneath you.

Because a circle is drawn by the yaw the strafe keys are read against, the orbit is also the one
case where the aim stays on the target for the whole fight instead of only on the ticks that feed a
swing. That is deliberate: it is exactly what puts the strafing on the circle.

### The rules it follows

| Grim check | What the module does |
|---|---|
| `Reach` (and its hitbox half) | The ray from the eye must land on the target's box, inside the entity interaction range. `range` is capped at the vanilla 3.0 for that reason. |
| `PacketOrderB` | One attack per tick, always followed by a swing in the same tick, in the vanilla attack-then-swing order. |
| `MultiInteractA` / `MultiInteractB` | Never two entities in one tick, so the "multiple entities" and "multiple target positions" checks have nothing to see. |
| Attack cooldown | The 1.9 swing timer is respected, which is also what makes the damage real. |
| `Prediction` (movement) | The crit jump and the orbit are key presses, so the input packet describes them and the prediction reproduces them. No velocity is ever written. |
| `SprintA` - `SprintG` | Only *starting* a sprint is checked. Stopping one for a crit is reported as `STOP_SPRINTING`, the same packet a wall produces. |
| `AimDuplicateLook` | The aim goes to the entity rotation, not the packet, so the client's own "did the rotation change" test still decides whether a look packet goes out. A duplicate look is never sent. |

### Settings

| Setting | Default | Description |
|---|---|---|
| `aim` | on | Aim at the target by writing the rotation to the player entity. Off: triggerbot behaviour. |
| `rotation-step` | 0° | Max degrees per tick the aim may turn. 0 snaps, which lands the most hits; a small value looks smoother but lags a moving target and skips more attacks. |
| `range` | 3.0 | How far the hitbox may be from the eye. Capped at vanilla's entity interaction range. |
| `cooldown` | on | Only attack when the 1.9 attack cooldown is charged. |
| `pause-on-use` | on | Do not attack while using an item. |
| `restore-view` | on | Hand the view back to the body when there is nothing left to aim at. |
| `crit` | on | Hold the swing back, jump with the jump key and drop the sprint flag so the hit lands as a critical. |
| `crit-timeout` | 20 | Ticks a swing may be held waiting for the fall before it goes out anyway. |
| `orbit` | off | Circle the target with the strafe keys instead of standing in front of it. |
| `orbit-direction` | clockwise | Which way to circle. `random` picks a side per target. |
| `orbit-distance` | 2.5 | Distance from the target's centre to hold while circling. Keep it inside `range`. `0.1` is the in-your-face end, where the hitboxes touch and the keys just press you around the target. |
| `players` / `hostile` / `passive` | on / on / off | What to attack. Friends are always skipped. |
| `render` | on | Master switch for the render group. |
| `circle` | on | Draw the attack range as a ring around the player. |
| `outline` | on | Outline every entity inside that range that the module would consider a target. |
| `circle-color` | red | Colour of the ring. |
| `circle-segments` | 90 | Segments the ring is built from. Higher is rounder. |
| `circle-at-eyes` | off | Draw the ring at eye height instead of at your feet. |
| `circle-width` | 0.08 | Radial thickness of the ring, in blocks. |
| `circle-height` | 0.3 | Height of the ring's outer wall, in blocks. 0 makes it flat. |
| `outline-color` | amber | Outline colour for entities in range. |
| `target-color` | red | Colour for the entity the module is working on: outlined with this, filled with a quarter of its alpha. |
| `orbit-circle` | on | Draw `orbit-distance` as a ring around the target being circled. Only shown while `orbit` is on. |
| `orbit-circle-color` | cyan | Colour of the orbit ring. |
| `orbit-circle-segments` | 60 | Segments the orbit ring is built from. |
| `orbit-circle-width` | 0.08 | Radial thickness of the orbit ring, in blocks. |
| `orbit-circle-height` | 0.3 | Height of the orbit ring's outer wall, in blocks. 0 makes it flat. |
| `attack-effect` | on | Draw a ring and a hitbox flash on every hit. |
| `attack-effect-color` | amber | Colour of the effect for a normal hit. |
| `crit-effect-color` | red | Colour of the effect when the hit is a critical. |
| `attack-effect-duration` | 0.4 | Seconds the effect lasts. |
| `attack-effect-radius` | 1.1 | Radius in blocks the ring expands to before it fades. |
| `attack-effect-box` | on | Flash the target's hitbox along with the ring. |
| `attack-particles` | on | Throw vanilla crit particles at the hit. Client-side only. |

Anything living that is not a player, a `Monster` or a `PassiveEntity` is left alone rather than
guessed at.

### Seeing the range

`render` draws the attack range as a ring around the player, at foot level by default.

The ring is a **band**, not a line: `circle-width` gives it radial thickness and `circle-height`
gives it a low outer wall. A one-pixel loop lying on the ground is almost edge-on when you are
standing on it, which is why a flat ring always looks too thin no matter how bright it is - the top
face reads from above and the wall reads from eye level, so this works from both. 270 quads at the
default 90 segments, which is nothing.

Adjacent segments share their corner vertices exactly, so the band has no seams to double-blend
along when its alpha is below opaque.

`circle-at-eyes` moves it up to eye height. The range is measured from the eye to the target's
hitbox, so the eye-level ring is the one that matches what `reaches()` actually tests; the foot-level
one is just easier to read while walking.

`outline` draws a box around every entity inside that range, using the module's own target filters -
players / hostile / passive, friends excluded. Drawing and deciding share one predicate
(`isCandidate`), so the outline can never claim something is attackable that the aura would skip. The
entity the module is currently working on is drawn in `target-color` with a translucent fill instead
of a plain outline, so it is obvious which one was picked.

The boxes follow the rendered position rather than the tick position, or they would visibly stutter
against the entities they belong to.

`orbit-circle` (on when `orbit` is on) draws `orbit-distance` as the same kind of band, centred on
the entity being circled instead of on the player. The range ring says how far a swing reaches; this
one says where the strafe keys are trying to hold you, so the deadband is something you can watch
close rather than infer from the movement. It is only drawn while the orbit is actually driving -
when the module is holding the keys - and its centre follows the rendered position for the same
reason the outlines do.

### The hit effect

`attack-effect` draws something on every hit, so a swing the module made is a swing you can see:

- a **shockwave ring** expands from the target's feet, fast at first and slowing as it goes, thinning
  and fading out over `attack-effect-duration` (0.4 s by default). It is anchored where the hit
  happened, so it stays behind when the target is knocked away or killed;
- a **flash on the hitbox** that follows the target, growing slightly as it fades, if
  `attack-effect-box` is on;
- a burst of vanilla **crit particles** at the hit, if `attack-particles` is on.

The ring is built from the same band the range ring and the orbit ring are (`drawBand`), so it is a
shockwave rather than a one-pixel circle. Its expansion runs on the wall clock instead of the tick,
which is what keeps it smooth at any frame rate; the whole effect costs a few dozen quads and no
allocation per frame.

A critical is coloured with `crit-effect-color` instead of `attack-effect-color`. That is not a
guess: a crit is decided by the state of the last movement packet, and `critConditions()` reads
exactly that state at the moment the swing goes out - the same value the server judges the hit by.
So a crit the player set up by jumping themselves is coloured as a crit too.

Nothing about the effect leaves the client. It is a render and some particles in this client's own
particle manager; no packet is involved, so there is nothing for Grim to have an opinion about.

`attack-effect` follows the `render` master switch, like everything else in the group.


## Loading screen: `hskk`

The splash that covers the resource reload says **hskk** where Mojang's wordmark normally sits: white
on the same red field, fading in and out with the same alpha as the progress bar under it.

It is a mixin rather than a resource pack, and that is not a preference. `SplashOverlay.LogoTexture`
reads `textures/gui/title/mojangstudios.png` straight out of the client's `DefaultResourcePack`, so a
mod-supplied copy of that file is never consulted - the only way in is to take over the two
`DrawContext.drawTexture` calls that paint the wordmark's left and right halves. The first is
replaced by the text, the second is dropped so Mojang's half cannot land on top of it.

The word is centred on the box those two halves share, and scaled to the capital height of the line
it replaces - 156 of the logo's 256 rows - so it carries the same weight as the original. Everything
else on the splash is untouched: the background fill, the progress bar and the fade are all vanilla.

### Winning against other splash mods

The two calls are a contested spot: **Puzzle** wraps them too (its `resourcepackSplashScreen`
option) and, when that option is on, never passes them on - it draws the logo with its own layer
instead of calling the original. That is why the hooks here are `@WrapOperation` and not `@Redirect`:

- a `@Redirect` cannot be chained. Anything that *wraps* it and then skips the original swallows it
  silently, which is exactly what Puzzle does, and "hskk" simply never appears;
- `@WrapOperation` chains. The mixin applied **last** holds the outermost position, and if it does
  not call the wrapped operation, nothing under it runs.

Which mixin is applied last is decided by mixin priority: mixins are applied in ascending priority
order, so the higher priority is applied later. Puzzle declares **2000** on its splash mixin, so this
one is declared at **2500** - high enough to wrap Puzzle rather than the other way round.

Two consequences worth knowing:

- with our mixin at 2500, Puzzle's logo layer is skipped for these two calls, so a custom Puzzle
  splash logo will not be drawn while this addon is installed. Only those two calls are affected -
  Puzzle's background colour and progress bar still work;
- the same decision applies to any other mod that wraps these calls. A mod above 2500 would win
  instead, and the failure is a missing "hskk", never a crash: a skipped wrap loses an argument, it
  does not break the splash.


## License

CC0, inherited from the Meteor addon template.