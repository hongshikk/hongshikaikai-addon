package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Holds the sprint key down whenever sprinting is legal, so W never has to be tapped twice.
 *
 * <h2>It only presses the key</h2>
 * The module writes {@code sprintKey.setPressed} and nothing else. Vanilla then decides whether
 * that becomes an actual sprint, and reports it the way it always does - the sprint flag in the
 * {@code PlayerInput} packet and the start/stop sprinting action. Client physics and server
 * prediction therefore agree on the sprint state, and the packets are the ones a player holding the
 * key would have sent.
 *
 * <p>Driving {@code player.setSprinting(true)} directly, which is the obvious way to write this,
 * flips the entity flag while the reported input still says the key is up. That is a disagreement
 * between what the client did and what it told the server, which is the shape of thing Grim's
 * simulation looks for.
 *
 * <h2>Why the guards exist</h2>
 * Grim checks the sprint state against a list of situations, and most of them cancel the movement
 * packet:
 *
 * <ul>
 *     <li>{@code SprintA} - food below 6. This one carries {@code setback = 0}, an immediate
 *         rubber-band, so it is not a cosmetic guard.</li>
 *     <li>{@code SprintB} - sprinting while sneaking or crawling.</li>
 *     <li>{@code SprintC} - sprinting while using an item.</li>
 *     <li>{@code SprintD} - sprinting while blind.</li>
 *     <li>{@code SprintE} - sprinting while pushed against a wall.</li>
 *     <li>{@code SprintF} - sprinting while gliding.</li>
 *     <li>{@code SprintG} - sprinting in water without swimming.</li>
 * </ul>
 *
 * <p>Vanilla refuses most of these on its own, which is why the guards are a belt as well as
 * braces: they make the module's intent checkable against Grim's list instead of relying on the
 * client's rules happening to line up.
 *
 * <h2>Pausing for the other modules</h2>
 * KillAura+ and Scaffold+ each drive the tick for their own reasons - the aura aims and attacks
 * with the rotation the movement packet carries, and the scaffold places with the movement
 * prediction that packet implies. A sprint changes both. Rather than race them for the same tick,
 * the module stops claiming the key while either is active, which leaves the sprint they see to be
 * whatever the player asked for.
 */
public class SprintPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGuards = settings.createGroup("Guards");
    private final SettingGroup sgPause = settings.createGroup("Pause");

    private final Setting<Trigger> trigger = sgGeneral.add(new EnumSetting.Builder<Trigger>()
        .name("trigger")
        .description("Moving: sprint whenever any direction key is held, which also keeps a sprint alive while strafing. Forward: only while the forward key is held.")
        .defaultValue(Trigger.Moving)
        .build()
    );

    private final Setting<Boolean> hungerGuard = sgGuards.add(new BoolSetting.Builder()
        .name("hunger-guard")
        .description("Do not sprint with food below 7. Grim's SprintA carries setback = 0 here, so this is an immediate rubber-band rather than an alert. Skipped while flying is allowed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useGuard = sgGuards.add(new BoolSetting.Builder()
        .name("use-guard")
        .description("Do not sprint while using an item (Grim SprintC).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sneakGuard = sgGuards.add(new BoolSetting.Builder()
        .name("sneak-guard")
        .description("Do not sprint while sneaking or crawling (Grim SprintB).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blindGuard = sgGuards.add(new BoolSetting.Builder()
        .name("blind-guard")
        .description("Do not sprint while blind (Grim SprintD).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> wallGuard = sgGuards.add(new BoolSetting.Builder()
        .name("wall-guard")
        .description("Do not sprint while pushed against a wall (Grim SprintE).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> waterGuard = sgGuards.add(new BoolSetting.Builder()
        .name("water-guard")
        .description("Do not sprint in water unless actually swimming (Grim SprintG).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glideGuard = sgGuards.add(new BoolSetting.Builder()
        .name("glide-guard")
        .description("Do not sprint while gliding (Grim SprintF).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnKillAura = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-killaura")
        .description("Pause while KillAura+ is active, so the aura owns the tick it is aiming and attacking in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnScaffold = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-scaffold")
        .description("Pause while Scaffold+ is active, so a sprint cannot shift the movement prediction it places from.")
        .defaultValue(true)
        .build()
    );

    /** True while the sprint key is down because of this module, so it is the one that lifts it. */
    private boolean keyHeldByUs;

    public SprintPlus() {
        super(HongShiKaiKai.CATEGORY, "sprint+", "Presses the sprint key for you whenever sprinting is legal, with a guard for every sprint check Grim has.");
    }

    @Override
    public void onActivate() {
        keyHeldByUs = false;
    }

    @Override
    public void onDeactivate() {
        release();
    }

    /**
     * Head of the tick, before the player tick reads the key bindings - the same place a real key
     * press would already be visible.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        boolean want = wantsSprint() && legal() && !paused();

        if (want) {
            // Only claim the key if it is not already down. If the player is holding sprint
            // themselves the module leaves it alone and never lifts it on their behalf.
            if (!mc.options.sprintKey.isPressed()) {
                mc.options.sprintKey.setPressed(true);
                keyHeldByUs = true;
            }
        } else if (keyHeldByUs) {
            release();
        }
    }

    private void release() {
        mc.options.sprintKey.setPressed(false);
        keyHeldByUs = false;

        // A guard can trip for a reason the key alone cannot undo until vanilla next ticks, so stop
        // the sprint now rather than letting one more tick go out.
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    /** True while another module owns the tick and this one should keep its hands off the key. */
    private boolean paused() {
        if (pauseOnKillAura.get() && isModuleActive(KillAuraPlus.class)) return true;
        return pauseOnScaffold.get() && isModuleActive(ScaffoldPlus.class);
    }

    private static boolean isModuleActive(Class<? extends Module> type) {
        Module module = Modules.get().get(type);
        return module != null && module.isActive();
    }

    private boolean wantsSprint() {
        return switch (trigger.get()) {
            case Moving -> mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
            case Forward -> mc.options.forwardKey.isPressed();
        };
    }

    private boolean legal() {
        if (hungerGuard.get() && !mc.player.getAbilities().allowFlying && mc.player.getHungerManager().getFoodLevel() < 7) {
            return false;
        }

        if (useGuard.get() && mc.player.isUsingItem()) return false;
        if (sneakGuard.get() && mc.player.isSneaking()) return false;
        if (blindGuard.get() && mc.player.hasStatusEffect(StatusEffects.BLINDNESS)) return false;
        if (wallGuard.get() && mc.player.horizontalCollision) return false;
        if (waterGuard.get() && mc.player.isTouchingWater() && !mc.player.isSwimming()) return false;
        if (glideGuard.get() && mc.player.isGliding()) return false;

        return true;
    }

    /** Which keys ask for a sprint. */
    public enum Trigger {
        /** Any direction key. Keeps a sprint alive while strafing. */
        Moving,
        /** The forward key only, which is what vanilla starts a sprint from. */
        Forward
    }
}
