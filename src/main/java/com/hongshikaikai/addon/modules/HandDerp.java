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
 * how the player is <em>drawn</em>. Second, the server keeps the value and re-broadcasts it to
 * everyone <em>else</em>, so nothing here has to be held open: the module only has to keep sending
 * the flip as often as the mode says, because any of the client's own options packets - a change of
 * render distance, of chat visibility, of the language - would otherwise put the real arm back.
 *
 * <h2>Your own model, and F5</h2>
 * What the server broadcasts does not come back: a player is not tracked by themselves, so the
 * entity data carrying the arm is never sent to the client that sent the options packet. The arm
 * this client draws is read off its own entity, and only this client can move it. So the module
 * writes the arm the server was told to the local entity as well, and that write is the only thing
 * that makes the flip visible in third person - there is nothing arriving from the server to do it.
 *
 * <p>The client's own main-hand <em>setting</em> is still left alone; only the entity's arm is ours,
 * and the entity's arm is what the model is drawn from.
 *
 * <h2>Silent</h2>
 * With {@code Silent} on, the arm the client renders is pinned to the client's own setting instead:
 * the entity's arm is put back once a tick whenever it has drifted off that setting. That reaches
 * the end state - the client keeps its own arm - without having to name a tracked-data slot at all;
 * the arm slot is {@code PlayerEntity.MAIN_ARM} up to 1.21.8 and
 * {@code PlayerLikeEntity.MAIN_ARM_ID} after it, so its name is not the same on every version this
 * addon supports.
 *
 * <p>The correction is deliberately a comparison against the value the module means to show rather
 * than a cached flag, so changing the main-hand setting while the module is on is followed instead
 * of fought.
 *
 * <h2>Restoring on disable</h2>
 * The arm the server was told last is sent back when the module is switched off, so the other
 * players see the player's real hand again: the restore runs whenever the last arm the server was
 * told differs from the client's arm now, which is the state the restore is for.
 *
 * <h2>Where the hooks live</h2>
 * The {@code Delay} mode runs from {@code TickEvent.Post}, the end of the client tick, so a switch
 * lands on a tick boundary and the next tick's packets carry the new arm. The {@code Swing} mode
 * runs from {@code PacketEvent.Send}: a switch is sent for every arm swing that goes out, which is
 * every attack and every block break, because vanilla's swing packet is what both of them produce.
 *
 * <h2>Versions</h2>
 * Every class and method this file names was read off the yarn-named jar for each supported version
 * (1.21.4 - 1.21.11) with {@code javap}. The mapping from Mojang's official names to the yarn
 * names used here is not one to one, and three of the four are worth naming:
 *
 * <ul>
 *     <li>the Mojang name {@code ServerboundClientInformationPacket} is
 *         {@link ClientOptionsC2SPacket}, built from {@link SyncedClientOptions} - the same record
 *         with the same nine components on all seven versions, so the packet is rebuilt from
 *         {@code GameOptions.getSyncedOptions()} with only the arm replaced, which keeps every other
 *         field of the player's options exactly as vanilla last sent them;</li>
 *     <li>the Mojang name {@code ServerboundPunchPacket} is {@link HandSwingC2SPacket}. In newer
 *         Minecraft versions the swing is split out into a packet of its own; on 1.21.4 - 1.21.11 a
 *         swing is still this packet, and it is what
 *         {@code ClientPlayerEntity.swingHand} sends, so attacks and block breaks both reach the
 *         {@code Swing} mode;</li>
 *     <li>the Mojang name {@code mc.options.mainHand()} is {@code mc.options.getMainArm()}, a
 *         {@code SimpleOption<Arm>} on all seven versions, and the flip is {@code Arm.getOpposite()},
 *         which is also present on all seven.</li>
 * </ul>
 */
public class HandDerp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder()
        .name("silent")
        .description("Keep your own model on the arm your settings say, instead of drawing it with the flipped arm everyone else is shown.")
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
        .description("Ticks between switches in Delay mode. A value of one switches the arm every tick.")
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
        syncLocalArm();
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

        // The local model is the module's too while it runs, so it is handed back with the server's
        // copy. Leaving it flipped would show the wrong arm until something else happened to move
        // it, which for a purely local value could be never.
        if (mc.player != null) {
            mc.player.setMainArm(current);
        }
    }

    /** The timer half of the module, plus the local-model correction. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        syncLocalArm();

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
     * Puts the arm the module means to show on the local entity.
     *
     * <p>This is the visible half of the module. The arm is a tracked entity value, but a player is
     * not tracked by themselves, so the value the server records and broadcasts to everyone else
     * never arrives back here: without this write the flip would exist on every other client and on
     * none of this one, and F5 would show the unflipped model. {@code Silent} turns the write around
     * and pins the entity to the client's own setting instead.
     *
     * <p>The comparison is against the value to show rather than a cached flag, so a change to the
     * main-hand setting is followed rather than fought while the module is on, and anything else
     * that moves the entity's arm is corrected on the next tick.
     */
    private void syncLocalArm() {
        if (mc.player == null) return;

        Arm shown = silent.get() ? mc.options.getMainArm().getValue() : serverArm;
        if (shown != null && mc.player.getMainArm() != shown) {
            mc.player.setMainArm(shown);
        }
    }

    /** Flips the arm the server is being told about, sends it, and shows it here. */
    private void switchHand() {
        if (serverArm == null) return;

        serverArm = serverArm.getOpposite();
        sendArm(serverArm);
        syncLocalArm();
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

    /** How often the arm is switched. */
    public enum HandMode {
        /** Every {@code delay} ticks. */
        Delay,
        /** Every arm swing that is sent - every attack and every block break. */
        Swing
    }
}
