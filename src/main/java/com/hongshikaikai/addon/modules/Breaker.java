package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.utils.crystal.BlockPosX;
import com.hongshikaikai.addon.utils.crystal.BlockUtil;
import com.hongshikaikai.addon.utils.crystal.CombatUtil;
import com.hongshikaikai.addon.utils.crystal.EntityUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Anti-burrow breaker: feeds the cells around the closest enemy into {@code PacketMine+}.
 *
 * <h2>What it does</h2>
 * For the closest enemy it feeds the cells worth breaking into {@code PacketMine+}: first the cell
 * above the head, then the cell the enemy is standing in (a burrow), then the face and the cell
 * below, and finally the surround cells the enemy could be pushed into. Only blocks on the "hard"
 * list count - obsidian, ender chests, netherite, crying obsidian, respawn anchors, ancient debris
 * and anvils - so the module is an anti-burrow tool rather than a general auto-mine.
 *
 * <h2>How it works here</h2>
 * <p>The break position and the second in-flight cell are read from this addon's
 * {@code PacketMine+}, and a cell is started through its public {@code mine} entry point. While
 * Meteor's {@code Blink} is holding packets the module stands down.
 */
public class Breaker extends Module {
    public static Breaker INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player may be and still be broken, in blocks.")
        .defaultValue(6.0)
        .min(0.0)
        .sliderRange(0.0, 8.0)
        .decimalPlaces(1)
        .build()
    );

    public final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far the surround cells may be from the eye, in blocks.")
        .defaultValue(6.0)
        .min(0.0)
        .sliderRange(0.0, 8.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Boolean> burrow = sgGeneral.add(new BoolSetting.Builder()
        .name("burrow")
        .description("Break the cell the enemy is standing in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> head = sgGeneral.add(new BoolSetting.Builder()
        .name("head")
        .description("Break the cell above the enemy's head.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> face = sgGeneral.add(new BoolSetting.Builder()
        .name("face")
        .description("Break the cell the enemy's face is in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> down = sgGeneral.add(new BoolSetting.Builder()
        .name("down")
        .description("Break the cell below the enemy.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> surround = sgGeneral.add(new BoolSetting.Builder()
        .name("surround")
        .description("Break the surround cells the enemy could be pushed into.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> forceDouble = sgGeneral.add(new BoolSetting.Builder()
        .name("force-double")
        .description("Keep cycling cells even while a second cell is already in flight.")
        .defaultValue(false)
        .build()
    );

    /** Blocks worth breaking: the ones a crystal cannot blow away. */
    public static final List<Block> hard = List.of(
        Blocks.OBSIDIAN,
        Blocks.ENDER_CHEST,
        Blocks.NETHERITE_BLOCK,
        Blocks.CRYING_OBSIDIAN,
        Blocks.RESPAWN_ANCHOR,
        Blocks.ANCIENT_DEBRIS,
        Blocks.ANVIL
    );

    public Breaker() {
        super(
            HongShiKaiKai.CATEGORY,
            "Breaker",
            "Feeds the cells around the closest enemy into PacketMine+."
        );
        INSTANCE = this;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (Modules.get().isActive(Blink.class)) return;

        PlayerEntity player = CombatUtil.getClosestEnemy(targetRange.get());
        if (player != null) doBreak(player);
    }

    private void doBreak(PlayerEntity player) {
        BlockPos pos = EntityUtil.getEntityPos(player, true);

        if (PacketMinePlus.getBreakPos() != null
            && !PacketMinePlus.getBreakPos().equals(PacketMinePlus.secondPos)
            && PacketMinePlus.secondPos != null
            && !mc.world.isAir(PacketMinePlus.secondPos)
            && !forceDouble.get()) {
            return;
        }

        double[] yOffset = new double[]{-0.8, 0.3, 1.1};
        double[] xzOffset = new double[]{0.3, -0.3};

        // If the cell already in flight is one this module would have picked, leave it alone.
        for (PlayerEntity entity : CombatUtil.getEnemies(targetRange.get())) {
            for (double y : yOffset) {
                for (double x : xzOffset) {
                    for (double z : xzOffset) {
                        BlockPos offsetPos = new BlockPosX(entity.getX() + x, entity.getY() + y, entity.getZ() + z);
                        if (canBreak(offsetPos) && offsetPos.equals(PacketMinePlus.getBreakPos())) return;
                    }
                }
            }
        }

        List<Float> yList = new ArrayList<>();
        if (down.get()) yList.add(-0.8F);
        if (head.get()) yList.add(2.3F);
        if (burrow.get()) yList.add(0.3F);
        if (face.get()) yList.add(1.1F);

        // A single axis offset first: those are the cells directly in front of, behind and beside the
        // enemy, which are the ones a surround break actually needs.
        for (double y : yList) {
            for (double offset : xzOffset) {
                BlockPos offsetPos = new BlockPosX(player.getX() + offset, player.getY() + y, player.getZ() + offset);
                if (canBreak(offsetPos)) {
                    PacketMinePlus.INSTANCE.mine(offsetPos);
                    return;
                }
            }
        }

        // Then the diagonals.
        for (double y : yList) {
            for (double offsetX : xzOffset) {
                for (double offsetZ : xzOffset) {
                    BlockPos offsetPos = new BlockPosX(player.getX() + offsetZ, player.getY() + y, player.getZ() + offsetX);
                    if (canBreak(offsetPos)) {
                        PacketMinePlus.INSTANCE.mine(offsetPos);
                        return;
                    }
                }
            }
        }

        if (!surround.get()) return;

        // A surround break is only worth it when the cell next to the enemy is a crystal cell that is
        // already open or that this module is about to open.
        for (Direction i : Direction.values()) {
            if (i != Direction.UP
                && i != Direction.DOWN
                && !(Math.sqrt(mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos())) > range.get())
                && (mc.world.isAir(pos.offset(i)) || pos.offset(i).equals(PacketMinePlus.getBreakPos()))
                && canPlaceCrystal(pos.offset(i), false)
                && !pos.offset(i).equals(PacketMinePlus.secondPos)) {
                return;
            }
        }

        ArrayList<BlockPos> list = new ArrayList<>();

        for (Direction i : Direction.values()) {
            if (i != Direction.UP
                && i != Direction.DOWN
                && !(Math.sqrt(mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos())) > range.get())
                && canBreak(pos.offset(i))
                && canPlaceCrystal(pos.offset(i), true)
                && !isSurroundPos(pos.offset(i))) {
                list.add(pos.offset(i));
            }
        }

        if (!list.isEmpty()) {
            PacketMinePlus.INSTANCE.mine(closest(list));
            return;
        }

        for (Direction i : Direction.values()) {
            if (i != Direction.UP
                && i != Direction.DOWN
                && !(Math.sqrt(mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos())) > range.get())
                && canBreak(pos.offset(i))
                && canPlaceCrystal(pos.offset(i), false)) {
                list.add(pos.offset(i));
            }
        }

        if (!list.isEmpty()) PacketMinePlus.INSTANCE.mine(closest(list));
    }

    private BlockPos closest(List<BlockPos> list) {
        return list.stream()
            .min(Comparator.comparingDouble(p -> mc.player.getEyePos().squaredDistanceTo(p.toCenterPos())))
            .orElseThrow();
    }

    private boolean isSurroundPos(BlockPos pos) {
        for (Direction i : Direction.values()) {
            if (i != Direction.UP && i != Direction.DOWN) {
                BlockPos self = EntityUtil.getPlayerPos(true);
                if (self.offset(i).equals(pos)) return true;
            }
        }

        return false;
    }

    /**
     * Whether a crystal could be placed on {@code pos}.
     *
     * <p>{@code block} selects between two variants: with it, only obsidian and bedrock count
     * as a base; without it any block does, which is the looser test the surround pass falls back to.
     */
    public boolean canPlaceCrystal(BlockPos pos, boolean block) {
        BlockPos obsPos = pos.down();
        BlockPos boost = obsPos.up();
        return (BlockUtil.getBlock(obsPos) == Blocks.BEDROCK || BlockUtil.getBlock(obsPos) == Blocks.OBSIDIAN || !block)
            && BlockUtil.noEntityBlockCrystal(boost, true, true)
            && BlockUtil.noEntityBlockCrystal(boost.up(), true, true);
    }

    private boolean isObsidian(BlockPos pos) {
        return mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= PacketMinePlus.INSTANCE.range.get()
            && hard.contains(BlockUtil.getBlock(pos))
            && BlockUtil.getClickSideStrict(pos) != null;
    }

    private boolean canBreak(BlockPos pos) {
        return isObsidian(pos)
            && (BlockUtil.getClickSideStrict(pos) != null || pos.equals(PacketMinePlus.getBreakPos()))
            && (!pos.equals(PacketMinePlus.secondPos)
                || !holdingPickaxe()
                    && !PacketMinePlus.INSTANCE.autoSwitch.get()
                    && !PacketMinePlus.INSTANCE.noGhostHand.get());
    }

    /**
     * Whether a pickaxe is held.
     *
     * <p>The vanilla {@code pickaxes} item tag is what answers: it says the same thing on every
     * version this addon builds for (a {@code PickaxeItem} class only exists up to 1.21.4 - from
     * 1.21.5 tools are plain items carrying a tool component), and it also covers modded pickaxes,
     * not just ones extending the vanilla class.
     */
    private boolean holdingPickaxe() {
        return mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
    }
}
