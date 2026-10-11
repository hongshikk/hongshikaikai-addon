package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.CrystalCompat;
import com.hongshikaikai.addon.utils.crystal.Animation;
import com.hongshikaikai.addon.utils.crystal.AnimateUtil;
import com.hongshikaikai.addon.utils.crystal.BlockPosX;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.ColorUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.Easing;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.ExplosionDamageUtils;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.MathUtil;
import com.hongshikaikai.addon.utils.crystal.PlayerEntityPredict;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.SwingSide;
import com.hongshikaikai.addon.utils.crystal.Timer;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.world.PacketMine;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Crystal aura: picks the best crystal break, placement or base place every tick by simulating the
 * explosion against predicted positions.
 *
 * <h2>What it does</h2>
 * Every {@code update-delay} milliseconds it picks the best crystal move available right now: the
 * enemy crystal worth breaking, the best cell to place a new crystal on, and - when {@code base-place}
 * is on - the obsidian to put underneath that cell first. The choice is made by simulating every
 * candidate explosion against every enemy's predicted position and against the player's own, and
 * keeping the one that hurts the enemy most without exceeding the self-damage, health-reserve and
 * balance limits.
 *
 * <h2>How it works here</h2>
 * <ul>
 *     <li>The options are Meteor setting groups - General, Rotation, Check, Base Place,
 *         Calculation, Misc and Render - and every option is visible under the group it belongs
 *         to.</li>
 *     <li>The search runs on the client tick, which is where every other Meteor combat module does
 *         its search.</li>
 *     <li>Meteor's line renderer has no per-shape width, so {@code line-width} is not offered.</li>
 *     <li>The systems it defers to are Meteor's or this addon's: {@code prefer-anchor} follows
 *         Meteor's AnchorAura, and the module's own {@code air-place}/{@code replace} switches stand
 *         in for an AirPlace module.</li>
 *     <li>Rotations are sent as look packets immediately before the interaction; see
 *         {@link com.hongshikaikai.addon.utils.crystal.RotationHelper}.</li>
 * </ul>
 */
public class CrystalAuraPlus extends Module {
    public static CrystalAuraPlus INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgCheck = settings.createGroup("Check");
    private final SettingGroup sgBase = settings.createGroup("Base Place");
    private final SettingGroup sgCalc = settings.createGroup("Calculation");
    private final SettingGroup sgMisc = settings.createGroup("Misc");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum damage a crystal has to deal to a target to be worth placing, in hearts.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Double> maxSelf = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage the module will accept dealing to you, in hearts.")
        .defaultValue(12.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Double> reserve = sgGeneral.add(new DoubleSetting.Builder()
        .name("reserve")
        .description("Health to keep in hand, in hearts. A placement that would eat into this is refused.")
        .defaultValue(2.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .build()
    );

    private final Setting<Boolean> balance = sgGeneral.add(new BoolSetting.Builder()
        .name("balance")
        .description("Prefer a placement that damages the target more than it damages you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> balanceOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("balance-offset")
        .description("How much more the target has to take than you for balance to accept the placement.")
        .defaultValue(0.0)
        .min(-20.0)
        .sliderRange(-20.0, 20.0)
        .visible(balance::get)
        .build()
    );

    private final Setting<Boolean> place = sgGeneral.add(new BoolSetting.Builder()
        .name("place")
        .description("Place crystals.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("How far a crystal may be placed from the eye, in blocks.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 6.0)
        .visible(place::get)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Milliseconds between two crystal placements.")
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 1000)
        .visible(place::get)
        .build()
    );

    private final Setting<SwapMode> autoSwap = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("auto-swap")
        .description("How to get a crystal into your hand. Silent and inventory never tell the server which slot is held.")
        .defaultValue(SwapMode.None)
        .visible(place::get)
        .build()
    );

    private final Setting<Boolean> afterBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("after-break")
        .description("Place the next crystal immediately after breaking one, before the normal delay has passed.")
        .defaultValue(true)
        .visible(place::get)
        .build()
    );

    private final Setting<Boolean> forcePlace = sgGeneral.add(new BoolSetting.Builder()
        .name("force-place")
        .description("Keep trying to place even while the break delay is running.")
        .defaultValue(false)
        .visible(place::get)
        .build()
    );

    private final Setting<Boolean> breakSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("break")
        .description("Break crystals.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> breakRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("How far a crystal may be from the eye to be broken, in blocks.")
        .defaultValue(4.0)
        .min(0.0)
        .sliderRange(0.0, 6.0)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Integer> breakDelay = sgGeneral.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Milliseconds between two crystal breaks.")
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 1000)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Integer> minAge = sgGeneral.add(new IntSetting.Builder()
        .name("min-age")
        .description("Ticks a crystal has to have existed before it may be broken.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 20)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Boolean> breakRemove = sgGeneral.add(new BoolSetting.Builder()
        .name("remove")
        .description("Remove the crystal client-side after breaking it, so it cannot be picked again this tick.")
        .defaultValue(false)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Boolean> onAdd = sgGeneral.add(new BoolSetting.Builder()
        .name("on-add")
        .description("Break a crystal the moment it appears on the cell the module is aiming at.")
        .defaultValue(false)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Boolean> resetCD = sgGeneral.add(new BoolSetting.Builder()
        .name("reset-attack")
        .description("Reset your attack cooldown after every crystal break, so a later hit is not slowed by it.")
        .defaultValue(true)
        .visible(breakSetting::get)
        .build()
    );

    private final Setting<Timing> timing = sgGeneral.add(new EnumSetting.Builder<Timing>()
        .name("timing")
        .description("Which part of the client tick the module acts on.")
        .defaultValue(Timing.All)
        .build()
    );

    private final Setting<Boolean> interactOnRender = sgGeneral.add(new BoolSetting.Builder()
        .name("interact-on-render")
        .description("Also run the interaction step every frame, not just every tick. Smoother, and heavier.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> wallRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("wall-range")
        .description("Within this distance a crystal may be attacked through a wall, in blocks.")
        .defaultValue(6.0)
        .min(0.0)
        .sliderRange(0.0, 6.0)
        .build()
    );

    private final Setting<SwingSide> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingSide>()
        .name("swing")
        .description("How the swing that accompanies a break is reported.")
        .defaultValue(SwingSide.All)
        .build()
    );

    // ---- Rotation ----

    public final Setting<Boolean> rotate = sgRotation.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Aim at what is being placed or broken.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onPlace = sgRotation.add(new BoolSetting.Builder()
        .name("on-place")
        .description("Aim before placing as well as before breaking.")
        .defaultValue(false)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> onBreak = sgRotation.add(new BoolSetting.Builder()
        .name("on-break")
        .description("Aim before breaking.")
        .defaultValue(false)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> yawStep = sgRotation.add(new BoolSetting.Builder()
        .name("yaw-step")
        .description("Turn towards the target gradually instead of snapping, and only act once the aim has arrived.")
        .defaultValue(false)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> whenElytra = sgRotation.add(new BoolSetting.Builder()
        .name("when-fall-flying")
        .description("Keep aiming while you are gliding.")
        .defaultValue(true)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Double> steps = sgRotation.add(new DoubleSetting.Builder()
        .name("steps")
        .description("How much of a half-turn the aim may take per tick while stepping. 0.05 is nine degrees.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Boolean> checkFov = sgRotation.add(new BoolSetting.Builder()
        .name("only-looking")
        .description("With yaw stepping on, hold the action until the aim is already close to the target.")
        .defaultValue(true)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Double> fov = sgRotation.add(new DoubleSetting.Builder()
        .name("fov")
        .description("How close the aim has to be, in degrees, for the action to go ahead.")
        .defaultValue(20.0)
        .min(0.0)
        .sliderRange(0.0, 360.0)
        .decimalPlaces(1)
        .visible(() -> rotate.get() && yawStep.get() && checkFov.get())
        .build()
    );

    // ---- Check ----

    private final Setting<Keybind> pause = sgCheck.add(new KeybindSetting.Builder()
        .name("pause")
        .description("Hold to pause the module.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> preferAnchor = sgCheck.add(new BoolSetting.Builder()
        .name("prefer-anchor")
        .description("Stand down while Meteor's AnchorAura is active, so the two do not fight over the same cell.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakOnlyHasCrystal = sgCheck.add(new BoolSetting.Builder()
        .name("only-hold")
        .description("Do nothing unless you are holding a crystal or one is reachable through auto-swap.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> eatingPause = sgCheck.add(new BoolSetting.Builder()
        .name("eating-pause")
        .description("Pause while you are using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchCooldown = sgCheck.add(new IntSetting.Builder()
        .name("switch-pause")
        .description("Milliseconds to wait after a hotbar switch before acting, in milliseconds.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Double> targetRange = sgCheck.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player may be and still be considered a target, in blocks.")
        .defaultValue(12.0)
        .min(0.0)
        .sliderRange(0.0, 20.0)
        .build()
    );

    private final Setting<Integer> updateDelay = sgCheck.add(new IntSetting.Builder()
        .name("update-delay")
        .description("Milliseconds between two searches for the best crystal move, in milliseconds.")
        .defaultValue(50)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    // ---- Base place ----

    private final Setting<Boolean> basePlace = sgBase.add(new BoolSetting.Builder()
        .name("base-place")
        .description("Place the obsidian and the crystal on top of it in one move.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> baseMin = sgBase.add(new DoubleSetting.Builder()
        .name("base-min")
        .description("Minimum damage a base-place crystal has to deal, in hearts.")
        .defaultValue(6.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Double> baseMax = sgBase.add(new DoubleSetting.Builder()
        .name("base-max")
        .description("Most self-damage a base-place crystal may deal, in hearts.")
        .defaultValue(12.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Double> overrideMax = sgBase.add(new DoubleSetting.Builder()
        .name("max-override")
        .description("Base placement is abandoned once an ordinary placement this good exists anyway.")
        .defaultValue(8.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .build()
    );

    private final Setting<Boolean> baseBalance = sgBase.add(new BoolSetting.Builder()
        .name("base-balance")
        .description("Require a base placement to hurt the target more than you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyBelow = sgBase.add(new BoolSetting.Builder()
        .name("only-below")
        .description("Only base-place on cells below the target, where the crystal cannot be walked away from.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventory = sgBase.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the obsidian from your inventory rather than the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectMining = sgBase.add(new BoolSetting.Builder()
        .name("detect-mining")
        .description("Do not base-place onto a cell that is already being mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgBase.add(new IntSetting.Builder()
        .name("base-delay")
        .description("Milliseconds between two base placements, in milliseconds.")
        .defaultValue(3000)
        .min(0)
        .sliderRange(0, 10000)
        .build()
    );

    // ---- Calculation ----

    private final Setting<Double> attackVecStep = sgCalc.add(new DoubleSetting.Builder()
        .name("attack-vec-step")
        .description("Granularity of the search for the nearest reachable point on a crystal's hitbox.")
        .defaultValue(0.1)
        .min(0.01)
        .sliderRange(0.01, 1.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> doCrystal = sgCalc.add(new BoolSetting.Builder()
        .name("interact-in-calc")
        .description("Act from inside the search itself rather than after it. Stops one tick sooner.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> selfPredict = sgCalc.add(new IntSetting.Builder()
        .name("self-predict")
        .description("Ticks of your own movement to simulate when costing a placement.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> predictTicks = sgCalc.add(new IntSetting.Builder()
        .name("predict")
        .description("Ticks of target movement to simulate when costing a placement.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> simulation = sgCalc.add(new IntSetting.Builder()
        .name("simulation")
        .description("Resolution of the movement simulation. Higher is more accurate and more expensive.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Double> maxMotionY = sgCalc.add(new DoubleSetting.Builder()
        .name("max-motion-y")
        .description("Vertical motion is clamped to this before predicting, so a jump arc is not overread.")
        .defaultValue(0.34)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> step = sgCalc.add(new BoolSetting.Builder()
        .name("step")
        .description("Let the prediction walk up a block.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doubleStep = sgCalc.add(new BoolSetting.Builder()
        .name("double-step")
        .description("Let the prediction walk up two blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> jump = sgCalc.add(new BoolSetting.Builder()
        .name("jump")
        .description("Let the prediction jump.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inBlockPause = sgCalc.add(new BoolSetting.Builder()
        .name("in-block-pause")
        .description("Do not predict at all while you are standing inside a block.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> terrainIgnore = sgCalc.add(new BoolSetting.Builder()
        .name("terrain-ignore")
        .description("Cost the explosion as if every block that is not obsidian, bedrock or an anvil were absent.")
        .defaultValue(true)
        .build()
    );

    // ---- Misc ----

    private final Setting<Boolean> ignoreMine = sgMisc.add(new BoolSetting.Builder()
        .name("ignore-mine")
        .description("Treat the block you are mining as already gone when costing damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> constantProgress = sgMisc.add(new DoubleSetting.Builder()
        .name("progress")
        .description("How far through a mine has to be before ignore-mine applies, in percent.")
        .defaultValue(90.0)
        .min(0.0)
        .sliderRange(0.0, 100.0)
        .visible(ignoreMine::get)
        .build()
    );

    private final Setting<Boolean> antiSurround = sgMisc.add(new BoolSetting.Builder()
        .name("anti-surround")
        .description("When a surrounding block is nearly mined, place into the hole it is about to leave.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> miningProgress = sgMisc.add(new DoubleSetting.Builder()
        .name("mining-progress")
        .description("How far through that mine has to be, in percent.")
        .defaultValue(90.0)
        .min(0.0)
        .sliderRange(0.0, 100.0)
        .visible(antiSurround::get)
        .build()
    );

    private final Setting<Double> antiSurroundMax = sgMisc.add(new DoubleSetting.Builder()
        .name("when-lower")
        .description("Only take the anti-surround opening when the current best placement is worse than this.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .visible(antiSurround::get)
        .build()
    );

    private final Setting<Boolean> slowPlace = sgMisc.add(new BoolSetting.Builder()
        .name("timeout")
        .description("After a break goes unanswered, fall back to a lower damage requirement instead of stopping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> slowDelay = sgMisc.add(new IntSetting.Builder()
        .name("timeout-delay")
        .description("Milliseconds without a break before the fallback applies, in milliseconds.")
        .defaultValue(600)
        .min(0)
        .sliderRange(0, 2000)
        .visible(slowPlace::get)
        .build()
    );

    private final Setting<Double> slowMinDamage = sgMisc.add(new DoubleSetting.Builder()
        .name("timeout-min")
        .description("Damage requirement while the fallback is active, in hearts.")
        .defaultValue(1.5)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .visible(slowPlace::get)
        .build()
    );

    private final Setting<Boolean> lethalOverride = sgMisc.add(new BoolSetting.Builder()
        .name("lethal-override")
        .description("Accept a lower-damage placement when the target is nearly dead.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> forceMaxHealth = sgMisc.add(new DoubleSetting.Builder()
        .name("lower-than")
        .description("Health below which the lethal override applies, in health points.")
        .defaultValue(7.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .visible(lethalOverride::get)
        .build()
    );

    private final Setting<Double> forceMin = sgMisc.add(new DoubleSetting.Builder()
        .name("force-min")
        .description("Damage requirement while the lethal override is active, in hearts.")
        .defaultValue(1.5)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .visible(lethalOverride::get)
        .build()
    );

    private final Setting<Boolean> armorBreaker = sgMisc.add(new BoolSetting.Builder()
        .name("armor-breaker")
        .description("Accept a lower-damage placement against a target whose armour is nearly gone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxDurable = sgMisc.add(new IntSetting.Builder()
        .name("max-durable")
        .description("Armour durability below which the armour breaker applies, in percent.")
        .defaultValue(8)
        .min(0)
        .sliderRange(0, 100)
        .visible(armorBreaker::get)
        .build()
    );

    private final Setting<Double> armorBreakerDamage = sgMisc.add(new DoubleSetting.Builder()
        .name("breaker-min")
        .description("Damage requirement while the armour breaker is active, in hearts.")
        .defaultValue(3.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .visible(armorBreaker::get)
        .build()
    );

    private final Setting<Boolean> forceWeb = sgMisc.add(new BoolSetting.Builder()
        .name("web-reset")
        .description("Allow the two options below: placing into a cobweb, and placing against nothing at all.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> airPlace = sgMisc.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Place a block against nothing, which is what a cobweb reset needs.")
        .defaultValue(false)
        .visible(forceWeb::get)
        .build()
    );

    public final Setting<Boolean> replace = sgMisc.add(new BoolSetting.Builder()
        .name("replace")
        .description("Treat a cobweb as replaceable, so a crystal can go straight into it.")
        .defaultValue(false)
        .visible(forceWeb::get)
        .build()
    );

    private final Setting<Double> hurtTime = sgMisc.add(new DoubleSetting.Builder()
        .name("hurt-time")
        .description("Ignore targets that were hit more recently than this many ticks ago.")
        .defaultValue(10.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .decimalPlaces(0)
        .build()
    );

    private final Setting<Double> waitHurt = sgMisc.add(new DoubleSetting.Builder()
        .name("wait-hurt")
        .description("Wait for the target's own hurt flash to end before breaking, in ticks.")
        .defaultValue(10.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .decimalPlaces(0)
        .build()
    );

    private final Setting<Double> syncTimeout = sgMisc.add(new DoubleSetting.Builder()
        .name("wait-timeout")
        .description("How long to keep waiting for that flash before breaking anyway, in milliseconds.")
        .defaultValue(500.0)
        .min(0.0)
        .sliderRange(0.0, 2000.0)
        .decimalPlaces(0)
        .build()
    );

    private final Setting<Keybind> manualMine = sgMisc.add(new KeybindSetting.Builder()
        .name("manual-mine")
        .description("Hold to disable the timeout and lethal fallbacks while it is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ---- Render ----

    private final Setting<TargetEsp> mode = sgRender.add(new EnumSetting.Builder<TargetEsp>()
        .name("target-esp")
        .description("How the current target is drawn.")
        .defaultValue(TargetEsp.Fill)
        .build()
    );

    private final Setting<Integer> animationTime = sgRender.add(new IntSetting.Builder()
        .name("animation-time")
        .description("Milliseconds the target highlight takes to travel between its normal and hit colours.")
        .defaultValue(200)
        .min(0)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<Easing> ease = sgRender.add(new EnumSetting.Builder<Easing>()
        .name("ease")
        .description("Curve of that colour animation.")
        .defaultValue(Easing.CubicInOut)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Fill colour of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("target-outline-color")
        .description("Outline colour of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );

    private final Setting<SettingColor> hitColor = sgRender.add(new ColorSetting.Builder()
        .name("hit-color")
        .description("Fill colour the target box flashes to on a hit.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build()
    );

    private final Setting<SettingColor> hitOutlineColor = sgRender.add(new ColorSetting.Builder()
        .name("hit-outline-color")
        .description("Outline colour the target box flashes to on a hit.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the cell a crystal is about to be placed on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sync = sgRender.add(new BoolSetting.Builder()
        .name("sync")
        .description("Move the drawn cell to the last cell actually interacted with, instead of the next one.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> shrink = sgRender.add(new BoolSetting.Builder()
        .name("shrink")
        .description("Shrink the drawn cell as it fades out instead of keeping it full size.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Draw the outline of the cell.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> boxColor = sgRender.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Colour of that outline.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> render.get() && box.get())
        .build()
    );

    private final Setting<Boolean> fill = sgRender.add(new BoolSetting.Builder()
        .name("fill")
        .description("Fill the cell.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Colour of that fill.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .visible(() -> render.get() && fill.get())
        .build()
    );

    private final Setting<Double> sliderSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("slider-speed")
        .description("How fast the drawn cell slides from the old placement to the new one.")
        .defaultValue(0.2)
        .min(0.01)
        .sliderRange(0.01, 1.0)
        .decimalPlaces(2)
        .visible(render::get)
        .build()
    );

    private final Setting<Double> startFade = sgRender.add(new DoubleSetting.Builder()
        .name("start-fade")
        .description("Seconds a placement stays drawn before it starts to fade.")
        .defaultValue(0.3)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .visible(render::get)
        .build()
    );

    private final Setting<Double> fadeSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("fade-speed")
        .description("How fast it fades once it starts.")
        .defaultValue(0.2)
        .min(0.01)
        .sliderRange(0.01, 1.0)
        .decimalPlaces(2)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> text = sgRender.add(new BoolSetting.Builder()
        .name("damage-text")
        .description("Draw the damage of the last placement above the cell.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> textColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-text-color")
        .description("Colour of that text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> render.get() && text.get())
        .build()
    );

    // ---- State ----

    /** The cell the module is currently trying to put a crystal on. */
    public BlockPos crystalPos;
    public final Timer lastBreakTimer = new Timer();
    final Animation animation = new Animation();
    final DecimalFormat df = new DecimalFormat("0.0");

    private final Timer baseTimer = new Timer();
    private final Timer placeTimer = new Timer();
    private final Timer noPosTimer = new Timer();
    private final Timer switchTimer = new Timer();
    private final Timer calcDelay = new Timer();
    private final Timer syncTimer = new Timer();

    public PlayerEntity displayTarget;
    public float breakDamage;
    public float tempDamage;
    public float lastDamage;
    double currentFade = 0.0;

    private EndCrystalEntity tempBreakCrystal;
    private EndCrystalEntity breakCrystal;
    private BlockPos tempPos;
    private BlockPos syncPos;
    private Vec3d placeVec3d;
    private Vec3d curVec3d;
    int lastSlot;
    BlockPos tempBasePos;
    BlockPos basePos;

    /** Scratch vector for the 2D damage text; reused so the render path allocates nothing. */
    private final Vector3d textPos = new Vector3d();

    public CrystalAuraPlus() {
        super(
            HongShiKaiKai.CATEGORY,
            "crystalAura+",
            "Picks the best crystal break, placement or base place every tick by simulating the explosion against predicted positions."
        );
        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        INSTANCE = this;
        crystalPos = null;
        tempPos = null;
        tempBreakCrystal = null;
        displayTarget = null;
        syncTimer.reset();
        lastBreakTimer.reset();
        RotationHelper.reset();
    }

    @Override
    public void onDeactivate() {
        crystalPos = null;
        tempPos = null;
        displayTarget = null;
        CombatUtil.clearOverride();
    }

    @Override
    public String getInfoString() {
        return displayTarget != null && lastDamage > 0.0f ? df.format(lastDamage) : null;
    }

    /** Settings read by the shared block helpers; null-safe so the helpers work with the module off. */
    public static boolean replaceCobweb() {
        return INSTANCE != null && INSTANCE.forceWeb.get() && INSTANCE.replace.get();
    }

    public static boolean airPlaceEnabled() {
        return INSTANCE != null && INSTANCE.forceWeb.get() && INSTANCE.airPlace.get();
    }

    // ---- Tick ----

    private boolean nullCheck() {
        return mc.player == null || mc.world == null;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        // Clear the placed-cell list once per tick, before the search runs.
        BlockUtil.placedPos.clear();

        if (timing.get() == Timing.Post) return;
        runTick();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (timing.get() != Timing.Post) return;
        runTick();
    }

    private void runTick() {
        if (nullCheck()) return;

        updateCrystalPos();

        if (shouldReturn()) return;

        doInteract();

        BlockPos base = basePos;
        if (basePlace.get() && base != null && BlockUtil.canPlace(base)) {
            doPlace(base);
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket packet && lastSlot != packet.getSelectedSlot()) {
            lastSlot = packet.getSelectedSlot();
            switchTimer.reset();
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (onAdd.get()
            && event.entity instanceof EndCrystalEntity crystal
            && syncPos != null
            && crystal.getBlockPos().equals(syncPos)) {
            doBreak(crystal);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (interactOnRender.get() && !nullCheck() && !shouldReturn()) {
            doInteract();

            BlockPos base = basePos;
            if (basePlace.get() && base != null && BlockUtil.canPlace(base)) {
                doPlace(base);
            }
        }

        renderPlacement(event);

        if (displayTarget != null && !noPosTimer.passed(500L)) {
            renderTarget(event);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!render.get() || !text.get() || lastDamage <= 0.0f || curVec3d == null) return;
        if (noPosTimer.passed((long) (startFade.get() * 1000.0))) return;

        textPos.set(curVec3d.x, curVec3d.y + 0.6, curVec3d.z);
        if (!NametagUtils.to2D(textPos, 1.0)) return;

        String damage = df.format(lastDamage);
        TextRenderer renderer = TextRenderer.get();

        NametagUtils.begin(textPos);
        renderer.begin(1.0);
        renderer.render(damage, -renderer.getWidth(damage) / 2.0, 0, textColor.get(), true);
        renderer.end();
        NametagUtils.end();
    }

    // ---- Search ----

    private void updateCrystalPos() {
        if (!calcDelay.passedMs(updateDelay.get())) return;

        calcDelay.reset();
        calcCrystalPos();
        CombatUtil.clearOverride();

        basePos = tempBasePos;
        lastDamage = tempDamage;
        breakCrystal = tempBreakCrystal;
        crystalPos = tempPos;
    }

    /**
     * Whether the module should do nothing right now.
     *
     * <p>Every refusal also resets the break timer, so the timeout fallback measures time since the
     * last real opportunity rather than since the last break.
     */
    private boolean shouldReturn() {
        if (nullCheck()) return true;

        if (eatingPause.get() && mc.player.isUsingItem()) {
            lastBreakTimer.reset();
            return true;
        }

        if (preferAnchor.get() && Modules.get().isActive(AnchorAura.class)) {
            lastBreakTimer.reset();
            return true;
        }

        if (pause.get().isPressed()) {
            lastBreakTimer.reset();
            return true;
        }

        return false;
    }

    /**
     * The search: every enemy crystal worth breaking, then every cell worth placing on, then the
     * anti-surround opening.
     */
    private void calcCrystalPos() {
        if (nullCheck()) return;

        if (breakOnlyHasCrystal.get()
            && !mc.player.getMainHandStack().getItem().equals(Items.END_CRYSTAL)
            && !mc.player.getOffHandStack().getItem().equals(Items.END_CRYSTAL)
            && !hasCrystal()) {
            tempPos = null;
            tempBreakCrystal = null;
            lastBreakTimer.reset();
            return;
        }

        boolean shouldReturn = shouldReturn();
        boolean needBasePlace = basePlace.get() && baseTimer.passedMs(delay.get()) && getBlock() != -1;

        tempBreakCrystal = null;
        breakDamage = 0.0f;
        tempPos = null;
        tempDamage = 0.0f;
        tempBasePos = null;
        float baseDamage = 0.0f;

        List<PlayerEntityPredict> list = new ArrayList<>();
        for (PlayerEntity target : CombatUtil.getEnemies(targetRange.get())) {
            if (target.hurtTime <= hurtTime.get()) {
                list.add(newPredict(target, predictTicks.get()));
            }
        }

        PlayerEntityPredict self = newPredict(mc.player, selfPredict.get());

        if (list.isEmpty()) {
            lastBreakTimer.reset();
            return;
        }

        // 1. Existing crystals to break.
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal) || entity.age < minAge.get()) continue;

            Vec3d attackVec = getAttackVec(EntityUtil.pos(crystal));
            if (attackVec == null) continue;
            if (!mc.player.canSee(crystal) && mc.player.getEyePos().distanceTo(attackVec) > wallRange.get()) continue;

            float selfDamage = calculateDamage(EntityUtil.pos(crystal), self.player, self);

            for (PlayerEntityPredict pap : list) {
                float damage = calculateDamage(EntityUtil.pos(crystal), pap.player, pap);
                if (damage > breakDamage
                    && !(selfDamage > maxSelf.get())
                    && !(reserve.get() > 0.0 && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount() - reserve.get())
                    && (damage >= EntityUtil.getHealth(pap.player)
                        || (damage >= getDamage(pap.player)
                            && (!balance.get()
                                || (getDamage(pap.player) == forceMin.get()
                                    ? damage >= selfDamage - 2.5
                                    : damage >= selfDamage + balanceOffset.get()))))) {
                    breakDamage = damage;
                    tempBreakCrystal = crystal;
                    displayTarget = pap.player;
                }
            }
        }

        if (doCrystal.get() && tempBreakCrystal != null && !shouldReturn) {
            doBreak(tempBreakCrystal);
            tempBreakCrystal = null;
        }

        // 2. Cells to place on.
        for (BlockPos pos : BlockUtil.getSphere((float) (double) breakRange.get() + 1.5f)) {
            boolean base = false;
            CombatUtil.clearOverride();

            if (needBasePlace && BlockUtil.canPlace(pos.down())) {
                CombatUtil.modifyPos = pos.down();
                CombatUtil.modifyBlockState = Blocks.OBSIDIAN.getDefaultState();
                base = true;
            }

            if (base && detectMining.get() && isMining(pos.down())) {
                continue;
            }

            Vec3d attackVec = getAttackVec(pos.toBottomCenterPos());
            if (attackVec == null || behindWall(pos, attackVec) || !canTouch(pos.down()) || !canPlaceCrystal(pos, true, false)) {
                continue;
            }

            float selfDamage = base
                ? calculateBaseDamage(pos, self.player, self)
                : calculateDamage(pos, self.player, self);

            for (PlayerEntityPredict pap : list) {
                if (base && onlyBelow.get() && pos.getY() - 0.5 > pap.player.getY()) continue;

                float damage = base
                    ? calculateBaseDamage(pos, pap.player, pap)
                    : calculateDamage(pos, pap.player, pap);

                if (base) {
                    if (tempDamage <= overrideMax.get()
                        && damage > tempDamage
                        && damage > baseDamage
                        && !(selfDamage > baseMax.get())
                        && !(reserve.get() > 0.0 && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount() - reserve.get())
                        && (damage >= EntityUtil.getHealth(pap.player)
                            || (damage >= baseMin.get() && (!baseBalance.get() || damage >= selfDamage)))) {
                        displayTarget = pap.player;
                        baseDamage = damage;
                        tempBasePos = pos.down();
                        tempPos = null;
                    }
                } else if (damage > tempDamage
                    && (damage >= baseDamage || tempDamage > overrideMax.get())
                    && !(selfDamage > maxSelf.get())
                    && !(reserve.get() > 0.0 && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount() - reserve.get())
                    && (damage >= EntityUtil.getHealth(pap.player)
                        || (damage >= getDamage(pap.player)
                            && (balance.get()
                                ? (getDamage(pap.player) == forceMin.get()
                                    ? damage >= selfDamage - 2.5
                                    : damage >= selfDamage + balanceOffset.get())
                                : true)))) {
                    displayTarget = pap.player;
                    tempPos = pos;
                    tempBasePos = null;
                    tempDamage = damage;
                }
            }
        }

        CombatUtil.clearOverride();

        // 3. The anti-surround opening.
        if (antiSurround.get()) {
            BlockPos breakPos = miningPos();
            double progress = miningProgressValue();

            if (breakPos != null
                && progress >= miningProgress.get() / 100.0
                && !BlockUtil.hasEntity(breakPos, false)
                && tempDamage <= antiSurroundMax.get()) {
                for (PlayerEntityPredict pap : list) {
                    BlockPos pos = new BlockPosX(EntityUtil.pos(pap.player).add(0.0, 0.5, 0.0));
                    if (BlockUtil.canCollide(pap.player, new Box(pos))) continue;

                    for (Direction i : Direction.values()) {
                        if (i == Direction.DOWN || i == Direction.UP) continue;

                        BlockPos offsetPos = pos.offset(i);
                        if (!offsetPos.equals(breakPos)) continue;

                        for (Direction direction : Direction.values()) {
                            if (direction == Direction.DOWN || direction == Direction.UP) continue;

                            BlockPos crystalCell = offsetPos.offset(direction);
                            if (!canPlaceCrystal(crystalCell, false, false)) continue;

                            float selfDamage = calculateDamage(crystalCell, self.player, self);
                            if (selfDamage < maxSelf.get()
                                && !(reserve.get() > 0.0 && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount() - reserve.get())) {
                                tempPos = crystalCell;

                                if (doCrystal.get() && !shouldReturn) {
                                    doCrystal(tempPos);
                                }

                                return;
                            }
                        }
                    }
                }
            }
        }

        if (doCrystal.get() && tempPos != null && !shouldReturn) {
            doCrystal(tempPos);
        }
    }

    private PlayerEntityPredict newPredict(PlayerEntity player, int ticks) {
        return new PlayerEntityPredict(
            player,
            maxMotionY.get(),
            ticks,
            simulation.get(),
            step.get(),
            doubleStep.get(),
            jump.get(),
            inBlockPause.get()
        );
    }

    // ---- Crystal placement / breaking ----

    public boolean canPlaceCrystal(BlockPos pos, boolean ignoreCrystal, boolean ignoreItem) {
        BlockPos obsPos = pos.down();
        BlockPos boost = obsPos.up();
        BlockPos boost2 = boost.up();
        return (BlockUtil.getBlock(obsPos) == Blocks.BEDROCK || BlockUtil.getBlock(obsPos) == Blocks.OBSIDIAN)
            && BlockUtil.getClickSideStrict(obsPos) != null
            && noEntityBlockCrystal(boost, ignoreCrystal, ignoreItem)
            && noEntityBlockCrystal(boost2, ignoreCrystal, ignoreItem)
            && (mc.world.isAir(boost) || (BlockUtil.hasCrystal(boost) && BlockUtil.getBlock(boost) == Blocks.FIRE))
            && (!com.hongshikaikai.addon.utils.crystal.CrystalConfig.lowVersion || mc.world.isAir(boost2));
    }

    private boolean noEntityBlockCrystal(BlockPos pos, boolean ignoreCrystal, boolean ignoreItem) {
        for (Entity entity : BlockUtil.getEntities(new Box(pos))) {
            if (entity.isAlive()
                && (!(entity instanceof ItemEntity) || !ignoreItem)
                && (!(entity instanceof EndCrystalEntity)
                    || !ignoreCrystal
                    || getAttackVec(EntityUtil.pos(entity)) == null
                    || (!mc.player.canSee(entity) && !(mc.player.getEyePos().distanceTo(EntityUtil.pos(entity)) <= wallRange.get())))) {
                return false;
            }
        }

        return true;
    }

    public boolean behindWall(BlockPos pos, Vec3d attackVec) {
        Vec3d crystalEyePos = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.7, pos.getZ() + 0.5);
        HitResult result = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(), crystalEyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        ));
        return result != null && result.getType() != HitResult.Type.MISS && mc.player.getEyePos().distanceTo(attackVec) > wallRange.get();
    }

    private boolean canTouch(BlockPos pos) {
        Direction side = BlockUtil.getClickSideStrict(pos);
        return side != null
            && pos.toCenterPos()
                .add(new Vec3d(side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5))
                .distanceTo(mc.player.getEyePos())
            <= placeRange.get();
    }

    private void doInteract() {
        BlockPos crystal = crystalPos;
        if (crystal != null) {
            doCrystal(crystal);
        }

        if (breakCrystal != null) {
            doBreak(breakCrystal);
            breakCrystal = null;
        }
    }

    private void doCrystal(BlockPos pos) {
        if (canPlaceCrystal(pos, false, false)) {
            doPlace(pos, rotate.get() && onPlace.get());
        }

        doBreak(pos);
    }

    /** Places a base block: the obsidian under a cell that is about to take a crystal. */
    private void doPlace(BlockPos pos) {
        noPosTimer.reset();
        if (!baseTimer.passed(delay.get())) return;
        if (detectMining.get() && isMining(pos)) return;

        int block = getBlock();
        if (block == -1) return;

        int old = EntityUtil.selectedSlot();
        baseSwap(block);
        BlockUtil.placeBlock(pos, rotate.get());
        if (inventory.get()) {
            baseSwap(block);
            EntityUtil.syncInventory();
        } else {
            baseSwap(old);
        }

        baseTimer.reset();
    }

    /** Places a crystal on {@code pos}, swapping one into the hand if needed. */
    private void doPlace(BlockPos pos, boolean rotate) {
        noPosTimer.reset();
        if (!place.get()) return;

        boolean holdingCrystal = mc.player.getMainHandStack().getItem().equals(Items.END_CRYSTAL)
            || mc.player.getOffHandStack().getItem().equals(Items.END_CRYSTAL);

        if (!holdingCrystal && !hasCrystal()) return;
        if (!canTouch(pos.down())) return;

        BlockPos obsPos = pos.down();
        Direction facing = BlockUtil.getClickSide(obsPos);
        Vec3d vec = obsPos.toCenterPos().add(facing.getVector().getX() * 0.5, facing.getVector().getY() * 0.5, facing.getVector().getZ() * 0.5);
        if (facing != Direction.UP && facing != Direction.DOWN) {
            vec = vec.add(0.0, 0.45, 0.0);
        }

        boolean stepped = shouldYawStep();
        if (!stepped && !placeTimer.passed(placeDelay.get())) return;
        if (rotate && !faceVector(vec)) return;
        if (!placeTimer.passed(placeDelay.get())) return;

        if (!holdingCrystal) {
            placeTimer.reset();
            syncPos = pos;

            int crystal = getCrystal();
            if (crystal == -1) return;

            int old = EntityUtil.selectedSlot();
            doSwap(crystal);
            placeCrystal(pos);

            if (autoSwap.get() == SwapMode.Silent) {
                doSwap(old);
            } else if (autoSwap.get() == SwapMode.Inventory) {
                doSwap(crystal);
                EntityUtil.syncInventory();
            }
        } else {
            placeTimer.reset();
            syncPos = pos;
            placeCrystal(pos);
        }
    }

    private void doSwap(int slot) {
        if (autoSwap.get() == SwapMode.Silent || autoSwap.get() == SwapMode.Normal) {
            InventoryUtil.switchToSlot(slot);
        } else if (autoSwap.get() == SwapMode.Inventory) {
            InventoryUtil.inventorySwap(slot, EntityUtil.selectedSlot());
        }
    }

    private void baseSwap(int slot) {
        if (!inventory.get()) {
            InventoryUtil.switchToSlot(slot);
        } else {
            InventoryUtil.inventorySwap(slot, EntityUtil.selectedSlot());
        }
    }

    private int getBlock() {
        return inventory.get() ? InventoryUtil.findBlockInventorySlot(Blocks.OBSIDIAN) : InventoryUtil.findBlock(Blocks.OBSIDIAN);
    }

    private int getCrystal() {
        if (autoSwap.get() == SwapMode.Silent || autoSwap.get() == SwapMode.Normal) {
            return InventoryUtil.findItem(Items.END_CRYSTAL);
        }

        return autoSwap.get() == SwapMode.Inventory ? InventoryUtil.findItemInventorySlot(Items.END_CRYSTAL) : -1;
    }

    private void placeCrystal(BlockPos pos) {
        boolean offhand = mc.player.getOffHandStack().getItem() == Items.END_CRYSTAL;
        BlockPos obsPos = pos.down();
        Direction facing = BlockUtil.getClickSide(obsPos);
        BlockUtil.clickBlock(obsPos, facing, false, offhand ? Hand.OFF_HAND : Hand.MAIN_HAND, swingMode.get());
    }

    public boolean hasCrystal() {
        return autoSwap.get() != SwapMode.None && getCrystal() != -1;
    }

    /** Breaks the crystal standing on {@code pos}, if there is one. */
    private void doBreak(BlockPos pos) {
        noPosTimer.reset();
        if (!breakSetting.get()) return;

        if (displayTarget != null && displayTarget.hurtTime > waitHurt.get() && !syncTimer.passedMs(syncTimeout.get())) return;

        lastBreakTimer.reset();
        if (!switchTimer.passed(switchCooldown.get())) return;

        syncTimer.reset();

        for (EndCrystalEntity entity : BlockUtil.getEndCrystals(
            new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1)
        )) {
            if (entity.age < minAge.get() || !entity.isAlive()) continue;

            // Never try a second crystal after the first one: every path through the
            // loop body ends in a return, including the ones that only force-place instead.
            breakCrystalEntity(entity, pos);
            return;
        }

        if (forcePlace.get() && crystalPos != null) {
            doPlace(crystalPos, false);
        }
    }

    /** Breaks a specific crystal entity. */
    private void doBreak(EndCrystalEntity entity) {
        noPosTimer.reset();
        if (!breakSetting.get() || !entity.isAlive()) return;

        if (displayTarget != null && displayTarget.hurtTime > waitHurt.get() && !syncTimer.passedMs(syncTimeout.get())) return;

        lastBreakTimer.reset();
        if (!switchTimer.passed(switchCooldown.get())) return;

        syncTimer.reset();
        if (entity.age < minAge.get()) return;

        breakCrystalEntity(entity, entity.getBlockPos());
    }

    /**
     * The shared break: rotation gate, packet, swing and the after-break placement.
     *
     * @return true when the break was actually sent
     */
    private boolean breakCrystalEntity(EndCrystalEntity entity, BlockPos pos) {
        boolean stepped = shouldYawStep();

        if (!stepped && !CombatUtil.breakTimer.passed(breakDelay.get())) {
            if (forcePlace.get() && crystalPos != null) doPlace(crystalPos, false);
            return false;
        }

        if (rotate.get() && onBreak.get()) {
            Vec3d attackVec = getAttackVec(EntityUtil.pos(entity));
            if (!faceVector(attackVec == null ? EntityUtil.pos(entity) : attackVec)) {
                if (forcePlace.get() && crystalPos != null) doPlace(crystalPos, false);
                return false;
            }
        }

        if (stepped && !CombatUtil.breakTimer.passed(breakDelay.get())) {
            if (forcePlace.get() && crystalPos != null) doPlace(crystalPos, false);
            return false;
        }

        animation.to = 1.0;
        animation.from = 1.0;
        CombatUtil.breakTimer.reset();
        syncPos = pos;

        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
        if (resetCD.get()) {
            CrystalCompat.resetAttackCooldown(mc.player);
        }

        EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());

        if (breakRemove.get()) {
            mc.world.removeEntity(entity.getId(), Entity.RemovalReason.KILLED);
        }

        BlockPos crystal = crystalPos;
        if (crystal != null
            && displayTarget != null
            && lastDamage >= getDamage(displayTarget)
            && afterBreak.get()
            && (!rotate.get() || !stepped || !checkFov.get() || RotationHelper.inFov(EntityUtil.pos(entity), fov.get().floatValue()))) {
            doPlace(crystal, false);
        }

        return true;
    }

    // ---- Damage ----

    public float calculateDamage(BlockPos pos, PlayerEntity player, PlayerEntityPredict predict) {
        return calculateDamage(new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), player, predict);
    }

    public float calculateDamage(Vec3d pos, PlayerEntity player, PlayerEntityPredict predict) {
        BlockPos breakPos = miningPos();

        if (ignoreMine.get()
            && breakPos != null
            && mc.player.getEyePos().distanceTo(breakPos.toCenterPos()) <= packetMineRange()
            && miningProgressValue() >= constantProgress.get() / 100.0) {
            CombatUtil.modifyPos = breakPos;
            CombatUtil.modifyBlockState = Blocks.AIR.getDefaultState();
        }

        if (terrainIgnore.get()) {
            CombatUtil.terrainIgnore = true;
        }

        float damage = ExplosionDamageUtils.calculateDamage(pos, player, predict, 6.0f);
        CombatUtil.clearOverride();
        CombatUtil.terrainIgnore = false;
        return damage;
    }

    /**
     * Damage of a base place, costed with the obsidian already accounted for.
     *
     * <p>The obsidian is not there yet, so the hit factory is told to pretend it is: that is what
     * stops the module from choosing a cell whose crystal the obsidian itself would shield.
     */
    public float calculateBaseDamage(BlockPos pos, PlayerEntity player, PlayerEntityPredict predict) {
        if (terrainIgnore.get()) {
            CombatUtil.terrainIgnore = true;
        }

        float damage = ExplosionDamageUtils.overridingExplosionDamage(
            player,
            predict,
            new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
            12.0f,
            pos.down(),
            Blocks.OBSIDIAN.getDefaultState()
        );
        CombatUtil.terrainIgnore = false;
        return damage;
    }

    /**
     * The damage a placement has to beat against one target, which the fallbacks lower.
     *
     * <p>Three escape hatches lower it: a break that went unanswered for
     * {@code timeout-delay} falls back to {@code timeout-min}, a nearly dead target to
     * {@code force-min}, and nearly broken armour to {@code breaker-min}.
     */
    private double getDamage(PlayerEntity target) {
        boolean manual = manualMine.get().isPressed();

        if (!manual && slowPlace.get() && lastBreakTimer.passed(slowDelay.get())) {
            return slowMinDamage.get();
        }

        if (!manual && lethalOverride.get() && EntityUtil.getHealth(target) <= forceMaxHealth.get()) {
            return forceMin.get();
        }

        if (armorBreaker.get()) {
            for (ItemStack armor : new ItemStack[]{
                target.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD),
                target.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
                target.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
                target.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET)
            }) {
                if (!armor.isEmpty() && !(EntityUtil.getDamagePercent(armor) > maxDurable.get())) {
                    return armorBreakerDamage.get();
                }
            }
        }

        return minDamage.get();
    }

    // ---- Mining ----

    /**
     * The cell the addon's packet miner is currently working on, or null.
     *
     * <p>{@code PacketMine+} is asked first; Meteor's own PacketMine is the fallback for anyone
     * running that one instead.
     */
    private BlockPos miningPos() {
        BlockPos packetMinePlus = PacketMinePlus.getBreakPos();
        if (packetMinePlus != null) return packetMinePlus;

        PacketMine packetMine = Modules.get().get(PacketMine.class);
        if (packetMine == null) return null;

        for (PacketMine.MyBlock block : packetMine.blocks) {
            if (block.mining) return block.blockPos;
        }

        return null;
    }

    private double miningProgressValue() {
        if (PacketMinePlus.getBreakPos() != null) return PacketMinePlus.progress;

        PacketMine packetMine = Modules.get().get(PacketMine.class);
        if (packetMine == null) return 0.0;

        for (PacketMine.MyBlock block : packetMine.blocks) {
            if (block.mining) return CrystalCompat.progress(block);
        }

        return 0.0;
    }

    private boolean isMining(BlockPos pos) {
        if (pos.equals(PacketMinePlus.getBreakPos())) return true;

        PacketMine packetMine = Modules.get().get(PacketMine.class);
        return packetMine != null && packetMine.isMiningBlock(pos);
    }

    private double packetMineRange() {
        // The reach comes from PacketMine+ while it is running; Meteor's PacketMine has no reach
        // setting of its own, so a plain 6.0 is used when it is the one mining.
        PacketMinePlus packetMinePlus = PacketMinePlus.INSTANCE;
        if (packetMinePlus != null && packetMinePlus.isActive()) return packetMinePlus.range.get();
        return 6.0;
    }

    // ---- Rotation ----

    /**
     * Whether the aim should be stepped rather than snapped.
     *
     * <p>Stepping is skipped while gliding, unless {@code when-elytra} keeps it on.
     */
    private boolean shouldYawStep() {
        return (whenElytra.get() || !mc.player.isGliding()) && yawStep.get();
    }

    private boolean faceVector(Vec3d directionVec) {
        if (directionVec == null) return false;

        if (!shouldYawStep()) {
            RotationHelper.lookAt(directionVec);
            return true;
        }

        return RotationHelper.faceVector(directionVec, true, checkFov.get(), fov.get().floatValue(), steps.get().floatValue());
    }

    public Vec3d getAttackVec(Vec3d feetPos) {
        return MathUtil.getPointToBoxFromBottom(mc.player.getEyePos(), feetPos, breakRange.get(), 2.0, attackVecStep.get());
    }

    // ---- Render ----

    private void renderPlacement(Render3DEvent event) {
        BlockPos cpos = sync.get() && crystalPos != null ? syncPos : crystalPos;
        if (cpos != null) {
            placeVec3d = cpos.down().toCenterPos();
        }

        if (placeVec3d == null) return;

        if (fadeSpeed.get() >= 1.0) {
            currentFade = noPosTimer.passed((long) (startFade.get() * 1000.0)) ? 0.0 : 0.5;
        } else {
            currentFade = AnimateUtil.animate(
                currentFade,
                noPosTimer.passed((long) (startFade.get() * 1000.0)) ? 0.0 : 0.5,
                fadeSpeed.get() / 10.0
            );
        }

        if (currentFade == 0.0) {
            curVec3d = null;
            return;
        }

        if (curVec3d != null && sliderSpeed.get() < 1.0) {
            curVec3d = new Vec3d(
                AnimateUtil.animate(curVec3d.x, placeVec3d.x, sliderSpeed.get() / 10.0),
                AnimateUtil.animate(curVec3d.y, placeVec3d.y, sliderSpeed.get() / 10.0),
                AnimateUtil.animate(curVec3d.z, placeVec3d.z, sliderSpeed.get() / 10.0)
            );
        } else {
            curVec3d = placeVec3d;
        }

        if (!render.get()) return;

        Box cbox = new Box(curVec3d, curVec3d);
        cbox = shrink.get() ? cbox.expand(currentFade) : cbox.expand(0.5);

        if (fill.get()) {
            event.renderer.box(cbox, ColorUtil.scaleAlpha(fillColor.get(), currentFade * 2.0), ColorUtil.scaleAlpha(fillColor.get(), 0.0), ShapeMode.Sides, 0);
        }

        if (this.box.get()) {
            event.renderer.box(cbox, ColorUtil.scaleAlpha(boxColor.get(), 0.0), ColorUtil.scaleAlpha(boxColor.get(), currentFade * 2.0), ShapeMode.Lines, 0);
        }
    }

    private void renderTarget(Render3DEvent event) {
        if (mode.get() == TargetEsp.None || displayTarget == null) return;

        double progress = animation.get(0.0, animationTime.get(), ease.get());
        Color side = ColorUtil.fadeColor(color.get(), hitColor.get(), progress);
        Color line = ColorUtil.fadeColor(outlineColor.get(), hitOutlineColor.get(), progress);

        Vec3d pos = displayTarget.getLerpedPos(event.tickDelta);
        Box box = displayTarget.getDimensions(displayTarget.getPose()).getBoxAt(pos).expand(0.0, 0.1, 0.0);

        event.renderer.box(box, side, line, mode.get() == TargetEsp.Fill ? ShapeMode.Sides : ShapeMode.Both, 0);
    }

    // ---- Enums ----

    public enum SwapMode {
        None,
        Normal,
        Silent,
        Inventory
    }

    public enum Timing {
        All,
        Pre,
        Post
    }

    public enum TargetEsp {
        Box,
        Fill,
        None
    }
}
