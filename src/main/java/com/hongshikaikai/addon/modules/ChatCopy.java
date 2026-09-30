package com.hongshikaikai.addon.modules;

import com.hongshikaikai.addon.HongShiKaiKai;
import com.hongshikaikai.addon.mixin.ChatHudAccessor;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Puts chat on the clipboard, because Minecraft's chat is drawn text with no way to select it:
 * everything said in chat is either retyped by hand or never shared at all.
 *
 * <h2>One message: right click it</h2>
 * Right clicking a line in the open chat screen copies the message under the cursor and nothing
 * else. A message that wrapped over several lines is copied as the one message it is, with the text
 * taken from the logical message rather than from the drawing, so no words are glued together at the
 * wrap points.
 *
 * <p>Finding that message is the chat HUD's own layout arithmetic, done here instead of by calling
 * into it: the cursor is converted into a line offset from the bottom of the chat
 * ({@code (scaledHeight - mouseY - 40) / (scale * lineHeight)}), {@code scrolledLines} is added so the
 * scrolled-back case is the same case, and the {@code endOfEntry} flag - true on the last drawn line
 * of each message - marks where one message ends and the next begins. This is the same arithmetic as
 * {@code ChatHud#getTextStyleAt}, which is why it is written out by hand: that method, together with
 * the whole hit-testing half of the chat HUD, was removed in 1.21.11, while the layout it used is
 * unchanged on every version from 1.21.4 to 1.21.11. The three private fields it needs come from
 * {@link ChatHudAccessor}, whose names are this addon's own and therefore the same on every version.
 *
 * <h2>The whole log: Ctrl + right click</h2>
 * Holding Ctrl while right clicking copies the whole log instead of one message. That path does not
 * touch the chat HUD at all: Meteor posts {@code ReceiveMessageEvent} for every message that reaches
 * the chat HUD - server chat and the addon's own output alike - before that message is added, so
 * listening to it is enough and the last few hundred plain strings are kept in a ring buffer, with
 * formatting stripped by taking {@code Text#getString()}. {@code filter} narrows what that copy
 * keeps and {@code lines} caps how far back it reaches, which is what makes it possible to copy just
 * one module's debug output. {@code seed-from-log} fills the ring buffer from
 * {@code logs/latest.log} on activation, so a module that is switched on after something interesting
 * scrolled past can still be copied from.
 *
 * <h2>Why the button is polled</h2>
 * The right mouse button is read straight from GLFW rather than taken from a Minecraft event: the
 * input APIs moved twice inside the version range this addon builds for (Meteor's mouse event is
 * {@code MouseButtonEvent} up to 1.21.8 and {@code MouseClickEvent} after, and vanilla's
 * {@code Element.mouseClicked} changed shape the same way), and a polled button is the same answer on
 * every version. Ctrl is polled the same way, for the same reason.
 *
 * <p>Nothing is sent anywhere: the buffer lives in memory and the copy goes to this client's
 * clipboard. The one file the module reads is {@code logs/latest.log}, on activation only.
 */
public class ChatCopy extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines")
        .description("How many of the most recent matching chat messages the Ctrl + right click copy takes.")
        .defaultValue(60)
        .min(1)
        .max(300)
        .sliderRange(5, 200)
        .build()
    );

    private final Setting<String> filter = new StringSetting.Builder()
        .name("filter")
        .description("The Ctrl + right click copy keeps only messages containing this text, case insensitive. Empty keeps everything. Use the module prefix (nofall+) to copy just that module's debug output.")
        .defaultValue("")
        .build();

    private final Setting<Boolean> rightClick = sgGeneral.add(new BoolSetting.Builder()
        .name("right-click")
        .description("Right click a message in the open chat screen to copy that message, or hold Ctrl and right click to copy the whole log. Vanilla does nothing with that click.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> seedFromLog = sgGeneral.add(new BoolSetting.Builder()
        .name("seed-from-log")
        .description("On activation, read the chat lines out of logs/latest.log so messages from before the module was on can be copied too.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> feedback = sgGeneral.add(new BoolSetting.Builder()
        .name("feedback")
        .description("Say in chat what was copied.")
        .defaultValue(true)
        .build()
    );

    /** Newest last. Sized for a few minutes of busy chat, which is far more than anyone copies. */
    private final Deque<String> buffer = new ArrayDeque<>();
    private static final int CAPACITY = 300;
    /** Clipboard text is capped so a runaway chat cannot produce a pathological paste. */
    private static final int MAX_CHARS = 60_000;
    /** How much of the log tail to scan when seeding. */
    private static final int LOG_TAIL_BYTES = 256 * 1024;

    /** True while the right mouse button was down on the previous tick, for edge detection. */
    private boolean rightClickWasDown;

    public ChatCopy() {
        super(HongShiKaiKai.CATEGORY_TOOLS, "chatcopy", "Right click a message to copy that message, Ctrl + right click to copy the whole chat log.");
    }

    @Override
    public void onActivate() {
        buffer.clear();
        rightClickWasDown = false;
        seedFromLog();
    }

    @Override
    public void onDeactivate() {
        rightClickWasDown = false;
    }

    /** Every message the chat HUD is about to show, the addon's own included. */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        push(event.getMessage().getString());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        pollRightClick();
    }

    /**
     * Right click in the open chat screen copies what is under the cursor: the message there, or the
     * whole log while Ctrl is held. Both are edge detected, so one copy per press.
     */
    private void pollRightClick() {
        long window = mc.getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (down && !rightClickWasDown && rightClick.get() && mc.currentScreen instanceof ChatScreen) {
            if (isCtrlDown(window)) copy();
            else copyHovered();
        }

        rightClickWasDown = down;
    }

    /** Either Ctrl counts, because either one is what the player is holding. */
    private static boolean isCtrlDown(long window) {
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /**
     * Puts the message under the cursor on the clipboard. The text comes from the logical message
     * list, so a message that wrapped over several drawn lines is copied once, in reading order, with
     * the spaces the wrapping ate restored.
     */
    private void copyHovered() {
        String message = hoveredMessage();

        if (message == null || message.isEmpty()) {
            if (feedback.get()) info("No chat message under the cursor.");
            return;
        }

        mc.keyboard.setClipboard(message);
        if (feedback.get()) info("Copied the message under the cursor to the clipboard.");
    }

    /**
     * The message under the cursor in the open chat screen, or null when the cursor is not over one.
     * This is vanilla's hit test, rewritten because 1.21.11 deleted its original.
     */
    private String hoveredMessage() {
        ChatHud chatHud = mc.inGameHud.getChatHud();
        ChatHudAccessor access = (ChatHudAccessor) (Object) chatHud;
        List<ChatHudLine.Visible> visible = access.hskk$getVisibleMessages();
        if (visible.isEmpty()) return null;

        // Chat HUD geometry, all of it from options that exist unchanged on every supported version.
        double scale = mc.options.getChatScale().getValue();
        int lineHeight = (int) (9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0));
        double width = Math.floor(mc.options.getChatWidth().getValue() * 280.0 + 40.0);
        int height = (int) Math.floor(mc.options.getChatHeightFocused().getValue() * 160.0 + 20.0);

        // The cursor in the chat's own frame: x is inset by the 4px the chat is drawn at, y is
        // measured up from the chat's bottom edge, which sits 40px above the bottom of the screen.
        // mc.mouse is in window pixels, while the chat lays itself out in scaled GUI pixels - the
        // same conversion vanilla does before it hands a screen its mouseX/mouseY.
        double mouseX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        double mouseY = mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();
        double lineX = mouseX / scale - 4.0;
        double lineY = (mc.getWindow().getScaledHeight() - mouseY - 40.0) / (scale * lineHeight);

        if (lineX < -4.0 || lineX > Math.floor(width / scale)) return null;

        int shown = Math.min(height / lineHeight, visible.size());
        if (lineY < 0.0 || lineY >= shown) return null;

        int index = (int) Math.floor(lineY + access.hskk$getScrolledLines());
        if (index < 0 || index >= visible.size()) return null;

        // endOfEntry marks the bottom drawn line of each message, so walking down from the cursor's
        // own line lands on the bottom of the message the cursor is inside.
        int entry = index;
        while (entry >= 0 && !visible.get(entry).endOfEntry()) entry--;
        if (entry < 0) return null;

        // Counting those bottom markers up to that line says how many messages are newer than this
        // one, which is exactly its index in the logical list: both lists are trimmed from the same
        // stream, so a message that is still drawn always has its entry still listed.
        int newer = 0;
        for (int i = 0; i <= entry; i++) {
            if (visible.get(i).endOfEntry()) newer++;
        }

        List<ChatHudLine> messages = access.hskk$getMessages();
        if (newer < 1 || newer > messages.size()) return null;

        return messages.get(newer - 1).content().getString();
    }

    /** Puts the filtered tail of the buffer on the clipboard. */
    public void copy() {
        String needle = filter.get().trim().toLowerCase(Locale.ROOT);

        List<String> matches = new ArrayList<>();
        for (String line : buffer) {
            if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(needle)) matches.add(line);
        }

        if (matches.isEmpty()) {
            if (feedback.get()) {
                info("Nothing to copy%s.", needle.isEmpty() ? "" : " matched \"" + filter.get() + "\"");
            }
            return;
        }

        int from = Math.max(0, matches.size() - lines.get());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < matches.size(); i++) {
            String line = matches.get(i);
            if (sb.length() + line.length() + 1 > MAX_CHARS) break;
            sb.append(line).append('\n');
        }

        mc.keyboard.setClipboard(sb.toString());

        if (feedback.get()) {
            info("Copied %d line(s) to the clipboard%s.", matches.size() - from,
                needle.isEmpty() ? "" : " matching \"" + filter.get() + "\"");
        }
    }

    private void push(String message) {
        if (message == null || message.isEmpty()) return;
        buffer.addLast(message);
        while (buffer.size() > CAPACITY) buffer.removeFirst();
    }

    /**
     * Reads the chat lines already in this session's log, so a module that was switched on late can
     * still be copied from. Minecraft logs every chat line as {@code [CHAT] ...}, which is written
     * for messages this addon prints as well.
     */
    private void seedFromLog() {
        if (!seedFromLog.get()) return;

        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) return;

        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0, length - LOG_TAIL_BYTES);
            file.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            file.readFully(bytes);

            String[] split = new String(bytes, StandardCharsets.UTF_8).split("\r?\n");
            List<String> chats = new ArrayList<>();
            // The first line is a partial one unless the whole file was read.
            for (int i = start == 0 ? 0 : 1; i < split.length; i++) {
                int index = split[i].indexOf("[CHAT] ");
                if (index >= 0) chats.add(split[i].substring(index + 7));
            }

            int from = Math.max(0, chats.size() - CAPACITY);
            for (int i = from; i < chats.size(); i++) push(chats.get(i));

            if (feedback.get() && !chats.isEmpty()) {
                info("Seeded %d chat line(s) from logs/latest.log.", chats.size() - from);
            }
        } catch (Exception e) {
            if (feedback.get()) warning("Could not read logs/latest.log: %s", e.getMessage());
        }
    }
}
