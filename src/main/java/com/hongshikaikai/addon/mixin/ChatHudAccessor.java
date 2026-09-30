package com.hongshikaikai.addon.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Opens up the three pieces of chat HUD state ChatCopy needs to work out which message the cursor is
 * over: the wrapped lines that are actually drawn, the logical messages behind them, and how far the
 * player has scrolled back.
 *
 * <p>Meteor has accessors for the first two, but their method names changed between the versions
 * this addon builds for ({@code getVisibleMessages()} became {@code meteor$getVisibleMessages()}),
 * so an addon cannot call them portably. The <em>field</em> names, on the other hand, are the same
 * on every version from 1.21.4 to 1.21.11 ({@code visibleMessages}, {@code messages},
 * {@code scrolledLines}), so the accessors here - whose names are ours - compile everywhere.
 */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
    /** The messages as drawn: one entry per wrapped line, newest first, bottom of the chat first. */
    @Accessor("visibleMessages")
    List<ChatHudLine.Visible> hskk$getVisibleMessages();

    /** The messages as received: one entry per logical message, newest first. */
    @Accessor("messages")
    List<ChatHudLine> hskk$getMessages();

    /** How many lines the chat is scrolled back by. */
    @Accessor("scrolledLines")
    int hskk$getScrolledLines();
}
