package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.mixin.PlayerMoveC2SPacketAccessor;
import com.hongshikaikai.addon.utils.crystal.BlockPosX;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.ColorUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.CrystalConfig;
import com.hongshikaikai.addon.utils.crystal.Easing;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.FadeUtils;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.SwingSide;
import com.hongshikaikai.addon.utils.crystal.Timer;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
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
import meteordevelopment.meteorclient.systems.modules.combat.Criticals;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.joml.Vector3d;

import java.text.DecimalFormat;

/**
 * Packet mining: sends the break packets itself instead of letting the vanilla interaction run.
 *
 * <h2>What it does</h2>
 * Mining is client-side prediction: the client animates a block break on its own and only asks the
 * server for the result at the end. This module sends the break packets itself - a
 * {@code START_DESTROY_BLOCK} the moment you click, then a {@code STOP_DESTROY_BLOCK} once the tool
 * would have finished - and cancels the vanilla interaction. The block is still shown in the world
 * until the server confirms it, which is what the render section draws a progress box over.
 *
 * <p>On top of that it stacks the tricks that make packet mining fast and safe: a second
 * cell mined in the same tick ({@code double-break}), an automatic tool swap for the second cell,
 * aiming the server at the target without moving the camera, spamming a crystal onto the block the
 * moment the break lands ({@code place}), and a claimed {@code onGround} on the last movement packet
 * before the break so a "you were airborne, mining should be slower" check sees a grounded player.
 *
 * <h2>How it works here</h2>
 * <p>The options live in Meteor setting groups, one per concern, and every option is visible under
 * the group it belongs to. {@code StartRotate}/{@code EndRotate} aim through
 * {@link com.hongshikaikai.addon.utils.crystal.RotationHelper}, which sends a silent look packet
 * immediately before the interaction, so the camera is never moved and there is nothing to snap
 * back at the end of a sequence. The systems it defers to are Meteor's or this addon's:
 * {@code Blink} is Meteor's Blink, the anchor-aura guard is Meteor's AnchorAura, {@code low-version}
 * and the armour-stand rule live in
 * {@link com.hongshikaikai.addon.utils.crystal.CrystalConfig}, the crystal it places with is
 * {@link CrystalAuraPlus}, and {@code smart} asks Meteor's Criticals. The {@code onGround} tag on
 * the last movement packet before the break is written through
 * {@link com.hongshikaikai.addon.mixin.PlayerMoveC2SPacketAccessor}. {@code no-collide} keeps its
 * own mixin: while the module is holding a ghost block open, that cell stops colliding.
 * {@code mine(BlockPos)} is a public entry point, so another module can push a target in.
 */
public class PacketMinePlus extends Module {
    public static PacketMinePlus INSTANCE;

    /** The cell the double-break is working on, or null. */
    public static BlockPos secondPos;
    /** How far through the current break the module believes it is, 0..1. */
    public static double progress = 0.0;
    /** Whether the client is showing a block the server has already been told to remove. */
    public static boolean ghost = false;
    /** Whether the current break has been seen to complete. */
    public static boolean complete = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCheck = settings.createGroup("Check");
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<Double> stopDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("stop-delay")
        .description("Milliseconds to wait after a break completes before starting the next one.")
        .defaultValue(50.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Double> startDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("start-delay")
        .description("Milliseconds between the click and the start packet.")
        .defaultValue(200.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Double> damage = sgGeneral.add(new DoubleSetting.Builder()
        .name("damage")
        .description("Multiplier on the predicted break time. Below 1 breaks earlier than vanilla would.")
        .defaultValue(0.7)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Integer> maxBreak = sgGeneral.add(new IntSetting.Builder()
        .name("max-break")
        .description("How many times one cell may be re-broken before the module gives up on it. 0 disables the limit.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    /** Read by {@code Breaker}: whether a tool swap may be made while the second cell is in flight. */
    public final Setting<Boolean> noGhostHand = sgGeneral.add(new BoolSetting.Builder()
        .name("1.21")
        .description("Hold the tool swap back until the next tick, which is what 1.21 servers expect.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> noCollide = sgGeneral.add(new BoolSetting.Builder()
        .name("no-collide")
        .description("Stop the ghost block from colliding, so you can walk through what the server already removed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Timing> timing = sgGeneral.add(new EnumSetting.Builder<Timing>()
        .name("timing")
        .description("Which part of the client tick the module acts on.")
        .defaultValue(Timing.All)
        .build()
    );

    private final Setting<Boolean> grimDisabler = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-disabler")
        .description("Follow every start packet with its stop, which is what the Grim disabler wants to see.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> instant = sgGeneral.add(new BoolSetting.Builder()
        .name("instant")
        .description("Keep re-sending the start packet without waiting for the break to finish.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> wait = sgGeneral.add(new BoolSetting.Builder()
        .name("wait")
        .description("Wait for the block to actually be gone before breaking it again.")
        .defaultValue(true)
        .visible(() -> !instant.get())
        .build()
    );

    private final Setting<Boolean> mineAir = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-air")
        .description("Start a break on a cell that is already air.")
        .defaultValue(true)
        .visible(() -> wait.get() && !instant.get())
        .build()
    );

    private final Setting<Boolean> hotBar = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-swap")
        .description("Take the tool from the hotbar instead of the whole inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doubleBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("double-break")
        .description("Send a start and a stop back to back on a second cell, so two breaks are in flight.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Put the fastest tool for the second cell in your hand while it is being broken.")
        .defaultValue(true)
        .visible(doubleBreak::get)
        .build()
    );

    private final Setting<Double> start = sgGeneral.add(new DoubleSetting.Builder()
        .name("start")
        .description("Fraction of the break time after which auto-switch is allowed to swap the tool in.")
        .defaultValue(0.9)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .visible(doubleBreak::get)
        .build()
    );

    private final Setting<Double> timeOut = sgGeneral.add(new DoubleSetting.Builder()
        .name("time-out")
        .description("Multiplier on the second cell's break time before the second break is abandoned.")
        .defaultValue(1.2)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .visible(doubleBreak::get)
        .build()
    );

    private final Setting<Boolean> setAir = sgGeneral.add(new BoolSetting.Builder()
        .name("set-air")
        .description("Remove the block client-side once the break packet is sent.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing the arm when the break starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> endSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("end-swing")
        .description("Swing the arm when the break finishes.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far a block may be from the eye to be mined, in blocks.")
        .defaultValue(6.0)
        .min(3.0)
        .sliderRange(3.0, 10.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<SwingSide> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingSide>()
        .name("swing-side")
        .description("How the swing that accompanies a break is reported.")
        .defaultValue(SwingSide.All)
        .build()
    );

    // ---- Check ----

    private final Setting<Boolean> unbreakableCancel = sgGeneral.add(new BoolSetting.Builder()
        .name("unbreakable-cancel")
        .description("Drop the target when the block turns out to be unbreakable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> switchReset = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-reset")
        .description("Restart the break timer whenever the held slot changes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> preferWeb = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-web")
        .description("Do not replace a cobweb that is already being mined with a new target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferHead = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-head")
        .description("Do not replace the block above your head while you are crawling.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> farCancel = sgGeneral.add(new BoolSetting.Builder()
        .name("far-cancel")
        .description("Drop the target once it leaves range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-ground")
        .description("Only finish a break while you are on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkWeb = sgGeneral.add(new BoolSetting.Builder()
        .name("check-web")
        .description("Count standing in a cobweb as being on the ground for the break-time calculation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkGround = sgGeneral.add(new BoolSetting.Builder()
        .name("check-ground")
        .description("Apply vanilla's airborne mining penalty to the predicted break time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smart = sgGeneral.add(new BoolSetting.Builder()
        .name("smart")
        .description("Only apply that penalty when the server is actually being told you are grounded, or you are gliding or webbed.")
        .defaultValue(true)
        .visible(checkGround::get)
        .build()
    );

    private final Setting<Boolean> usingPause = sgGeneral.add(new BoolSetting.Builder()
        .name("using-pause")
        .description("Pause while you are using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> allowOffhand = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-offhand")
        .description("Keep mining while the item you are using is in your off hand.")
        .defaultValue(true)
        .visible(usingPause::get)
        .build()
    );

    private final Setting<Boolean> bypassGround = sgGeneral.add(new BoolSetting.Builder()
        .name("bypass-ground")
        .description("Claim onGround on the movement packets sent near the end of a break, so the server does not slow the break down.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> bypassTime = sgGeneral.add(new IntSetting.Builder()
        .name("bypass-time")
        .description("How long before the break finishes the ground claim starts, in milliseconds.")
        .defaultValue(400)
        .min(0)
        .sliderRange(0, 2000)
        .visible(bypassGround::get)
        .build()
    );

    private final Setting<Keybind> pause = sgCheck.add(new KeybindSetting.Builder()
        .name("pause")
        .description("Hold to pause the module.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ---- Place ----

    public final Setting<Boolean> crystal = sgPlace.add(new BoolSetting.Builder()
        .name("crystal")
        .description("Place a crystal on the cell once it is broken, and attack the crystals around it.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyHeadBomber = sgPlace.add(new BoolSetting.Builder()
        .name("only-cev")
        .description("Only place a crystal while the obsidian key is held, the way a head bomber would.")
        .defaultValue(true)
        .visible(crystal::get)
        .build()
    );

    private final Setting<Boolean> waitPlace = sgPlace.add(new BoolSetting.Builder()
        .name("wait-place")
        .description("Hold the break back while a crystal is about to go onto the same cell.")
        .defaultValue(true)
        .visible(crystal::get)
        .build()
    );

    private final Setting<Boolean> spamPlace = sgPlace.add(new BoolSetting.Builder()
        .name("spam-place")
        .description("Place even when a crystal already stands on the cell.")
        .defaultValue(false)
        .visible(crystal::get)
        .build()
    );

    private final Setting<Boolean> afterBreak = sgPlace.add(new BoolSetting.Builder()
        .name("after-break")
        .description("Attack the crystals around the cell the moment the break lands.")
        .defaultValue(true)
        .visible(crystal::get)
        .build()
    );

    private final Setting<Boolean> checkDamage = sgPlace.add(new BoolSetting.Builder()
        .name("detect-progress")
        .description("Only start placing a crystal once the break is this far through.")
        .defaultValue(true)
        .visible(crystal::get)
        .build()
    );

    private final Setting<Double> crystalDamage = sgPlace.add(new DoubleSetting.Builder()
        .name("progress")
        .description("How far through the break has to be before the crystal goes down, 0..1.")
        .defaultValue(0.9)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .visible(() -> crystal.get() && checkDamage.get())
        .build()
    );

    public final Setting<Keybind> obsidian = sgPlace.add(new KeybindSetting.Builder()
        .name("obsidian")
        .description("Hold to place obsidian into the broken cell.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> enderChest = sgPlace.add(new KeybindSetting.Builder()
        .name("ender-chest")
        .description("Hold to place an ender chest into the broken cell instead of obsidian.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> placeRotate = sgPlace.add(new BoolSetting.Builder()
        .name("place-rotate")
        .description("Aim before placing or attacking a crystal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventory = sgPlace.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the block or crystal from your inventory rather than the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Milliseconds between two placements.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    // ---- Rotation ----

    private final Setting<Boolean> rotate = sgRotation.add(new BoolSetting.Builder()
        .name("start-rotate")
        .description("Aim at the cell before starting the break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> endRotate = sgRotation.add(new BoolSetting.Builder()
        .name("end-rotate")
        .description("Aim at the cell again before the stop packet.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> yawStep = sgRotation.add(new BoolSetting.Builder()
        .name("yaw-step")
        .description("Turn towards the cell gradually instead of snapping, and only act once the aim has arrived.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> whenElytra = sgRotation.add(new BoolSetting.Builder()
        .name("fall-flying")
        .description("Keep stepping the aim while you are gliding.")
        .defaultValue(true)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Double> steps = sgRotation.add(new DoubleSetting.Builder()
        .name("steps")
        .description("How much of a half-turn the aim may take per tick while stepping. 0.05 is nine degrees.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Boolean> checkFov = sgRotation.add(new BoolSetting.Builder()
        .name("only-looking")
        .description("With yaw stepping on, hold the action until the aim is already close to the cell.")
        .defaultValue(true)
        .visible(yawStep::get)
        .build()
    );

    private final Setting<Double> fov = sgRotation.add(new DoubleSetting.Builder()
        .name("fov")
        .description("How close the aim has to be, in degrees, for the action to go ahead.")
        .defaultValue(20.0)
        .min(0.0)
        .sliderRange(0.0, 360.0)
        .decimalPlaces(1)
        .visible(() -> yawStep.get() && checkFov.get())
        .build()
    );

    // ---- Render ----

    private final Setting<Boolean> checkDouble = sgRender.add(new BoolSetting.Builder()
        .name("check-double")
        .description("Hide the second cell's box while it is the same cell as the first.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Animation> animation = sgRender.add(new EnumSetting.Builder<Animation>()
        .name("animation")
        .description("How the progress box grows.")
        .defaultValue(Animation.Up)
        .build()
    );

    private final Setting<Easing> ease = sgRender.add(new EnumSetting.Builder<Easing>()
        .name("ease")
        .description("Curve of the progress animation.")
        .defaultValue(Easing.CubicInOut)
        .build()
    );

    private final Setting<Easing> fadeEase = sgRender.add(new EnumSetting.Builder<Easing>()
        .name("fade-ease")
        .description("Curve of the colour fade that runs alongside it.")
        .defaultValue(Easing.CubicInOut)
        .build()
    );

    private final Setting<Double> expandLine = sgRender.add(new DoubleSetting.Builder()
        .name("expand-line")
        .description("How much of the outline is drawn ahead of the fill, 0..1.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<SettingColor> startColor = sgRender.add(new ColorSetting.Builder()
        .name("start-fill")
        .description("Fill colour the progress box starts at.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .build()
    );

    private final Setting<SettingColor> startOutlineColor = sgRender.add(new ColorSetting.Builder()
        .name("start-outline")
        .description("Outline colour the progress box starts at.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .build()
    );

    private final Setting<SettingColor> endColor = sgRender.add(new ColorSetting.Builder()
        .name("end-fill")
        .description("Fill colour the progress box fades to as the break finishes.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .build()
    );

    private final Setting<SettingColor> endOutlineColor = sgRender.add(new ColorSetting.Builder()
        .name("end-outline")
        .description("Outline colour the progress box fades to as the break finishes.")
        .defaultValue(new SettingColor(255, 255, 255, 100))
        .build()
    );

    private final Setting<SettingColor> doubleColor = sgRender.add(new ColorSetting.Builder()
        .name("double-fill")
        .description("Fill colour of the second cell.")
        .defaultValue(new SettingColor(88, 94, 255, 100))
        .visible(doubleBreak::get)
        .build()
    );

    private final Setting<SettingColor> doubleOutlineColor = sgRender.add(new ColorSetting.Builder()
        .name("double-outline")
        .description("Outline colour of the second cell.")
        .defaultValue(new SettingColor(88, 94, 255, 100))
        .visible(doubleBreak::get)
        .build()
    );

    private final Setting<Boolean> text = sgRender.add(new BoolSetting.Builder()
        .name("text")
        .description("Draw the break progress above the cell.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Fill the progress box.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Draw the progress box outline.")
        .defaultValue(true)
        .build()
    );

    // ---- State ----

    private final FadeUtils animationTime = new FadeUtils(1000L);
    private final FadeUtils secondAnim = new FadeUtils(1000L);
    private final DecimalFormat df = new DecimalFormat("0.0");

    private final Timer mineTimer = new Timer();
    private final Timer secondTimer = new Timer();
    private final Timer delayTimer = new Timer();
    private final Timer placeTimer = new Timer();
    private final Timer startTime = new Timer();

    /** Scratch vector for the 3D progress text; reused so the render path allocates nothing. */
    private final Vector3d textPos = new Vector3d();

    BlockPos breakPos;
    int lastSlot = -1;
    Runnable switchBack;
    boolean startPacket = false;
    int breakNumber = 0;
    double breakFinalTime;
    double secondFinalTime;
    boolean sendGroundPacket = false;
    boolean swapped = false;
    int mainSlot = 0;

    public PacketMinePlus() {
        super(
            HongShiKaiKai.CATEGORY,
            "PacketMine+",
            "Sends the break packets itself, with double-break, auto tool swap, silent aim and a crystal placement on the cell it opens."
        );
        INSTANCE = this;
    }

    /** The cell this module is currently breaking, or null when it is off or idle. */
    public static BlockPos getBreakPos() {
        return INSTANCE != null && INSTANCE.isActive() ? INSTANCE.breakPos : null;
    }

    /**
     * Whether a collision query for {@code pos} should be answered with an empty shape.
     *
     * <p>Called from the collision mixin on every query, so the cheap settings are tested before the
     * position is compared.
     */
    public static boolean shouldDisableCollision(BlockPos pos) {
        PacketMinePlus instance = INSTANCE;
        return instance != null
            && instance.isActive()
            && instance.noCollide.get()
            && ghost
            && pos.equals(instance.breakPos);
    }

    @Override
    public String getInfoString() {
        return progress >= 1.0 ? "Done" : df.format(progress * 100.0) + "%";
    }

    @Override
    public void onActivate() {
        INSTANCE = this;
        resetState();
    }

    @Override
    public void onDeactivate() {
        // Put the auto-switched tool back on disable rather than leaving it in the hand.
        if (swapped) {
            InventoryUtil.switchToSlot(mainSlot);
            swapped = false;
        }

        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    private void resetState() {
        startPacket = false;
        ghost = false;
        complete = false;
        breakPos = null;
        secondPos = null;
        breakNumber = 0;
        switchBack = null;
        progress = 0.0;
    }

    private boolean nullCheck() {
        return mc.player == null || mc.world == null;
    }

    // ---- Tick ----

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (timing.get() == Timing.Post) return;
        runTick(true);
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (timing.get() != Timing.Post) return;
        runTick(false);
    }

    private void runTick(boolean pre) {
        if (nullCheck()) return;

        if (breakPos != null && mc.world.isAir(breakPos)) {
            complete = true;
        }

        if (secondPos != null) {
            int secondSlot = getTool(secondPos);
            if (secondSlot == -1) secondSlot = EntityUtil.selectedSlot();

            secondFinalTime = getBreakTime(secondPos, secondSlot, 1.0);
            if (!isAir(secondPos) && !unbreakable(secondPos)) {
                double time = getBreakTime(secondPos, EntityUtil.selectedSlot(), 1.0);
                if (secondTimer.passedMs(time * timeOut.get())) {
                    secondPos = null;
                }
            } else {
                secondPos = null;
            }
        }

        // The tool swap is deferred to the next tick's pre pass, which is what
        // 1.21 servers need.
        if (switchBack != null && pre) {
            switchBack.run();
            switchBack = null;
        }

        if (mc.player.isDead()) {
            secondPos = null;
        }

        autoSwitch();

        if (mc.player.isCreative()) {
            startPacket = false;
            ghost = false;
            complete = false;
            breakNumber = 0;
            breakPos = null;
            progress = 0.0;
        } else if (breakPos == null) {
            breakNumber = 0;
            startPacket = false;
            ghost = false;
            complete = false;
            progress = 0.0;
        } else {
            int slot = getTool(breakPos);
            if (slot == -1) slot = EntityUtil.selectedSlot();

            breakFinalTime = getBreakTime(breakPos, slot);
            progress = breakFinalTime > 0.0 ? mineTimer.getMs() / breakFinalTime : 0.0;

            if (isAir(breakPos)) {
                breakNumber = 0;
            }

            if ((!(breakNumber > maxBreak.get() - 1) || maxBreak.get() <= 0 || complete)
                && (wait.get() || !isAir(breakPos) || instant.get())) {
                if (unbreakable(breakPos)) {
                    if (unbreakableCancel.get()) {
                        breakPos = null;
                        startPacket = false;
                        ghost = false;
                        complete = false;
                    }

                    breakNumber = 0;
                } else if (MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(breakPos.toCenterPos())) > range.get()) {
                    if (farCancel.get()) {
                        startPacket = false;
                        ghost = false;
                        complete = false;
                        breakNumber = 0;
                        breakPos = null;
                    }
                } else if (!usingPause.get()
                    || !mc.player.isUsingItem()
                    || (allowOffhand.get() && mc.player.getActiveHand() != Hand.MAIN_HAND)) {
                    if (!pause.get().isPressed()) {
                        if (!isBlinkPaused()) {
                            if (!isAnchorAuraTarget()) {
                                if (hotBar.get() || EntityUtil.inInventory()) {
                                    doMainStep(slot);
                                }
                            }
                        }
                    }
                }
            } else {
                if (breakPos.equals(secondPos)) {
                    secondPos = null;
                }

                startPacket = false;
                ghost = false;
                complete = false;
                breakNumber = 0;
                breakPos = null;
            }
        }
    }

    /**
     * The body of one tick once the target has survived every gate: the placement half, the
     * wait-for-crystal gate and then either the start or the stop of the break.
     *
     * @param slot the hotbar or inventory index of the best tool for {@code breakPos}
     */
    private void doMainStep(int slot) {
        if (isAir(breakPos)) {
            if (shouldCrystal()) {
                for (Direction facing : Direction.values()) {
                    CombatUtil.attackCrystal(breakPos.offset(facing), placeRotate.get(), true);
                }
            }

            if (placeTimer.passedMs(placeDelay.get()) && BlockUtil.canPlace(breakPos) && mc.currentScreen == null) {
                if (enderChest.get().isPressed()) {
                    int eChest = findBlock(Blocks.ENDER_CHEST);
                    if (eChest != -1) {
                        int oldSlot = EntityUtil.selectedSlot();
                        doSwap(eChest, eChest);
                        BlockUtil.placeBlock(breakPos, placeRotate.get());
                        doSwap(oldSlot, eChest);
                        placeTimer.reset();
                    }
                } else if (obsidian.get().isPressed()) {
                    int obsidianSlot = findBlock(Blocks.OBSIDIAN);
                    if (obsidianSlot != -1) {
                        boolean hasCrystal = false;
                        if (shouldCrystal()) {
                            for (Entity entity : BlockUtil.getEntities(new Box(breakPos.up()))) {
                                if (entity instanceof EndCrystalEntity) {
                                    hasCrystal = true;
                                    break;
                                }
                            }
                        }

                        if (!hasCrystal || spamPlace.get()) {
                            int oldSlot = EntityUtil.selectedSlot();
                            doSwap(obsidianSlot, obsidianSlot);
                            BlockUtil.placeBlock(breakPos, placeRotate.get());
                            doSwap(oldSlot, obsidianSlot);
                            placeTimer.reset();
                        }
                    }
                }
            }

            breakNumber = 0;
        } else if (canPlaceCrystal(breakPos.up()) && shouldCrystal()) {
            if (placeTimer.passedMs(placeDelay.get())) {
                if (checkDamage.get()) {
                    if (breakFinalTime > 0.0
                        && mineTimer.getMs() / breakFinalTime >= crystalDamage.get()
                        && !placeCrystal()) {
                        return;
                    }
                } else if (!placeCrystal()) {
                    return;
                }
            } else if (startPacket) {
                return;
            }
        }

        if (waitPlace.get()) {
            BlockPos target = crystalAuraTarget();
            if (target != null) {
                for (Direction i : Direction.values()) {
                    if (breakPos.offset(i).equals(target)) {
                        CrystalAuraPlus crystalAura = CrystalAuraPlus.INSTANCE;
                        if (crystalAura != null && crystalAura.canPlaceCrystal(breakPos, false, false)) {
                            return;
                        }
                        break;
                    }
                }
            }
        }

        if (delayTimer.passed((long) (double) stopDelay.get())) {
            if (startPacket) {
                finishBreak(slot);
            } else {
                startBreak(slot);
            }
        }
    }

    /** The stop half: rotate back if asked, send the stop packet and swap the tool home. */
    private void finishBreak(int slot) {
        if (isAir(breakPos)) return;
        if (onlyGround.get() && !mc.player.isOnGround()) return;
        if (!mineTimer.passed((long) breakFinalTime)) return;

        if (endRotate.get() && shouldYawStep() && !faceVector(breakPos.toCenterPos().offset(BlockUtil.getClickSide(breakPos), 0.5))) {
            return;
        }

        int old = EntityUtil.selectedSlot();
        boolean shouldSwitch;
        if (hotBar.get()) {
            shouldSwitch = slot != old;
        } else {
            if (slot < 9) slot += 36;
            shouldSwitch = old + 36 != slot;
        }

        if (shouldSwitch) {
            if (hotBar.get()) {
                InventoryUtil.switchToSlot(slot);
            } else {
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, old, SlotActionType.SWAP, mc.player);
            }
        }

        int finalSlot = slot;
        this.switchBack = () -> {
            if (endRotate.get() && !faceVector(breakPos.toCenterPos().offset(BlockUtil.getClickSide(breakPos), 0.5))) {
                if (shouldSwitch) {
                    restoreTool(old, finalSlot, true);
                }
            } else {
                EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, breakPos, BlockUtil.getClickSide(breakPos), id
                ));

                if (endSwing.get()) {
                    EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());
                }

                if (shouldSwitch) {
                    restoreTool(old, finalSlot, false);
                }

                breakNumber++;
                delayTimer.reset();
                startTime.reset();

                if (afterBreak.get() && shouldCrystal()) {
                    for (Direction facing : Direction.values()) {
                        CombatUtil.attackCrystal(breakPos.offset(facing), placeRotate.get(), true);
                    }
                }

                if (setAir.get()) {
                    mc.world.setBlockState(breakPos, Blocks.AIR.getDefaultState());
                }

                ghost = true;
            }
        };

        if (!noGhostHand.get()) {
            this.switchBack.run();
            this.switchBack = null;
        }
    }

    /** The start half: delay, rotation gate, swing, double-break and the start packet. */
    private void startBreak(int slot) {
        if (!startTime.passedMs(startDelay.get())) return;
        if (!mineAir.get() && isAir(breakPos)) return;

        Direction side = BlockUtil.getClickSide(breakPos);
        if (rotate.get()) {
            Vec3i vec3i = side.getVector();
            Vec3d vec = breakPos.toCenterPos().add(new Vec3d(vec3i.getX() * 0.5, vec3i.getY() * 0.5, vec3i.getZ() * 0.5));
            if (!faceVector(vec)) return;
        }

        mineTimer.reset();
        animationTime.reset();

        if (swing.get()) {
            EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());
        }

        if (doubleBreak.get()) {
            if (secondPos == null || isAir(secondPos)) {
                double breakTime = getBreakTime(breakPos, slot, 1.0);
                secondAnim.reset();
                secondAnim.setLength((long) breakTime);
                secondTimer.reset();
                secondPos = breakPos;
            }

            doDoubleBreak(side);
        }

        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, breakPos, side, id
        ));

        startTime.reset();
    }

    private void doDoubleBreak(Direction side) {
        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, breakPos, side, id
        ));
        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, breakPos, side, id
        ));
    }

    /** Puts the tool that was held before the stop packet back, hotbar or inventory alike. */
    private void restoreTool(int old, int finalSlot, boolean skipInventorySync) {
        if (hotBar.get()) {
            InventoryUtil.switchToSlot(old);
        } else {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, finalSlot, old, SlotActionType.SWAP, mc.player);
            if (!skipInventorySync) EntityUtil.syncInventory();
        }
    }

    /** Keeps the fastest tool for the second cell in the hand while it is being broken. */
    private void autoSwitch() {
        if (!autoSwitch.get() || !doubleBreak.get()) return;

        int index = -1;
        if (secondPos != null) {
            float currentFastest = 1.0f;

            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;

                float digSpeed = efficiencyLevel(stack);
                float destroySpeed = stack.getMiningSpeedMultiplier(mc.world.getBlockState(secondPos));
                if (digSpeed + destroySpeed > currentFastest) {
                    currentFastest = digSpeed + destroySpeed;
                    index = i;
                }
            }
        }

        if (index != -1
            && !mc.options.useKey.isPressed()
            && !mc.options.attackKey.isPressed()
            && !mc.player.isUsingItem()
            && secondTimer.passedMs(getBreakTime(secondPos, index, start.get()))) {
            if (index != EntityUtil.selectedSlot()) {
                mainSlot = EntityUtil.selectedSlot();
                InventoryUtil.switchToSlot(index);
                swapped = true;
            }
        } else if (swapped) {
            InventoryUtil.switchToSlot(mainSlot);
            swapped = false;
        }
    }

    // ---- Click ----

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (nullCheck()) return;
        if (mc.player.isCreative()) return;

        event.cancel();

        BlockPos pos = event.blockPos;
        if (pos.equals(breakPos)) return;
        if (unbreakable(pos)) return;
        if (breakPos != null && preferWeb.get() && BlockUtil.getBlock(breakPos) == Blocks.COBWEB) return;
        if (breakPos != null
            && preferHead.get()
            && mc.player.isCrawling()
            && EntityUtil.getPlayerPos(true).up().equals(breakPos)) {
            return;
        }

        if (BlockUtil.getClickSideStrict(pos) == null) return;
        if (MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos())) > range.get()) return;

        breakPos = pos;
        breakNumber = 0;
        startPacket = false;
        ghost = false;
        complete = false;
        mineTimer.reset();
        animationTime.reset();

        if (isBlinkPaused()) return;

        Direction side = BlockUtil.getClickSide(breakPos);
        if (rotate.get()) {
            Vec3i vec3i = side.getVector();
            Vec3d vec = breakPos.toCenterPos().add(new Vec3d(vec3i.getX() * 0.5, vec3i.getY() * 0.5, vec3i.getZ() * 0.5));
            if (!faceVector(vec)) return;
        }

        if (!startTime.passedMs(startDelay.get())) return;

        if (swing.get()) {
            EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());
        }

        if (doubleBreak.get()) {
            if (secondPos == null || isAir(secondPos)) {
                int slot = getTool(breakPos);
                if (slot == -1) slot = EntityUtil.selectedSlot();

                secondFinalTime = getBreakTime(breakPos, slot, 1.0);
                secondAnim.reset();
                secondAnim.setLength((long) secondFinalTime);
                secondTimer.reset();
                secondPos = breakPos;
            }

            doDoubleBreak(side);
        }

        int slot = getTool(breakPos);
        if (slot == -1) slot = EntityUtil.selectedSlot();

        breakFinalTime = getBreakTime(breakPos, slot);
        EntityUtil.sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, breakPos, side, id
        ));
        startTime.reset();
    }

    /**
     * Pushes a cell into the module from outside.
     *
     * <p>When the module is off, or you are in creative, this falls back to the vanilla interaction.
     */
    public void mine(BlockPos pos) {
        if (nullCheck()) return;

        if (mc.player.isCreative() || !isActive()) {
            mc.interactionManager.attackBlock(pos, BlockUtil.getClickSide(pos));
            return;
        }

        if (pos.equals(breakPos)) return;
        if (unbreakable(pos)) return;
        if (breakPos != null && preferWeb.get() && BlockUtil.getBlock(breakPos) == Blocks.COBWEB) return;
        if (breakPos != null
            && preferHead.get()
            && mc.player.isCrawling()
            && EntityUtil.getPlayerPos(true).up().equals(breakPos)) {
            return;
        }

        if (BlockUtil.getClickSideStrict(pos) == null) return;
        if (MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(pos.toCenterPos())) > range.get()) return;

        breakPos = pos;
        breakNumber = 0;
        startPacket = false;
        ghost = false;
        complete = false;
        mineTimer.reset();
        animationTime.reset();
    }

    // ---- Packets ----

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (nullCheck() || mc.player.isCreative()) return;

        if (event.packet instanceof PlayerMoveC2SPacket) {
            if (bypassGround.get()
                && !mc.player.isGliding()
                && breakPos != null
                && !isAir(breakPos)
                && bypassTime.get() > 0
                && MathHelper.sqrt((float) breakPos.toCenterPos().squaredDistanceTo(mc.player.getEyePos())) <= range.get() + 2.0) {
                double breakTime = breakFinalTime - bypassTime.get();
                if (breakTime <= 0.0 || mineTimer.passed((long) breakTime)) {
                    sendGroundPacket = true;
                    ((PlayerMoveC2SPacketAccessor) event.packet).setOnGround(true);
                }
            } else {
                sendGroundPacket = false;
            }
        } else if (event.packet instanceof UpdateSelectedSlotC2SPacket packet) {
            if (packet.getSelectedSlot() != lastSlot) {
                lastSlot = packet.getSelectedSlot();
                if (switchReset.get()) {
                    startPacket = false;
                    ghost = false;
                    complete = false;
                    mineTimer.reset();
                    animationTime.reset();
                }
            }
        } else if (event.packet instanceof PlayerActionC2SPacket packet) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                if (breakPos == null || !packet.getPos().equals(breakPos)) return;

                if (grimDisabler.get()) {
                    mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, packet.getPos(), packet.getDirection()
                    ));
                }

                startPacket = true;
            } else if (packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
                if (breakPos == null || !packet.getPos().equals(breakPos)) return;

                if (!instant.get()) {
                    startPacket = false;
                    ghost = false;
                    complete = false;
                }
            }
        }
    }

    // ---- Placement helpers ----

    private boolean placeCrystal() {
        int crystalSlot = findCrystal();
        if (crystalSlot != -1) {
            int oldSlot = EntityUtil.selectedSlot();
            doSwap(crystalSlot, crystalSlot);
            BlockUtil.placeCrystal(breakPos.up(), placeRotate.get());
            doSwap(oldSlot, crystalSlot);
            placeTimer.reset();
            return !waitPlace.get();
        }

        return true;
    }

    private boolean canPlaceCrystal(BlockPos pos) {
        BlockPos obsPos = pos.down();
        BlockPos boost = obsPos.up();
        return (BlockUtil.getBlock(obsPos) == Blocks.BEDROCK || BlockUtil.getBlock(obsPos) == Blocks.OBSIDIAN)
            && BlockUtil.getClickSideStrict(obsPos) != null
            && noEntity(boost)
            && noEntity(boost.up())
            && (!CrystalConfig.lowVersion || mc.world.isAir(boost.up()));
    }

    private boolean noEntity(BlockPos pos) {
        for (Entity entity : BlockUtil.getEntities(new Box(pos))) {
            if (!(entity instanceof ItemEntity) && (!(entity instanceof ArmorStandEntity) || !CrystalConfig.ignoreArmorStand)) {
                return false;
            }
        }

        return true;
    }

    private void doSwap(int slot, int inv) {
        if (!inventory.get()) {
            InventoryUtil.switchToSlot(slot);
        } else {
            InventoryUtil.inventorySwap(inv, EntityUtil.selectedSlot());
        }
    }

    private int findCrystal() {
        return inventory.get() ? InventoryUtil.findItemInventorySlot(Items.END_CRYSTAL) : InventoryUtil.findItem(Items.END_CRYSTAL);
    }

    private int findBlock(Block block) {
        return inventory.get() ? InventoryUtil.findBlockInventorySlot(block) : InventoryUtil.findBlock(block);
    }

    private boolean shouldCrystal() {
        return crystal.get() && (!onlyHeadBomber.get() || obsidian.get().isPressed());
    }

    /** The cell CrystalAura+ is about to place on, if it is on and has one. */
    private BlockPos crystalAuraTarget() {
        CrystalAuraPlus crystalAura = CrystalAuraPlus.INSTANCE;
        return crystalAura != null && crystalAura.isActive() ? crystalAura.crystalPos : null;
    }

    /** Whether Meteor's Blink is holding packets back, which makes packet mining pointless. */
    private boolean isBlinkPaused() {
        return Modules.get().isActive(Blink.class);
    }

    /**
     * Whether the target is the respawn anchor Meteor's AnchorAura is working on.
     *
     * <p>Meteor's AnchorAura does not expose its current target, so a respawn anchor is refused
     * outright while that module is running.
     */
    private boolean isAnchorAuraTarget() {
        return Modules.get().isActive(AnchorAura.class) && BlockUtil.getBlock(breakPos) instanceof RespawnAnchorBlock;
    }

    // ---- Break-time maths ----

    public static double getBreakTime(BlockPos pos) {
        if (INSTANCE == null || INSTANCE.mc.player == null || INSTANCE.mc.world == null) return 1.0;

        int slot = INSTANCE.getTool(pos);
        if (slot == -1) slot = EntityUtil.selectedSlot();

        return INSTANCE.getBreakTime(pos, slot);
    }

    private double getBreakTime(BlockPos pos, int slot) {
        return getBreakTime(pos, slot, damage.get());
    }

    private double getBreakTime(BlockPos pos, int slot, double damageMultiplier) {
        return 1.0f / getBlockStrength(pos, mc.player.getInventory().getStack(slot)) / 20.0f * 1000.0f * damageMultiplier;
    }

    private float getBlockStrength(BlockPos position, ItemStack itemStack) {
        BlockState state = mc.world.getBlockState(position);
        float hardness = state.getHardness(mc.world, position);
        if (hardness < 0.0f) return 0.0f;

        float penalty = state.isToolRequired() && !itemStack.isSuitableFor(state) ? 100.0f : 30.0f;
        return getDigSpeed(state, itemStack) / hardness / penalty;
    }

    private float getDigSpeed(BlockState state, ItemStack itemStack) {
        float digSpeed = getDestroySpeed(state, itemStack);

        if (digSpeed > 1.0f) {
            int efficiency = efficiencyLevel(itemStack);
            if (efficiency > 0 && !itemStack.isEmpty()) {
                digSpeed += (float) (StrictMath.pow(efficiency, 2.0) + 1.0);
            }
        }

        if (mc.player.hasStatusEffect(StatusEffects.HASTE)) {
            digSpeed *= 1.0f + (mc.player.getStatusEffect(StatusEffects.HASTE).getAmplifier() + 1) * 0.2f;
        }

        if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            digSpeed *= switch (mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
        }

        if (mc.player.isSubmergedInWater()) {
            digSpeed *= (float) mc.player.getAttributeInstance(EntityAttributes.SUBMERGED_MINING_SPEED).getValue();
        }

        boolean inWeb = checkWeb.get()
            && breakPos != null
            && EntityUtil.isInWeb(mc.player)
            && mc.world.getBlockState(breakPos).getBlock() == Blocks.COBWEB;
        if ((!mc.player.isOnGround() || inWeb)
            && checkGround.get()
            && (!smart.get() || Modules.get().isActive(Criticals.class) || mc.player.isGliding() || inWeb)) {
            digSpeed /= 5.0f;
        }

        return digSpeed < 0.0f ? 0.0f : digSpeed;
    }

    private float getDestroySpeed(BlockState state, ItemStack itemStack) {
        float destroySpeed = 1.0f;
        if (itemStack != null && !itemStack.isEmpty()) {
            destroySpeed *= itemStack.getMiningSpeedMultiplier(state);
        }

        return destroySpeed;
    }

    /** The efficiency level on a stack, looked up through the server's own enchantment registry. */
    private int efficiencyLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty() || mc.world == null) return 0;

        try {
            Registry<Enchantment> registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            RegistryEntry<Enchantment> efficiency = registry.getOrThrow(Enchantments.EFFICIENCY);
            return EnchantmentHelper.getLevel(efficiency, stack);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** The fastest tool slot for {@code pos}: hotbar index when {@code hotbar-swap} is on, inventory index otherwise. */
    private int getTool(BlockPos pos) {
        int index = -1;
        float currentFastest = 1.0f;
        int limit = hotBar.get() ? 9 : 36;

        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            float digSpeed = efficiencyLevel(stack);
            float destroySpeed = stack.getMiningSpeedMultiplier(mc.world.getBlockState(pos));
            if (digSpeed + destroySpeed > currentFastest) {
                currentFastest = digSpeed + destroySpeed;
                index = i;
            }
        }

        return index;
    }

    /** Whether the block at {@code blockPos} cannot be broken at all. */
    public static boolean unbreakable(BlockPos blockPos) {
        if (INSTANCE == null || INSTANCE.mc.world == null) return false;

        Block block = INSTANCE.mc.world.getBlockState(blockPos).getBlock();
        return !(block instanceof AirBlock) && (block.getHardness() == -1.0f || block.getHardness() == 100.0f);
    }

    private boolean isAir(BlockPos pos) {
        return mc.world.isAir(pos) || (BlockUtil.getBlock(pos) == Blocks.FIRE && BlockUtil.hasCrystal(pos));
    }

    // ---- Rotation ----

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

    // ---- Render ----

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;

        if (breakPos != null && mc.world.isAir(breakPos)) {
            complete = true;
        }

        if (mc.player.isCreative()) {
            progress = 0.0;
            return;
        }

        if (secondPos != null) {
            if (isAir(secondPos)) {
                secondPos = null;
                return;
            }

            if (!checkDouble.get() || !secondPos.equals(breakPos)) {
                secondAnim.setLength((long) secondFinalTime);
                double doubleEase = secondAnim.ease(ease.get());
                if (box.get()) {
                    event.renderer.box(getFillBox(secondPos, doubleEase), doubleColor.get(), doubleColor.get(), ShapeMode.Sides, 0);
                }

                if (outline.get()) {
                    event.renderer.box(getOutlineBox(secondPos, doubleEase), doubleOutlineColor.get(), doubleOutlineColor.get(), ShapeMode.Lines, 0);
                }
            }
        }

        if (breakPos == null) {
            progress = 0.0;
            return;
        }

        progress = breakFinalTime > 0.0 ? mineTimer.getMs() / breakFinalTime : 0.0;
        animationTime.setLength((long) breakFinalTime);
        double easeValue = animationTime.ease(ease.get());

        if (unbreakable(breakPos)) {
            if (box.get()) {
                event.renderer.box(new Box(breakPos), startColor.get(), startColor.get(), ShapeMode.Sides, 0);
            }

            if (outline.get()) {
                event.renderer.box(new Box(breakPos), startOutlineColor.get(), startOutlineColor.get(), ShapeMode.Lines, 0);
            }

            return;
        }

        Color fill = getColor(animationTime.ease(fadeEase.get()));
        Color line = getOutlineColor(animationTime.ease(fadeEase.get()));

        if (box.get()) {
            event.renderer.box(getFillBox(breakPos, easeValue), fill, fill, ShapeMode.Sides, 0);
        }

        if (outline.get()) {
            event.renderer.box(getOutlineBox(breakPos, easeValue), line, line, ShapeMode.Lines, 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!text.get() || nullCheck() || !isActive()) return;
        if (mc.player.isCreative() || breakPos == null) return;

        String label;
        if (isAir(breakPos)) {
            label = "Waiting";
        } else if ((long) mineTimer.getMs() < breakFinalTime) {
            label = df.format(progress * 100.0) + "%";
        } else {
            label = "100.0%";
        }

        textPos.set(breakPos.getX() + 0.5, breakPos.getY() + 0.5, breakPos.getZ() + 0.5);
        if (!NametagUtils.to2D(textPos, 1.0)) return;

        TextRenderer renderer = TextRenderer.get();
        NametagUtils.begin(textPos);
        renderer.begin(1.0);
        renderer.render(label, -renderer.getWidth(label) / 2.0, 0, Color.WHITE, true);
        renderer.end();
        NametagUtils.end();
    }

    private Box getFillBox(BlockPos pos, double ease) {
        switch (animation.get()) {
            case Center -> {
                ease = (1.0 - ease) / 2.0;
                return new Box(pos).shrink(ease, ease, ease).shrink(-ease, -ease, -ease);
            }
            case Grow -> {
                ease = (1.0 - ease) / 2.0;
                return new Box(pos).shrink(ease, 0.0, ease).shrink(-ease, 0.0, -ease);
            }
            case Up -> {
                return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + ease, pos.getZ() + 1);
            }
            case Down -> {
                return new Box(pos.getX(), pos.getY() + 1 - ease, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            }
            case Oscillation -> {
                return new Box(pos).shrink(ease, ease, ease).shrink(-ease, -ease, -ease);
            }
            default -> {
                return new Box(pos);
            }
        }
    }

    private Box getOutlineBox(BlockPos pos, double ease) {
        ease = Math.min(ease + expandLine.get(), 1.0);
        return getFillBox(pos, ease);
    }

    private Color getColor(double quad) {
        return ColorUtil.fadeColor(startColor.get(), endColor.get(), quad);
    }

    private Color getOutlineColor(double quad) {
        return ColorUtil.fadeColor(startOutlineColor.get(), endOutlineColor.get(), quad);
    }

    // ---- Enums ----

    private enum Animation {
        Center,
        Grow,
        Up,
        Down,
        Oscillation,
        None
    }

    public enum Timing {
        All,
        Pre,
        Post
    }
}
