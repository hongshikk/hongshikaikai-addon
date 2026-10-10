package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Arm;

import java.util.Locale;

/**
 * HandDerp: tells the server that the player's main hand is the other one, over and over.
 *
 * <p>This is a port of LiquidBounce's {@code ModuleHandDerp} (Kotlin, GPL-3.0). The decisions are
 * the LiquidBounce ones: the same {@code Silent} option, the same {@code Delay} and {@code Swing}
 * modes, the same default delay of one tick, and the same restore-the-arm-on-disable step. Where
 * the port departs from the source, the method that departs says so.
 *
 * <h2>The shape of the trick</h2>
 * The arm the server believes a player uses is not read off the player entity - it arrives in the
 * client's own options packet. So the module does not touch the player at all: it builds the
 * options packet the client would normally send, swaps the main arm for the other one, and sends
 * that. The server records the new arm and starts broadcasting it to everyone, which means the
 * other players' clients render the player's sword in the other hand.
 *
 * <p>Two things follow from that, and both are worth being exact about. First, the arm is not a
 * combat value: which hand swings, which hand holds the shield and which hand the item is in are
 * decided by the inventory and by the swing packets, not by this field. What the field changes is
 * how the player is <em>drawn</em> to everybody else. Second, the server keeps the value and
 * re-broadcasts it, so nothing here has to be held open: the module only has to keep sending the
 * flip as often as the mode says, because any of the client's own options packets - a change of
 * render distance, of chat visibility, of the language - would otherwise put the real arm back.
 *
 * <p>The module does not write the client's own setting, so the arm you see yourself with never
 * changes; what changes is what everyone else sees. That asymmetry is what the source's
 * {@code Silent} option is about - without it the server's broadcast comes back to you and flips
 * your own model too, because the arm is also a tracked entity value.
 *
 * <h2>Silent</h2>
 * With {@code Silent} on, the arm the client renders is pinned to the client's own setting: the
 * entity's arm is put back whenever the server's broadcast has moved it. LiquidBounce instead
 * cancels the broadcast at the packet level, and it decides which packet to cancel by comparing
 * the tracked entry's <em>index</em> with the numeric id of the arm - which is a comparison between
 * an entity-data slot number and an arm number, so on the versions this addon builds for it matches
 * the shared entity flags slot or the air slot rather than the main-arm slot, and cancelling those
 * would be far worse than the round trip it is trying to prevent. The intent is kept and the test
 * is not: the arm is restored on the entity once a tick, which reaches the same end state - the
 * client keeps its own arm - without having to name a tracked-data slot that is
 * {@code PlayerEntity.MAIN_ARM} up to 1.21.8 and {@code PlayerLikeEntity.MAIN_ARM_ID} after it.
 *
 * <p>The restore is deliberately a comparison against the option rather than a stored copy, so
 * changing the main-hand setting while the module is on is followed instead of fought.
 *
 * <h2>Restoring on disable</h2>
 * The arm the server was told last is sent back when the module is switched off, so the other
 * players see the player's real hand again. LiquidBounce only does that if the client's option has
 * moved away from the value it captured when the module was built - which, since nothing in the
 * module changes the option, is a condition that cannot become true. The port restores
 * unconditionally when the last arm the server was told differs from the client's arm now, which is
 * the state the restore is for.
 *
 * <h2>Where the hooks live</h2>
 * The {@code Delay} mode runs from {@code TickEvent.Post}, the end of the client tick, so a switch
 * lands on a tick boundary and the next tick's packets carry the new arm. The {@code Swing} mode
 * runs from {@code PacketEvent.Send}: a switch is sent for every arm swing that goes out, which is
 * every attack and every block break, because vanilla's swing packet is what both of them produce.
 *
 * <h2>Versions</h2>
 * Every class and method this file names was read off the yarn-named jar for each supported version
 * (1.21.4 - 1.21.11) with {@code javap}. The mapping from the source's Mojang names is not one to
 * one, and three of the four are worth naming:
 *
 * <ul>
 *     <li>the source's {@code ServerboundClientInformationPacket} is
 *         {@link ClientOptionsC2SPacket}, built from {@link SyncedClientOptions} - the same record
 *         with the same nine components on all seven versions, so the packet is rebuilt from
 *         {@code GameOptions.getSyncedOptions()} with only the arm replaced, which keeps every other
 *         field of the player's options exactly as vanilla last sent them;</li>
 *     <li>the source's {@code ServerboundPunchPacket} is {@link HandSwingC2SPacket}. The source
 *         targets a Minecraft newer than any this addon builds for, where the swing was split out
 *         into a packet of its own; on 1.21.4 - 1.21.11 a swing is still this packet, and it is what
 *         {@code ClientPlayerEntity.swingHand} sends, so attacks and block breaks both reach the
 *         {@code Swing} mode;</li>
 *     <li>the source's {@code mc.options.mainHand()} is {@code mc.options.getMainArm()}, a
 *         {@code SimpleOption<Arm>} on all seven versions, and the flip is {@code Arm.getOpposite()},
 *         which is also present on all seven.</li>
 * </ul>
 */
public class HandDerp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder()
        .name("silent")
        .description("Keep your own model on the arm your settings say, instead of letting the server's broadcast flip it back and forth with the arm everyone else is shown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<HandMode> mode = sgGeneral.add(new EnumSetting.Builder<HandMode>()
        .name("mode")
        .description("Delay switches the arm on a timer. Swing switches it every time an arm swing is sent, which is every attack and every block break.")
        .defaultValue(HandMode.Delay)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between switches in Delay mode. The source's default is one, which switches on every tick.")
        .defaultValue(1)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .visible(() -> mode.get() == HandMode.Delay)
        .build()
    );

    /**
     * The arm the server was last told about, or null when the module has not been switched on in a
     * world yet. Starts at the client's own arm, which is what the server believes before the first
     * switch, and is flipped from there.
     */
    private Arm serverArm;

    /** Ticks counted since the last switch in Delay mode. */
    private int ticksSinceSwitch;

    public HandDerp() {
        super(HongShiKaiKai.CATEGORY, "HandDerp", "Tells the server your main hand is the other one, so everyone else sees the sword in the wrong hand.");
    }

    @Override
    public void onActivate() {
        serverArm = mc.options.getMainArm().getValue();
        ticksSinceSwitch = 0;
    }

    @Override
    public void onDeactivate() {
        // Give the server the player's real arm back. MC keeps no record of what the server was
        // told, so the module does; without this the other players would keep seeing the arm the
        // module happened to stop on until the client sent an options packet for some other reason.
        Arm current = mc.options.getMainArm().getValue();

        if (serverArm != null && serverArm != current) {
            sendArm(current);
        }

        serverArm = null;
        ticksSinceSwitch = 0;
    }

    /** The timer half of the module, plus the {@code Silent} correction. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        keepOwnArm();

        if (mode.get() != HandMode.Delay) return;

        // waitTicks(delay) then switch, over and over: with the default delay of 1 that is a switch
        // per tick, and a delay of 0 does not wait less than that because a tick is the smallest
        // step there is.
        ticksSinceSwitch++;

        if (ticksSinceSwitch < delay.get()) return;

        ticksSinceSwitch = 0;
        switchHand();
    }

    /** The swing half of the module. */
    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mode.get() != HandMode.Swing) return;
        if (!(event.packet instanceof HandSwingC2SPacket)) return;

        switchHand();
    }

    /**
     * Puts the client's own arm back on the entity if the server's broadcast moved it. This is the
     * port's {@code Silent}: it runs at the end of the tick, after the network has been processed
     * and before the frame is drawn, so the correction is not visible.
     *
     * <p>The comparison is against the option, so the setting is followed rather than cached. When
     * the two already agree this is a pair of reads and nothing else.
     */
    private void keepOwnArm() {
        if (!silent.get()) return;

        Arm option = mc.options.getMainArm().getValue();
        if (mc.player.getMainArm() != option) {
            mc.player.setMainArm(option);
        }
    }

    /** Flips the arm the server is being told about and sends it. */
    private void switchHand() {
        if (serverArm == null) return;

        serverArm = serverArm.getOpposite();
        sendArm(serverArm);
    }

    /**
     * Sends the client's own options packet with the main arm replaced.
     *
     * <p>Everything else comes from {@code GameOptions.getSyncedOptions()}, which builds the record
     * from the live option values on every call, so the view distance, the chat settings, the
     * model parts, the text filtering and the particle mode are the ones the client is actually
     * using. Only the arm is ours.
     */
    private void sendArm(Arm arm) {
        if (mc.getNetworkHandler() == null) return;

        SyncedClientOptions current = mc.options.getSyncedOptions();

        mc.getNetworkHandler().sendPacket(new ClientOptionsC2SPacket(new SyncedClientOptions(
            current.language(),
            current.viewDistance(),
            current.chatVisibility(),
            current.chatColorsEnabled(),
            current.playerModelParts(),
            arm,
            current.filtersText(),
            current.allowsServerListing(),
            current.particleStatus()
        )));
    }

    @Override
    public String getInfoString() {
        return serverArm == null ? null : serverArm.name().toLowerCase(Locale.ROOT);
    }

    /** How often the arm is switched. The names are LiquidBounce's mode names. */
    public enum HandMode {
        /** Every {@code delay} ticks. */
        Delay,
        /** Every arm swing that is sent - every attack and every block break. */
        Swing
    }
}
