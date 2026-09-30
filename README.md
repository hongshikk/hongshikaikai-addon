# HongShiKaiKai

A [Meteor Client](https://meteorclient.com/) addon for Minecraft **1.21.4 - 1.21.11**, providing the
**ElytraFly+**, **Scaffold+**, **Sprint+**, **KillAura+** and **NoFall+** modules in the
`hongshikaikai` category, the **ChatCopy** module in `hskk-tools`, plus a `scaffold-blocks` HUD element
and the `hskk` loading screen.

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
| `up-pitch` | -90° | Flight pitch when space is held alone. -90 stalls; vanilla elytra climbs best near -60..-85. |
| `diagonal-pitch` | -45° | Flight pitch when space and a direction are held together. |
| `down-pitch` | 90° | Flight pitch when sneak (shift) is held, with or without a direction. Positive looks down, so the elytra dives. Space and sneak together cancel out to `level-pitch`. |
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

### Flying next to NoFall+

The two modules do not pause each other, and neither one treats the other as a fall-saver.

ElytraFly+ is a flight assistant, not a NoFall. It only puts an elytra on when `auto-takeoff` fires
on a fresh jump press, and `auto-takeoff` is off by default; the glide itself is started by vanilla,
because the key the player pressed is what sends `START_FALL_FLYING`. An ElytraFly+ that is enabled
but not gliding therefore has nothing to deploy with, and NoFall+ does not stand down for it - the
falls nobody is gliding through (walking off a ledge, a flight that ended when the rockets ran out,
an elytra that broke mid-air, a takeoff the server refused) are exactly NoFall+'s business.

The dive is the other half, and it is the case that used to kill people. `sneak` with `lock-view` on
points the body at the floor at `down-pitch`, and a dive like that is a real glide but not a saved
one: `Entity#limitFallDistance` only caps the fall while `velocity.y > -0.5`, so the fall distance
accumulates for the whole dive and the landing is charged in full. NoFall+ `arrest-dive` watches for
exactly that - a glide whose remaining ground is inside the distance a level glide needs to bleed the
current vertical speed back to the cap - and takes the body rotation over, holding the level pitch
until the descent is slow. Level is where the bleed is strongest (the lift term goes with `cos^2` of
the pitch), which is why the module's own `deploy-pitch` is 0 as well.

The rotation handed over here is the rotation sent to the server: the movement packet carries it and
the server predicts with it, so a save the client believes in is a save the server applies. While
NoFall+ holds the glide it reports `isSaving()` and this module stands off the view lock entirely -
and does **not** hand the free view back to the body while doing so, because that write lands on the
very pitch NoFall+ just set.

NoFall+ `in-place` is the one case where the save comes with a free view of its own. It takes the
lock over for the whole fall and outranks this module in it, so the camera carries on from the view
this module was rendering rather than snapping back to the body; the view goes back to the body
when the feet are down, exactly as it does when this module's own lock ends. See the NoFall+
section for what the drop does.

The lock comes back the moment the descent is under the cap and the player asks to go up: NoFall+
reads that request off this module's own keys (space, or a direction key without sneak), so the
first thing `lock-view` does after a save is obey the climb key again. That is also what makes
`up-pitch` matter after a dive: the default is -70, because at exactly -90 both the climb term and
the lift term vanish and holding space stalls instead of climbing. If the player would rather keep a
dive under manual control, `arrest-dive` can be switched off.

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


## Module: NoFall+

Survives a fall the server would have charged for by getting the player under an elytra glide before
the ground. Nothing here writes an `onGround` flag. The save is a glide, which carries the player
forwards; `in-place` starts that glide at the physical minimum instead of the `deploy-ticks` run-up,
ends it as soon as the fall distance has actually been capped, and aims the body at the landing
point, so the tail of the fall is a descent onto the spot the player was falling onto (see
[Landing where you fell](#landing-where-you-fell-the-in-place-drop)).

There are two shapes of that, because "a glide" only saves a fall while its descent is already slow.
A fall that has not reached the ground yet is a **deploy**: swap an elytra in, press jump, hold the
level pitch, land. A glide that is already running and pointed at the ground - ElytraFly+ with sneak
held writes exactly that - is an **arrest**: take the body rotation over and hold the same level pitch
until the descent has bled back under the cap. The rest of this section is about why the glide is the
only route, and the arrest is at the end.

### Why the onGround route is closed

Three separate things stop a packet NoFall on Grim, and the second one is not even Grim's:

- a claimed `onGround` on a packet that carries a position is replayed through the movement
  prediction, so `GroundSpoof` flags every claim the prediction does not agree with. A claimed
  `onGround` on a packet with no position is the case `NoFall` exists for, and
  `CheckManagerListener` vetoes the claims that are not near the ground before the check sees them;
- spending the claim does not help. In 1.21.4 `Entity#fall` settles the whole stored fall distance
  the moment a packet claims ground, so one spoof at altitude *is* the fall damage. The only
  damage-free state is claiming ground on every packet, which is exactly what `GroundSpoof` looks
  for;
- the 1.17 duplicate movement packet that used to get around this is rewritten by
  `CheckManagerListener#isMojangStupid`, whose comment is "Prevents position-related bypasses like
  nofall".

### What the glide does instead

`LivingEntity#tickGliding` calls `Entity#limitFallDistance` on every tick the player glides, and that
method caps the stored fall distance at 1.0 block - but only while `velocity.y > -0.5`:

```java
if (this.getVelocity().getY() > -0.5 && this.fallDistance > 1.0F) {
    this.fallDistance = 1.0F;
}
```

That condition is the whole trick, and it has two consequences that shape the whole module:

- a glide aimed at the ground does nothing. Falling feet-first means the pitch points down, the glide
  dives at up to terminal velocity, the cap never runs and the landing is charged in full.
  `level-pitch` is therefore not cosmetic - the module writes the pitch (`deploy-pitch`, 0 by default)
  when the glide starts and holds it for as long as the glide lasts, which is also the posture a
  player uses when doing this by hand. **This is also why being in a glide is not the same as being
  saved**, and why the module arrests a dive (below): a glide someone else started, pointed down, is
  charged in full;
- the glide has to be given time. A level glide bleeds vertical speed by roughly 12 percent per tick
  (the `-0.1 * vy * cos^2` lift term against 0.98 drag), so a fall of 1.0 blocks/tick needs about
  seven ticks above the ground before the vertical speed is inside the cap, and a terminal dive needs
  about seventeen. `deploy-ticks` defaults to 8 for that reason, and the debug output names the tick
  the glide became slow enough.

One tick under the cap is enough: the method assigns 1.0 rather than subtracting, so the whole
accumulated fall distance is wiped the first time the glide is slow enough, and level flight stays
under the threshold from then on.

The module does not start the glide itself. It puts an elytra in the chest slot and presses the jump
key, and `ClientPlayerEntity#tickMovement` sends `START_FALL_FLYING` the way it does for a player.
That matters twice over: vanilla flips its own glide flag in that same tick, so the client physics,
the command and the input packet all describe one state, and the packet order is the one Grim was
written against.

### Arresting a dive

`arrest-dive`, on by default, covers the glide the module did not start. ElytraFly+ with `lock-view`
steers the body with the keys, so sneak held writes `down-pitch` and drives the body at the floor; a
vanilla dive, or the mouse held down, is the same fall. All of them are real glides and none of them
is a save, because the cap only runs while `velocity.y > -0.5`.

The trigger is a distance, not a tick count. A level glide eats the geometric sum
`(speed - 0.45) / 0.11` of ground while it slows from `speed` down to the cap - about thirty blocks
for a terminal dive - and starting later than that is a partial save at best. Timing it off "ticks
at the current speed" would fire far too early, because the descent is still fast: a terminal dive
would be arrested well over a hundred blocks up and every intentional dive would be neutered.
`arrest-margin` is the room left on top of the physical minimum, and `scan-range` has to cover the
arrest distance for the ground to be seen at all.

Once the arrest is on, the module holds `deploy-pitch`. Level is where the bleed is strongest: the
lift term goes with `cos^2` of the pitch, so pointing up or down both lose it. ElytraFly+ stops
writing the body rotation while `isSaving()` is true, so there is one writer and not a fight over the
pitch, and the rotation that is written is the rotation in the movement packet - the client physics
and the server prediction use the same value, so the save is not a client-side belief.

**The hold ends as soon as the descent is back under the cap and the player asks for height**, and
that half matters as much as the arrest. Pitching up is not a way out of a dive - the climb term
needs horizontal speed and disappears at the same `cos^2` the lift does, and at exactly -90 both are
gone, which is why ElytraFly+'s own `up-pitch` description warns about -90 - so the module does not
hand control back while the dive is still fast. Once `velocity.y` is above -0.5 the fall distance has
already been wiped to 1.0 and holding the rotation would only mean "dive, then pull up" ends stuck
pointing at the floor until the landing. With `lock-view` the request is read off the keys ElytraFly+
would be using anyway: space, or a direction key without sneak. With the mouse on the body it is a
look clearly above the held pitch. Turn `arrest-dive` off to keep a dive under manual control.

### Landing where you fell: the in-place drop

Everything above saves the fall by gliding, and a glide is not a vertical drop. A level glide
descends at about 0.17 blocks a tick and travels forwards at roughly ten times that, and the tail
between the tick the cap runs and the tick the feet hit the ground is spent gliding forwards - a
hundred block fall ends a couple of hundred blocks downrange of where it started. `in-place`, off by
default, is about not ending up there.

The switch hands the module the body rotation through the same free view ElytraFly+ and KillAura+
use. The mouse keeps the camera, the module writes the rotation the server sees, and the view is
handed back to the body when the fall ends - one movement packet later the server is told where the
player was looking the whole time. That free view runs for the whole save, not just the drop,
because the body rotation belongs to the module from the first tick it writes it. Without the switch
the pitch is written straight to the entity exactly as it always was and the camera goes with it.

`in-place` also moves the glide start from the clock to the distance, and that half is the one that
matters for where the fall lands. The ordinary gate is `deploy-ticks`: the run-up measured in ticks,
extended by the bleed time for the current speed. Comparing ticks-until-the-ground at the current
speed with ticks-of-bleed is generous, because the glide decelerates while it bleeds - a fast fall is
asked for roughly twice the ground the bleed actually needs. Every block past the minimum is glided
level at 0.17 blocks a tick, and that is where the forward drift comes from, far more than from the
last few blocks. With the switch the gate is `bleedClearance() + pressLead()`: the geometric descent
the bleed needs, `(speed - 0.45) / 0.11`, plus three ticks of the fall as it stands for the press
itself (a key that has to be lifted first, and the tick it takes to press), plus `in-place-lead`.
`deploy-ticks` and its distance are not consulted in this mode.

Replaying the glide physics tick by tick - a free fall from rest onto flat ground, no input - the
forward drift of the whole save comes out at about:

| Fall | `deploy-ticks` gate | `in-place` gate |
|---|---|---|
| 20 blocks | 10 blocks | 6 blocks |
| 50 blocks | 79 blocks | 21 blocks |
| 100 blocks | 206 blocks | 30 blocks |
| 200 blocks | 373 blocks | 53 blocks |

The terrain downrange and what the player does with the mouse change those numbers; the ratio is the
point. One thing to know about the late start is that it measures the ground **below the press
point**, while the glide then travels forwards - ground that is closer than the ray said, a slope or
a wall the drift runs into, is the way an in-place save can arrive without the room the bleed needed.
It is bought with margin: `deploy-ticks` is the conservative gate because it can afford to press
early, and `in-place-lead` is the knob that buys the margin back. Raise it if a save still lands
hard, or turn `in-place` off to get the old gate back.

While `in-place` is on the module does not hold the level pitch all the way down either. It holds it
only as long as the cap needs, and then aims the body at the **landing point**: the ground its own
vertical raycast sees straight down, taken at the moment the drop takes over. The aim is recomputed
every tick, so as the forward push carries the player past that point the aim keeps pointing back at
it - which is what turns the tail into a descent onto the point instead of a level glide past it.

The drop cannot start before the cap, and the reason is the same two vanilla lines as always:

- `Entity#limitFallDistance` leaves the server's fall distance at 1.0 block and vanilla starts
  charging at 3.0 (`SAFE_FALL_DISTANCE`), so exactly **two blocks** of descent can be spent outside
  the cap without a scratch;
- the cap keeps resetting the fall distance for as long as the descent is still above -0.5, so the
  first blocks of the drop cost nothing at all. The module replays both lines tick by tick
  (`predictedFallDistance`) rather than holding a fixed clearance, and starts the drop only when the
  whole descent onto the point still fits inside those two blocks. `in-place-margin` (0.5 by default)
  is what it holds back.

If the ground leaves the raycast mid-drop - the drift carries the player out over a cliff - the drop
is abandoned, the level pitch comes back, and the fall is saved the ordinary way. With `debug` on,
the take-over says `in-place: N blocks of ground left and the drop adds X of fall distance - aiming
at the landing point`, and nothing is said when the drop never becomes affordable.

Two consequences worth knowing. The drop cannot undo the forward speed the bleed already created:
the elytra turns about 0.1 blocks a tick of horizontal speed out of every block it descends while
the lift term runs, and only a longer glide dragged back over the same ground removes it. What the
drop does is stop adding to it and turn it back toward the point, so the landing ends up near the
landing point rather than downrange of it. And with `in-place` on there is no pull-up hand-back: the
drop is a handful of ticks long and the rotation belongs to the module until the feet are down.

### The three timing rules

- the press has to be a **fresh** press. `ElytraB` reads the input that precedes the command (it has
  to say `jump = false`) and the input that follows it (it has to say `jump = true`). The client only
  sends `PlayerInput` when the input changes, so a key that is already down leaves the previous input
  at `true`, which `ElytraB` reads as "started gliding without releasing jump". A key that is down
  when the module wants to press is therefore dropped for a tick first, and the tick right after a
  press is never the next press. The same rule is what vanilla enforces, so the press is exactly the
  one a player makes;
- the elytra is swapped in as soon as the ground below is far enough to hurt (`min-fall-distance`
  measured straight down), which is the start of the fall, not once three blocks have already been
  fallen - the swap costs two ticks (drop the input, click) plus the lead, and all of it has to be
  over before the glide can start. The glide itself still waits until the fall is genuinely dangerous.
  `equip-lead-ticks` has to pass before the command may go out. The inventory click
  is applied by the server's main thread while Grim reads the chest plate straight off the platform
  player, so a command that arrives in the same tick as the click is cancelled as "no elytra" - the
  client glides, the server never caps the fall, and the save silently fails. One click per fall: a
  second click sent before the server answers would swap the elytra straight back out;
- the swap is a **legal inventory click**, which means the input is dropped for the tick before it.
  Grim's `MultiActionsC` flags a `CLICK_WINDOW` that arrives while the last input packet still
  reports a key down - `KnownInput#moving` counts jump as well as the four directions - and a vanilla
  client cannot produce that, because the GUI that allows the click is also what stops the input. The
  module releases the movement, jump and sprint keys for one tick, clicks on the next one, and hands
  the keys back immediately; the cost is one tick of steering. The click that puts the chest item
  back gets the same treatment, and it needs it more: it goes out when the player is back on the
  ground, which is exactly when they are walking. Both clicks are the only inventory writes the
  module makes;
- the glide starts a few ticks above the ground (`deploy-ticks`). The server caps the stored fall
  distance on the tick it processes the command, so the command has to arrive before the landing
  packet, not with it.

One consequence worth knowing: a jump key the module had to override is left **up** when the fall
ends, because telling the player's own press apart from the module's needs the physical keyboard
state, and that call changed signature after 1.21.8. Press the key again to jump.

The chest item the elytra displaced is put back once the player is down, unless `restore-chestplate`
is off. Turning the module off mid-glide leaves the elytra on rather than dropping the player.

### Seeing what it does

`debug` is on by default and reports one line per stage in chat: the fall it armed on (fall distance,
ground distance, ticks and velocity), the inventory swap, every jump press, the start of the glide,
the dive arrest and the landing. When nothing is happening, the same setting also says why - paused,
guarded, no usable elytra, horizontal speed above the cap, or cooldown left. With `in-place` on, the
same output says when the drop takes over and how much fall distance it predicts for it. Turn it off
once it behaves.

### Guards

| Check | Guard |
|---|---|
| `ElytraA` / `ElytraC` | One start per fall, `cooldown-ticks` apart, and at most four attempts if the command keeps being rejected. |
| `ElytraD` | The elytra has to be a real one with more than one point of durability left. |
| `ElytraF` | Nothing happens below `min-fall-distance`, by which point the player has been off the ground for several ticks. |
| `ElytraG` / `ElytraH` / `ElytraI` | No glide under levitation, in a vehicle or in water; each of those ends the attempt and puts the chest item back. |
| Mace | `pause-on-mace` keeps a smash intact, and keeps a mace dive out of the dive arrest. |
| Dive | `arrest-dive` takes a running glide that is pointed at the ground over and levels it, which is the only way a dive is ever saved. |
| In-place drop | The drop starts only after the cap has run and only while the whole descent onto the landing point fits in the two blocks the cap leaves; ground that leaves the raycast mid-drop abandons it and the level pitch comes back. |
| Kinetic damage (1.21.2+) | `max-horizontal-speed` skips deployments that are not a plain vertical drop, because gliding into a wall or slope at speed is charged by the game itself. |

### Settings

| Setting | Default | Description |
|---|---|---|
| `min-fall-distance` | 3.0 | Blocks of fall before anything happens, measured by the module from y - the client's own `fallDistance` field is never updated in 1.21.4, which is what the in-game heartbeat caught. 3.0 is where vanilla starts damaging, so there is nothing to save below it. |
| `deploy-ticks` | 8.0 | Minimum ticks above the ground to start gliding. The module extends this automatically for the current vertical speed: a level glide bleeds roughly 11 percent of its speed per tick and the cap needs that speed above -0.5, so a terminal dive gets about twenty ticks of run-up. This is not and cannot be a last-instant deploy. |
| `equip-lead-ticks` | 2 | Ticks the elytra has to have been on the chest before the command may be sent. Grim reads the chest plate off the platform player, so a command that arrives together with the inventory click looks like "no elytra" to it: the command is cancelled, this client still glides, and the landing hurts exactly as much as without the module. |
| `scan-range` | 32 | How far down the ground is looked for. A fall longer than this waits for a later tick. |
| `max-horizontal-speed` | 1.0 | Deploy only below this horizontal speed, in blocks per tick. |
| `restore-chestplate` | on | Swap the original chest item back once the player is down. Off keeps the elytra on. |
| `level-pitch` | on | Hold the view level while the glide runs. Without it the glide dives and `limitFallDistance` never caps anything. |
| `deploy-pitch` | 0.0 | Pitch written when the glide starts. 0 is level flight, the slowest descent and the safest cap. Negative looks up. |
| `restore-pitch` | off | Put the view back after the fall. Off leaves it level, which is one camera snap instead of two. Ignored by `in-place`, which always hands its free view back. |
| `arrest-dive` | on | Take over a glide that is already diving at the ground and hold the level pitch until the vertical speed is back under the cap, then hand the rotation back as soon as the player asks for height. This is the ElytraFly+ sneak/`down-pitch` dive and any vanilla dive; a glide on its own caps nothing during one. |
| `arrest-margin` | 4.0 | Extra blocks of clearance on top of the distance a level glide needs to bleed the current vertical speed back to the cap. Raise it if a dive still lands hard; 0 is the physical minimum with no room to spare. |
| `in-place` | off | Start the glide at the physical minimum instead of the `deploy-ticks` run-up, end the level glide once the cap has run, and aim the body at the landing point below so the tail of the fall is a descent onto that point instead of a level glide past it. The camera stays free: the module writes the rotation the server sees and hands the view back to the body when the fall ends. Needs `level-pitch`. |
| `in-place-margin` | 0.5 | Blocks of the two the cap leaves before vanilla charges, held back when deciding whether the drop is still free. Raise it if a landing still hurts; 0 spends the two blocks to the last inch. |
| `in-place-lead` | 1.0 | Extra blocks of ground kept beyond the physical minimum before the in-place glide starts. The press room - three ticks of the current fall, scaled by the speed - is already folded in. Raise it if a save still lands hard; 0 spends the physical minimum plus the press room and nothing else. |
| `debug` | off | Report every stage of every fall in chat, including the reasons nothing happened. |
| `pause-on-mace` | on | No glide while a mace is held, and no dive arrest either. |
| `levitation-guard` | on | No glide under levitation. |
| `cooldown-ticks` | 4 | Minimum ticks between two starts, measured from a start that actually happened. |

### If it does not save you

- **No elytra in the inventory**: the module says so once per fall and does nothing. ElytraFly+ can
  supply one from the hotbar; this module needs it somewhere in the inventory, chest slot included.
- **A dive still lands hard**: read the `dive arrest: vy=... with N blocks of ground` line. If it did
  not appear at all, the ground was never seen - a terminal dive needs roughly thirty blocks of it, so
  `scan-range` has to cover the arrest distance. If it appeared too late,
  `the dive never got under the cap (vy=...) - raise scan-range or arrest-margin` says the bleed
  never finished; raise `arrest-margin` (and `scan-range` with it).
- **Nothing happens at all**: turn `debug` on (it is on by default) and read the chat. The fall line
  prints the fall distance, the ground distance and the estimated ticks to impact, and every reason a
  press was skipped is named. A short fall that is over before the inventory click round-trips is the
  one case the module cannot help with.
- **The command is rejected**: the server, not this client, decides. A server that cancels
  `START_FALL_FLYING`, or a client-side mod fighting for the chest slot, both break it.
- **Damage after a save**: the module prints `took X damage (health A -> B), capWindow=...` whenever
  health drops during a fall it was involved in. `capWindow=true` means the fall distance was wiped
  and the damage came from something else - kinetic damage from a glide into a wall or slope is the
  usual answer. `capWindow=false` means the glide arrived too late and `deploy-ticks` wants raising.
- **You are gliding but still take damage**: read the cap lines. `slow enough for the cap, vy=...`
  means the fall distance was wiped and the save should have worked; `landed before the glide got
  under the cap (vy=...) - raise deploy-ticks` means it was not, and the number says by how much. A
  dive gets its own pair: `dive arrest: vy=... with N blocks of ground` when the arrest starts, and
  `the dive never got under the cap (vy=...) - raise scan-range or arrest-margin` when the bleed ran
  out of ground. 1.21.2+ also charges kinetic damage for a glide into a wall or slope, which is what
  `max-horizontal-speed` is about.
- **The elytra wears out**: every glide costs durability, and an elytra down to its last point is
  refused by the module and by the game.
- **`in-place` is on and the save still glides past**: read whether the
  `in-place: N blocks of ground left and the drop adds X of fall distance - aiming at the landing
  point` line appeared. If it did not, either the cap never ran or the ground never came inside the
  two blocks the drop is allowed to spend - `in-place-margin` of 0 spends the most of it. If the line
  did appear, the drop is doing what it can: the forward speed the bleed created before the cap ran
  cannot be removed by the drop, only stopped from growing.
- **`in-place` is on and the landing hurts**: the mode starts the glide at the physical minimum, so
  the margin `deploy-ticks` was holding is gone. The `waiting: N blocks of ground left, the bleed
  needs X and the press lead is Y` line says what the gate is waiting for, and
  `landed before the glide got under the cap (vy=...) - raise in-place-lead` says the press came too
  late. Raise `in-place-lead` in blocks; the cost is that each block is glided level again.


## Module: ChatCopy

Puts chat on the clipboard. Minecraft's chat is drawn text with no selection at all, so without
something like this everything said in chat is either retyped by hand or never shared - which makes it
impossible to report what a module printed.

It lives in its own **`hskk-tools`** category rather than with the combat and movement modules.

### Right click a message

Right clicking a line in the open chat screen copies the message under the cursor and nothing else. A
message that wrapped over several lines is copied as the one message it is: the text is taken from the
logical message behind the drawing, so the words are not glued together at the wrap points and the
spaces the wrapping ate are back.

Working out which message that is uses the chat HUD's own layout arithmetic, written out in the module
instead of called on the HUD:

- the cursor becomes a line offset from the bottom of the chat,
  `(scaledHeight - mouseY - 40) / (scale * lineHeight)`;
- `scrolledLines` is added, so a scrolled-back chat is the same case as a fresh one;
- the `endOfEntry` flag - true on the last drawn line of each message - says where one message ends
  and the next begins.

That is the arithmetic `ChatHud.getTextStyleAt` used, and writing it out is not a preference: that
method, along with the whole hit-testing half of the chat HUD, was **removed in 1.21.11**, while the
layout it was built on is unchanged from 1.21.4 to 1.21.11. The three private fields it needs
(`visibleMessages`, `messages`, `scrolledLines`) are read through this addon's own accessor mixin
(`ChatHudAccessor`) under names of its own, because Meteor's equivalents were renamed across the same
range (`getVisibleMessages()` became `meteor$getVisibleMessages()`) while the field names did not
change at all.

### Ctrl + right click for the whole log

Holding Ctrl while right clicking copies the whole log instead of one message.

That path never touches the chat HUD. Meteor posts `ReceiveMessageEvent` for every message that
reaches the chat HUD, server chat and this addon's own output alike, before that message is added, so
listening to that event is enough. The plain text of the last few hundred messages is kept in a ring
buffer, formatting stripped.

`filter` narrows what the whole-log copy keeps, case insensitively, and it is the intended way to use
this with a module's debug output: set it to the module name (`nofall+`) and only those lines are
copied. `lines` caps how far back the copy reaches.

`seed-from-log` reads the tail of `logs/latest.log` on activation and seeds the buffer from it.
Minecraft logs every chat line as `[CHAT] ...`, including the lines this addon prints, so a module
that is switched on *after* something interesting happened can still be copied from.

### Why the button is polled

The right mouse button and Ctrl are read straight from GLFW instead of taken from a Minecraft event:
within this addon's version range Meteor's mouse event is `MouseButtonEvent` up to 1.21.8 and
`MouseClickEvent` after it, and vanilla's `Element.mouseClicked` changed shape at the same boundary. A
polled button is the same answer on every version.

Nothing leaves the client: the buffer is in memory and the copy goes to this client's clipboard. The
only file read is that log tail, once per activation.

### Settings

| Setting | Default | Description |
|---|---|---|
| `lines` | 60 | How many of the most recent matching messages the Ctrl + right click copy takes. |
| `filter` | empty | The Ctrl + right click copy keeps only messages containing this text, case insensitive. Empty keeps everything. |
| `right-click` | on | Right click a message to copy it; Ctrl + right click copies the whole log. |
| `seed-from-log` | on | Seed the buffer from `logs/latest.log` on activation. |
| `feedback` | on | Say in chat what was copied. |


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
