package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;

/**
 * Keeps the player's inventory handler open after the inventory screen closes.
 *
 * <h2>What it does</h2>
 * Vanilla closes the player's inventory handler the moment the inventory screen goes away, which
 * throws away anything sitting in the 2x2 crafting grid and the 4 armour slots the screen exposes.
 * This module remembers that the last real screen was the inventory and cancels that
 * {@link CloseHandledScreenC2SPacket}, so the server keeps the handler alive and the crafting grid
 * survives the screen being closed.
 *
 * <p>The screen is tracked on {@link TickEvent.Post}; the chat screen, the game menu and Meteor's
 * client screens - every Meteor GUI screen extends {@link WidgetScreen} - are ignored so a menu
 * opened on top of the inventory cannot hide it.
 */
public class XCarry extends Module {
    /** The last screen that was not a client-owned menu; the inventory is the one that matters. */
    private Screen lastScreen;

    public XCarry() {
        super(
            HongShiKaiKai.CATEGORY,
            "XCarry",
            "Keeps the inventory handler open so the crafting grid and armour slots survive the screen closing."
        );
    }

    @Override
    public void onActivate() {
        lastScreen = null;
    }

    /** Remembers the last real screen, so a menu opened on top of the inventory cannot hide it. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        Screen screen = mc.currentScreen;

        if (screen != null && !(screen instanceof GameMenuScreen) && !(screen instanceof ChatScreen) && !(screen instanceof WidgetScreen)) {
            lastScreen = screen;
        }
    }

    /** Refuses the close that would throw the crafting grid away. */
    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (lastScreen instanceof InventoryScreen && event.packet instanceof CloseHandledScreenC2SPacket) {
            event.cancel();
        }
    }
}
