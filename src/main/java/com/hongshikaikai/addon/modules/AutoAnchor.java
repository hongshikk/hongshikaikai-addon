package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.AnimateUtil;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.ColorUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import com.hongshikaikai.addon.utils.crystal.ExplosionDamageUtils;
import com.hongshikaikai.addon.utils.crystal.InventoryUtil;
import com.hongshikaikai.addon.utils.crystal.PlayerEntityPredict;
import com.hongshikaikai.addon.utils.crystal.RotationHelper;
import com.hongshikaikai.addon.utils.crystal.SwingSide;
import com.hongshikaikai.addon.utils.crystal.Timer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Respawn-anchor aura: places, charges with glowstone and detonates anchors on nearby enemies.
 *
 * <h2>What it does</h2>
 * For the nearest enemy it does the whole respawn-anchor cycle itself: it picks the cell two above
 * the target (or searches the sphere around the player when that cell is blocked), places the anchor,
 * charges it with glowstone, and then clicks it with something that is not glowstone to detonate it.
 * The cell is chosen by simulating the anchor's explosion - blast power 10, with the anchor itself
 * read as air - against the target's predicted position and against the player's own, and keeping the
 * one that hurts the target most without exceeding the self-damage and health-reserve limits.
 *
 * <p>The {@code assist} group watches for a cell where an anchor would hit a nearby enemy but cannot
 * be placed directly, and places the one obsidian neighbour that makes it placeable.
 *
 * <h2>How the settings are laid out</h2>
 * <ul>
 *     <li>The options are grouped into Meteor setting groups - Interact, Predict, Rotate, Assist and
 *         Render - and every option is visible under the group it belongs to.</li>
 *     <li>The search runs on the client tick, which is where every other Meteor combat module does
 *         its search.</li>
 *     <li>Yaw stepping is applied inline through {@link RotationHelper}, so aiming never leaves the
 *         calling code.</li>
 *     <li>The neighbouring systems it defers to are Meteor's or this addon's:
 *         {@code prefer-crystal} follows {@code CrystalAura+}, pausing on a blink follows Meteor's
 *         {@code Blink}, and air placement is the {@code CrystalAura+} switch that
 *         {@link BlockUtil} already reads.</li>
 *     <li>{@code target-esp} offers Box, Fill and None, with Fill as the default.</li>
 *     <li>The Box and Fill colours are each a boolean switch plus a colour.</li>
 * </ul>
 *
 * <h2>Mining detection</h2>
 * Whether a cell is being mined is tracked from the server's {@code BlockBreakingProgress} packets
 * and from the packet mine's current break position. The map is kept here, scoped to this module, so
 * {@code only-mining} and {@code detect-mining} answer from this module's own view of the world.
 */
public class AutoAnchor extends Module {
    public static AutoAnchor INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInteract = settings.createGroup("Interact");
    private final SettingGroup sgPredict = settings.createGroup("Predict");
    private final SettingGroup sgRotate = settings.createGroup("Rotate");
    private final SettingGroup sgAssist = settings.createGroup("Assist");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far the anchor may be placed from the eye, in blocks.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 6.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player may be and still be targeted, in blocks.")
        .defaultValue(8.0)
        .min(0.1)
        .sliderRange(0.1, 12.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Boolean> preferCrystal = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-crystal")
        .description("Stand down while CrystalAura+ has a crystal of its own to place. On by default, so a placement is never fought over.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> light = sgGeneral.add(new BoolSetting.Builder()
        .name("less-cpu")
        .description("Only consider cells the target can actually be seen from, which is much cheaper but skips cells behind cover.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventorySwap = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-swap")
        .description("Take the anchor and the glowstone from your inventory rather than the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakCrystal = sgGeneral.add(new BoolSetting.Builder()
        .name("break-crystal")
        .description("Attack a crystal standing in the anchor cell before placing into it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spam = sgGeneral.add(new BoolSetting.Builder()
        .name("spam")
        .description("Cycle place, charge and detonate without waiting for the anchor to be seen as charged.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyMining = sgGeneral.add(new BoolSetting.Builder()
        .name("only-mining")
        .description("Only spam while the anchor cell is being mined. This is what makes the module answer a burrow-break attempt.")
        .defaultValue(true)
        .visible(spam::get)
        .build()
    );

    private final Setting<Boolean> fast = sgGeneral.add(new BoolSetting.Builder()
        .name("fast")
        .description("Place the anchor again immediately after detonating it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> whenSpamming = sgGeneral.add(new BoolSetting.Builder()
        .name("when-spamming")
        .description("Also re-place the anchor in the same tick it is detonated while spamming.")
        .defaultValue(true)
        .visible(fast::get)
        .build()
    );

    private final Setting<Boolean> usingPause = sgGeneral.add(new BoolSetting.Builder()
        .name("using-pause")
        .description("Pause while you are using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SwingSide> swingMode = sgGeneral.add(new EnumSetting.Builder<SwingSide>()
        .name("swing")
        .description("How the swing that accompanies an interaction is reported.")
        .defaultValue(SwingSide.All)
        .build()
    );

    private final Setting<Timing> timing = sgGeneral.add(new EnumSetting.Builder<Timing>()
        .name("timing")
        .description("Which part of the client tick the module acts on.")
        .defaultValue(Timing.All)
        .build()
    );

    private final Setting<Double> placeDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-delay")
        .description("Milliseconds between two anchor placements.")
        .defaultValue(100.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Double> fillDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill-delay")
        .description("Milliseconds between charging an anchor and detonating it.")
        .defaultValue(100.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Double> breakDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-delay")
        .description("Milliseconds between detonating an anchor and the next charge.")
        .defaultValue(100.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Double> spamDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("spam-delay")
        .description("Milliseconds between two passes of the spam cycle.")
        .defaultValue(200.0)
        .min(0.0)
        .sliderRange(0.0, 1000.0)
        .build()
    );

    private final Setting<Double> updateDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("update-delay")
        .description("Milliseconds between two searches for a good anchor cell.")
        .defaultValue(200.0)
        .min(0.0)
        .sliderRange(0.0, 1000.0)
        .build()
    );

    // ---- Interact ----

    private final Setting<Double> minDamage = sgInteract.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum damage a fresh placement has to deal to be worth making, in hearts.")
        .defaultValue(4.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> explosionMin = sgInteract.add(new DoubleSetting.Builder()
        .name("explosion-min")
        .description("Minimum damage an existing anchor has to deal to be worth detonating, in hearts.")
        .defaultValue(4.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> forceHead = sgInteract.add(new DoubleSetting.Builder()
        .name("force-head")
        .description("Damage the head cell has to deal to be chosen over any other cell, in hearts.")
        .defaultValue(7.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Boolean> noSuicide = sgInteract.add(new BoolSetting.Builder()
        .name("no-suicide")
        .description("Never make a placement that would kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smart = sgInteract.add(new BoolSetting.Builder()
        .name("smart")
        .description("Never make a placement that hurts you more than it hurts the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> terrainIgnore = sgInteract.add(new BoolSetting.Builder()
        .name("terrain-ignore")
        .description("Cost the explosion as if everything that is not obsidian, bedrock or an anvil were air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> prefer = sgInteract.add(new DoubleSetting.Builder()
        .name("prefer")
        .description("Damage an existing anchor has to deal before it outranks building a new one, in hearts.")
        .defaultValue(7.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgInteract.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage the module will accept dealing to you, in hearts.")
        .defaultValue(8.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    // ---- Predict ----

    private final Setting<Integer> selfPredict = sgPredict.add(new IntSetting.Builder()
        .name("self-predict")
        .description("Ticks to simulate your own movement forward by when costing self-damage.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> predictTicks = sgPredict.add(new IntSetting.Builder()
        .name("predict")
        .description("Ticks to simulate the target's movement forward by when picking a cell.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> simulation = sgPredict.add(new IntSetting.Builder()
        .name("simulation")
        .description("How many fractions along each axis each predicted step is allowed to be split into when the full step is blocked.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> maxMotionY = sgPredict.add(new DoubleSetting.Builder()
        .name("max-motion-y")
        .description("Cap on the vertical motion the prediction may carry forward, in blocks per tick.")
        .defaultValue(0.34)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> step = sgPredict.add(new BoolSetting.Builder()
        .name("step")
        .description("Let the prediction walk up a block.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doubleStep = sgPredict.add(new BoolSetting.Builder()
        .name("double-step")
        .description("Let the prediction walk up two blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> jump = sgPredict.add(new BoolSetting.Builder()
        .name("jump")
        .description("Let the prediction jump when it leaves the ground.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inBlockPause = sgPredict.add(new BoolSetting.Builder()
        .name("in-block-pause")
        .description("Stop predicting once the target is standing inside a block.")
        .defaultValue(true)
        .build()
    );

    // ---- Rotate ----

    private final Setting<Boolean> rotate = sgRotate.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Aim at the anchor cell before interacting with it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> yawStep = sgRotate.add(new BoolSetting.Builder()
        .name("yaw-step")
        .description("Turn in steps instead of snapping, and hold the interaction back until the aim has arrived.")
        .defaultValue(true)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> fallFlying = sgRotate.add(new BoolSetting.Builder()
        .name("fall-flying")
        .description("Keep stepping the aim while you are gliding.")
        .defaultValue(true)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Double> steps = sgRotate.add(new DoubleSetting.Builder()
        .name("steps")
        .description("Fraction of a half-turn of yaw allowed per tick while stepping.")
        .defaultValue(0.05)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Boolean> checkFov = sgRotate.add(new BoolSetting.Builder()
        .name("only-looking")
        .description("Only act once the aim the server holds is already inside the configured field of view.")
        .defaultValue(true)
        .visible(() -> rotate.get() && yawStep.get())
        .build()
    );

    private final Setting<Double> fov = sgRotate.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Field of view, in degrees, the existing aim has to be within.")
        .defaultValue(20.0)
        .min(0.0)
        .sliderRange(0.0, 360.0)
        .decimalPlaces(1)
        .visible(() -> rotate.get() && yawStep.get() && checkFov.get())
        .build()
    );

    // ---- Assist ----

    private final Setting<Boolean> assist = sgAssist.add(new BoolSetting.Builder()
        .name("assist")
        .description("Place one block to make a good anchor cell placeable when it is not already.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> obsidian = sgAssist.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Use obsidian for the assist block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectMining = sgAssist.add(new BoolSetting.Builder()
        .name("detect-mining")
        .description("Skip an assist cell that is already being mined.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> assistRange = sgAssist.add(new DoubleSetting.Builder()
        .name("assist-range")
        .description("How far an enemy may be for the assist to look for a cell, in blocks.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderRange(0.0, 6.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> assistDamage = sgAssist.add(new DoubleSetting.Builder()
        .name("assist-damage")
        .description("Minimum damage the assisted anchor has to deal, in hearts.")
        .defaultValue(6.0)
        .min(0.0)
        .sliderRange(0.0, 36.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Double> assistDelay = sgAssist.add(new DoubleSetting.Builder()
        .name("assist-delay")
        .description("Seconds between two assist placements.")
        .defaultValue(0.1)
        .min(0.0)
        .sliderRange(0.0, 1.0)
        .decimalPlaces(2)
        .build()
    );

    // ---- Render ----

    private final Setting<TargetEsp> targetEsp = sgRender.add(new EnumSetting.Builder<TargetEsp>()
        .name("target-esp")
        .description("How to draw the target the module is aiming at.")
        .defaultValue(TargetEsp.Fill)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Colour of the target shape.")
        .defaultValue(new SettingColor(255, 255, 255, 250))
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("target-outline-color")
        .description("Outline colour of the target shape.")
        .defaultValue(new SettingColor(255, 255, 255, 250))
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the anchor cell that is being worked on.")
        .defaultValue(true)
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
        .min(0.0)
        .sliderRange(0.0, 1.0)
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

    // ---- State ----

    private final Timer delayTimer = new Timer();
    private final Timer calcTimer = new Timer();
    private final Timer noPosTimer = new Timer();
    private final Timer assistTimer = new Timer();

    private final ArrayList<BlockPos> chargeList = new ArrayList<>();
    /** Cells the server has reported breaking, keyed by the entity doing the breaking. */
    private final Map<Integer, BlockPos> breakMap = new ConcurrentHashMap<>();

    public PlayerEntity displayTarget;
    public BlockPos currentPos;
    public BlockPos tempPos;
    public double lastDamage;
    double fade = 0.0;
    BlockPos assistPos;
    private Vec3d placeVec3d;
    private Vec3d curVec3d;

    public AutoAnchor() {
        super(
            HongShiKaiKai.CATEGORY,
            "AutoAnchor",
            "Places, charges and detonates respawn anchors on nearby enemies."
        );
        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        INSTANCE = this;
        tempPos = null;
        currentPos = null;
        displayTarget = null;
        assistPos = null;
        placeVec3d = null;
        curVec3d = null;
        fade = 0.0;
        chargeList.clear();
        breakMap.clear();
        RotationHelper.reset();
    }

    @Override
    public void onDeactivate() {
        tempPos = null;
        currentPos = null;
        displayTarget = null;
        assistPos = null;
        breakMap.clear();
        CombatUtil.clearOverride();
        CombatUtil.terrainIgnore = false;
    }

    @Override
    public String getInfoString() {
        return displayTarget != null && currentPos != null ? displayTarget.getName().getString() : null;
    }

    /** Whether a straight line from {@code from} reaches {@code to} without a block in the way. */
    public static boolean canSee(Vec3d from, Vec3d to) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        HitResult result = client.world.raycast(new RaycastContext(
            from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player
        ));
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    // ---- Tick ----

    private boolean nullCheck() {
        return mc.player == null || mc.world == null;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
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

        int anchor = inventorySwap.get()
            ? InventoryUtil.findBlockInventorySlot(Blocks.RESPAWN_ANCHOR)
            : InventoryUtil.findBlock(Blocks.RESPAWN_ANCHOR);
        int glowstone = inventorySwap.get()
            ? InventoryUtil.findBlockInventorySlot(Blocks.GLOWSTONE)
            : InventoryUtil.findBlock(Blocks.GLOWSTONE);
        int unBlock = inventorySwap.get() ? anchor : InventoryUtil.findUnBlock();
        int old = EntityUtil.selectedSlot();

        if (isBlinkPaused()) {
            currentPos = null;
        } else if (preferCrystal.get() && hasCrystalTarget()) {
            currentPos = null;
        } else if (anchor == -1) {
            currentPos = null;
        } else if (glowstone == -1) {
            currentPos = null;
        } else if (unBlock == -1) {
            currentPos = null;
        } else if (mc.player.isSneaking()) {
            currentPos = null;
        } else if (usingPause.get() && mc.player.isUsingItem()) {
            currentPos = null;
        } else if (!inventorySwap.get() || EntityUtil.inInventory()) {
            if (assist.get()) onAssist();
            calc();

            BlockPos pos = currentPos;
            if (pos == null) return;

            if (breakCrystal.get()) CombatUtil.attackCrystal(pos, rotate.get(), false);

            boolean shouldSpam = spam.get() && (!onlyMining.get() || isMining(pos));
            if (shouldSpam) {
                if (!delayTimer.passed((long) spamDelay.get().doubleValue())) return;
                delayTimer.reset();

                if (BlockUtil.canPlace(pos, range.get(), breakCrystal.get())) {
                    placeBlock(pos, rotate.get(), anchor);
                }

                if (!chargeList.contains(pos)) {
                    delayTimer.reset();
                    clickBlock(pos, BlockUtil.getClickSide(pos), rotate.get(), glowstone);
                    chargeList.add(pos);
                }

                chargeList.remove(pos);
                clickBlock(pos, BlockUtil.getClickSide(pos), rotate.get(), unBlock);

                if (fast.get() && whenSpamming.get()) {
                    if (shouldYawStep() && checkFov.get()) {
                        Direction side = BlockUtil.getClickSide(pos);
                        Vec3d directionVec = clickVector(pos, side);
                        if (RotationHelper.inFov(directionVec, fov.get().floatValue())) {
                            placeThroughCrystal(pos, anchor);
                        }
                    } else {
                        placeThroughCrystal(pos, anchor);
                    }
                }
            } else if (BlockUtil.canPlace(pos, range.get(), breakCrystal.get())) {
                if (!delayTimer.passed((long) placeDelay.get().doubleValue())) return;
                delayTimer.reset();
                placeBlock(pos, rotate.get(), anchor);
            } else if (BlockUtil.getBlock(pos) == Blocks.RESPAWN_ANCHOR) {
                if (!chargeList.contains(pos)) {
                    if (!delayTimer.passed((long) fillDelay.get().doubleValue())) return;
                    delayTimer.reset();
                    clickBlock(pos, BlockUtil.getClickSide(pos), rotate.get(), glowstone);
                    chargeList.add(pos);
                } else {
                    if (!delayTimer.passed((long) breakDelay.get().doubleValue())) return;
                    delayTimer.reset();
                    chargeList.remove(pos);
                    clickBlock(pos, BlockUtil.getClickSide(pos), rotate.get(), unBlock);

                    if (fast.get()) {
                        if (shouldYawStep() && checkFov.get()) {
                            Direction side = BlockUtil.getClickSide(pos);
                            Vec3d directionVec = clickVector(pos, side);
                            if (RotationHelper.inFov(directionVec, fov.get().floatValue())) {
                                placeThroughCrystal(pos, anchor);
                            }
                        } else {
                            placeThroughCrystal(pos, anchor);
                        }
                    }
                }
            }

            if (!inventorySwap.get()) doSwap(old);
        }
    }

    /**
     * Places the anchor into {@code pos} as if the cell were empty.
     *
     * <p>The block that was clicked to detonate still exists on
     * the client, so the placement is costed with the cell overridden to air. Without it the second
     * placement of the spam cycle would be refused by the client's own "there is a block here" check.
     */
    private void placeThroughCrystal(BlockPos pos, int anchor) {
        CombatUtil.modifyPos = pos;
        CombatUtil.modifyBlockState = Blocks.AIR.getDefaultState();
        placeBlock(pos, rotate.get(), anchor);
        CombatUtil.modifyPos = null;
    }

    private static Vec3d clickVector(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getVector().getX() * 0.5,
            pos.getY() + 0.5 + side.getVector().getY() * 0.5,
            pos.getZ() + 0.5 + side.getVector().getZ() * 0.5
        );
    }

    /** Whether CrystalAura+ is holding a target better than anything this module would pick. */
    private static boolean hasCrystalTarget() {
        CrystalAuraPlus aura = CrystalAuraPlus.INSTANCE;
        return aura != null && aura.isActive() && aura.crystalPos != null;
    }

    /** Whether Meteor's Blink is holding packets back, which makes the server's view stale. */
    private static boolean isBlinkPaused() {
        return Modules.get().isActive(Blink.class);
    }

    // ---- Search ----

    private void calc() {
        if (nullCheck()) return;

        if (calcTimer.passed((long) updateDelay.get().doubleValue())) {
            calcTimer.reset();

            PlayerEntityPredict self = new PlayerEntityPredict(
                mc.player,
                maxMotionY.get(),
                selfPredict.get(),
                simulation.get(),
                step.get(),
                doubleStep.get(),
                jump.get(),
                inBlockPause.get()
            );

            tempPos = null;
            double placeDamage = minDamage.get();
            double breakDamage = explosionMin.get();
            boolean anchorFound = false;

            List<PlayerEntity> enemies = CombatUtil.getEnemies(targetRange.get());
            ArrayList<PlayerEntityPredict> list = new ArrayList<>();

            for (PlayerEntity player : enemies) {
                list.add(new PlayerEntityPredict(
                    player,
                    maxMotionY.get(),
                    predictTicks.get(),
                    simulation.get(),
                    step.get(),
                    doubleStep.get(),
                    jump.get(),
                    inBlockPause.get()
                ));
            }

            // One pass over the cell above each target: the head cell is the best anchor cell there
            // is, so it is tested first and taken immediately when it is good enough.
            for (PlayerEntityPredict pap : list) {
                BlockPos pos = EntityUtil.getEntityPos(pap.player, true).up(2);
                if (!(BlockUtil.canPlace(pos, range.get(), breakCrystal.get())
                    || BlockUtil.getBlock(pos) == Blocks.RESPAWN_ANCHOR && BlockUtil.getClickSideStrict(pos) != null)) {
                    continue;
                }

                double selfDamage = getAnchorDamage(pos, self);
                if (selfDamage > maxSelfDamage.get()) continue;
                if (noSuicide.get() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;

                double damage = getAnchorDamage(pos, pap);
                if (!(damage > forceHead.get())) continue;
                if (smart.get() && selfDamage > damage) continue;

                lastDamage = damage;
                displayTarget = pap.player;
                tempPos = pos;
                break;
            }

            // Otherwise sweep the sphere around the player, preferring a lethal cell over a merely
            // adequate one and an existing anchor over building a new one.
            if (tempPos == null) {
                for (BlockPos pos : BlockUtil.getSphere(range.get().floatValue() + 1.0f, mc.player.getEyePos())) {
                    for (PlayerEntityPredict pap : list) {
                        if (light.get()) {
                            CombatUtil.modifyPos = pos;
                            CombatUtil.modifyBlockState = Blocks.AIR.getDefaultState();
                            boolean skip = !canSee(pos.toCenterPos(), pap.pos);
                            CombatUtil.modifyPos = null;
                            if (skip) continue;
                        }

                        if (BlockUtil.getBlock(pos) != Blocks.RESPAWN_ANCHOR) {
                            if (anchorFound || !BlockUtil.canPlace(pos, range.get(), breakCrystal.get())) continue;

                            CombatUtil.modifyPos = pos;
                            CombatUtil.modifyBlockState = Blocks.OBSIDIAN.getDefaultState();
                            boolean skip = BlockUtil.getClickSideStrict(pos) == null;
                            CombatUtil.modifyPos = null;
                            if (skip) continue;

                            double damage = getAnchorDamage(pos, pap);
                            if (damage < placeDamage || !crystalIsWeaker(damage)) continue;

                            double selfDamage = getAnchorDamage(pos, self);
                            if (selfDamage > maxSelfDamage.get()) continue;
                            if (noSuicide.get() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
                            if (smart.get() && selfDamage > damage) continue;

                            lastDamage = damage;
                            displayTarget = pap.player;
                            placeDamage = damage;
                            tempPos = pos;
                        } else {
                            double damage = getAnchorDamage(pos, pap);
                            if (damage < breakDamage || BlockUtil.getClickSideStrict(pos) == null) continue;

                            if (damage >= prefer.get()) anchorFound = true;
                            if (!anchorFound && damage < placeDamage) continue;
                            if (!crystalIsWeaker(damage)) continue;

                            double selfDamage = getAnchorDamage(pos, self);
                            if (selfDamage > maxSelfDamage.get()) continue;
                            if (noSuicide.get() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
                            if (smart.get() && selfDamage > damage) continue;

                            lastDamage = damage;
                            displayTarget = pap.player;
                            breakDamage = damage;
                            tempPos = pos;
                        }
                    }
                }
            }
        }

        // The cell is published every tick, not only on a search, so the render fade and the tick
        // loop see a null as soon as the search stops finding anything.
        currentPos = tempPos;
    }

    /**
     * Whether CrystalAura+'s own chosen placement is worth less than {@code damage}.
     *
     * <p>The test is against CrystalAura+'s own chosen placement. With CrystalAura+ off, or holding
     * no cell, there is nothing to defer to.
     */
    private static boolean crystalIsWeaker(double damage) {
        CrystalAuraPlus aura = CrystalAuraPlus.INSTANCE;
        return aura == null || !aura.isActive() || aura.crystalPos == null || aura.lastDamage < damage;
    }

    /**
     * Damage an anchor detonated at {@code anchorPos} would deal.
     *
     * <p>{@code terrainIgnore} is applied around the call: every block that is
     * not on the hard list is read as air for the duration of the calculation.
     */
    public double getAnchorDamage(BlockPos anchorPos, PlayerEntityPredict predict) {
        if (terrainIgnore.get()) CombatUtil.terrainIgnore = true;

        double damage = ExplosionDamageUtils.anchorDamage(anchorPos, predict.player, predict);
        CombatUtil.terrainIgnore = false;
        return damage;
    }

    // ---- Placing ----

    public void placeBlock(BlockPos pos, boolean rotate, int slot) {
        if (BlockUtil.allowAirPlace()) {
            airPlace(pos, rotate, slot);
        } else {
            Direction side = BlockUtil.getPlaceSide(pos);
            if (side != null) clickBlock(pos.offset(side), side.getOpposite(), rotate, slot);
        }
    }

    public void clickBlock(BlockPos pos, Direction side, boolean rotate, int slot) {
        if (pos == null) return;

        Vec3d directionVec = clickVector(pos, side);
        if (!rotate || faceVector(directionVec)) {
            doSwap(slot);
            EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());
            BlockHitResult result = new BlockHitResult(directionVec, side, pos, false);
            EntityUtil.sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, result, id));

            if (inventorySwap.get()) doSwap(slot);
        }
    }

    public void airPlace(BlockPos pos, boolean rotate, int slot) {
        if (pos == null) return;

        Direction side = BlockUtil.getClickSide(pos);
        Vec3d directionVec = clickVector(pos, side);
        if (!rotate || faceVector(directionVec)) {
            doSwap(slot);
            EntityUtil.swingHand(Hand.MAIN_HAND, swingMode.get());
            BlockHitResult result = new BlockHitResult(directionVec, side, pos, false);
            EntityUtil.sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, result, id));

            if (inventorySwap.get()) doSwap(slot);
        }
    }

    private void doSwap(int slot) {
        if (inventorySwap.get()) {
            InventoryUtil.inventorySwap(slot, EntityUtil.selectedSlot());
        } else {
            InventoryUtil.switchToSlot(slot);
        }
    }

    // ---- Rotation ----

    /**
     * Turns towards {@code directionVec}, snapping or stepping, and reports whether the aim is close
     * enough to act on.
     *
     * <p>The step and the field-of-view test happen in the same call - see
     * {@link RotationHelper}.
     */
    public boolean faceVector(Vec3d directionVec) {
        if (!shouldYawStep()) {
            RotationHelper.lookAt(directionVec);
            return true;
        }

        return RotationHelper.faceVector(directionVec, true, checkFov.get(), fov.get().floatValue(), steps.get().floatValue());
    }

    /**
     * Whether the aim should be stepped rather than snapped.
     *
     * <p>Stepping is forced while {@code fallFlying} is on, and otherwise only while you are not
     * gliding.
     */
    private boolean shouldYawStep() {
        if (!yawStep.get()) return false;
        if (fallFlying.get()) return true;
        return !mc.player.isGliding();
    }

    // ---- Assist ----

    /**
     * Places the one block that makes an otherwise unplaceable anchor cell usable.
     *
     * <p>For the nearest enemies it finds the cell two above
     * them, or a horizontal neighbour of it, where an anchor would be lethal; if that cell has no
     * place side but one of its neighbours does, the obsidian goes down there and the next tick can
     * place the anchor.
     */
    public void onAssist() {
        assistPos = null;

        int anchor = inventorySwap.get()
            ? InventoryUtil.findBlockInventorySlot(Blocks.RESPAWN_ANCHOR)
            : InventoryUtil.findBlock(Blocks.RESPAWN_ANCHOR);
        int glowstone = inventorySwap.get()
            ? InventoryUtil.findBlockInventorySlot(Blocks.GLOWSTONE)
            : InventoryUtil.findBlock(Blocks.GLOWSTONE);
        int old = EntityUtil.selectedSlot();

        if (anchor == -1 || glowstone == -1) return;
        if (mc.player.isSneaking()) return;
        if (usingPause.get() && mc.player.isUsingItem()) return;
        if (isBlinkPaused()) return;
        if (!assistTimer.passed((long) (assistDelay.get() * 1000.0))) return;

        assistTimer.reset();

        if (obsidian.get()) {
            anchor = inventorySwap.get()
                ? InventoryUtil.findBlockInventorySlot(Blocks.OBSIDIAN)
                : InventoryUtil.findBlock(Blocks.OBSIDIAN);
            if (anchor == -1) return;
        }

        ArrayList<PlayerEntityPredict> list = new ArrayList<>();
        for (PlayerEntity player : CombatUtil.getEnemies(assistRange.get())) {
            list.add(new PlayerEntityPredict(
                player,
                maxMotionY.get(),
                predictTicks.get(),
                simulation.get(),
                step.get(),
                doubleStep.get(),
                jump.get(),
                inBlockPause.get()
            ));
        }

        double bestDamage = assistDamage.get();

        for (PlayerEntityPredict pap : list) {
            BlockPos pos = EntityUtil.getEntityPos(pap.player, true).up(2);
            if (mc.world.getBlockState(pos).getBlock() == Blocks.RESPAWN_ANCHOR) return;

            if (BlockUtil.clientCanPlace(pos, false)) {
                double damage = getAnchorDamage(pos, pap);
                if (damage >= bestDamage) {
                    bestDamage = damage;
                    assistPos = pos;
                }
            }

            for (Direction i : Direction.values()) {
                if (i != Direction.UP && i != Direction.DOWN && BlockUtil.clientCanPlace(pos.offset(i), false)) {
                    double damage = getAnchorDamage(pos.offset(i), pap);
                    if (damage >= bestDamage) {
                        bestDamage = damage;
                        assistPos = pos.offset(i);
                    }
                }
            }
        }

        if (assistPos == null) return;
        if (BlockUtil.getPlaceSide(assistPos, range.get()) != null) return;

        BlockPos placePos = getHelper(assistPos);
        if (placePos == null) return;

        doSwap(anchor);
        BlockUtil.placeBlock(placePos, rotate.get());

        if (inventorySwap.get()) {
            doSwap(anchor);
        } else {
            doSwap(old);
        }
    }

    /** The neighbouring cell that could be built on to give {@code pos} a place side. */
    public BlockPos getHelper(BlockPos pos) {
        for (Direction i : Direction.values()) {
            if ((!detectMining.get() || !isMining(pos.offset(i)))
                && BlockUtil.isStrictDirection(pos.offset(i), i.getOpposite())
                && BlockUtil.canPlace(pos.offset(i))) {
                return pos.offset(i);
            }
        }

        return null;
    }

    // ---- Mining detection ----

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof BlockBreakingProgressS2CPacket packet && packet.getPos() != null) {
            breakMap.put(packet.getEntityId(), packet.getPos());
        }
    }

    /**
     * Whether {@code pos} is being mined right now, by this client's packet mine or by anyone else.
     *
     * <p>The question is answered from the map this module keeps from the server's
     * breaking-progress packets. A cell that has gone
     * to air has finished breaking and is dropped from the map here.
     */
    private boolean isMining(BlockPos pos) {
        BlockPos own = PacketMinePlus.getBreakPos();
        if (own != null && own.equals(pos)) return true;
        if (mc.world == null) return false;

        for (Iterator<Map.Entry<Integer, BlockPos>> it = breakMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BlockPos> entry = it.next();

            if (mc.world.isAir(entry.getValue())) {
                it.remove();
                continue;
            }

            if (!entry.getValue().equals(pos)) continue;

            Entity entity = mc.world.getEntityById(entry.getKey());
            if (entity != null && entity.getEyePos().distanceTo(pos.toCenterPos()) <= 7.0) {
                return true;
            }
        }

        return false;
    }

    // ---- Render ----

    @EventHandler
    private void onRender(Render3DEvent event) {
        renderPlacement(event);
        renderTarget(event);
    }

    private void renderPlacement(Render3DEvent event) {
        if (currentPos != null) {
            noPosTimer.reset();
            placeVec3d = currentPos.toCenterPos();
        }

        if (placeVec3d == null) return;

        if (fadeSpeed.get() >= 1.0) {
            fade = noPosTimer.passed((long) (startFade.get() * 1000.0)) ? 0.0 : 0.5;
        } else {
            fade = AnimateUtil.animate(
                fade,
                noPosTimer.passed((long) (startFade.get() * 1000.0)) ? 0.0 : 0.5,
                fadeSpeed.get() / 10.0
            );
        }

        if (fade == 0.0) {
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
        cbox = shrink.get() ? cbox.expand(fade) : cbox.expand(0.5);

        if (fill.get()) {
            event.renderer.box(cbox, ColorUtil.scaleAlpha(fillColor.get(), fade * 2.0), ColorUtil.scaleAlpha(fillColor.get(), 0.0), ShapeMode.Sides, 0);
        }

        if (box.get()) {
            event.renderer.box(cbox, ColorUtil.scaleAlpha(boxColor.get(), 0.0), ColorUtil.scaleAlpha(boxColor.get(), fade * 2.0), ShapeMode.Lines, 0);
        }
    }

    /** Draws the target the module is currently working on, in the selected Box/Fill/None shape. */
    private void renderTarget(Render3DEvent event) {
        if (targetEsp.get() == TargetEsp.None || displayTarget == null || currentPos == null) return;

        Vec3d pos = displayTarget.getLerpedPos(event.tickDelta);
        Box targetBox = displayTarget.getDimensions(displayTarget.getPose()).getBoxAt(pos).expand(0.0, 0.1, 0.0);

        event.renderer.box(
            targetBox,
            color.get(),
            outlineColor.get(),
            targetEsp.get() == TargetEsp.Fill ? ShapeMode.Sides : ShapeMode.Both,
            0
        );
    }

    // ---- Enums ----

    public enum Timing {
        All,
        Pre,
        Post
    }

    /**
     * How the target is drawn.
     *
     * <p>The renderer draws boxes only, so the choices are a filled box, an outlined box, or
     * nothing at all.
     */
    public enum TargetEsp {
        Box,
        Fill,
        None
    }
}
