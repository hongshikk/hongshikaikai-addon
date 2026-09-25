package com.hongshikaikai.addon.utils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;

/**
 * Minecraft 1.21.4 的字段写法。
 *
 * <p>1.21.5 把这两处都改了名字 / 收了可见性,所以这个类必须按版本给两份实现:
 * <ul>
 *   <li>{@code Entity.prevYaw/prevPitch} 在 1.21.5 改名为 {@code lastYaw/lastPitch};</li>
 *   <li>{@code PlayerInventory.selectedSlot} 在 1.21.5 从 public 字段变成私有字段,
 *       对外只留 {@code getSelectedSlot()/setSelectedSlot()}。</li>
 * </ul>
 * 这里的成员名都是拿 javap 在对应版本的命名 jar 上核对过的。
 */
public final class VersionCompat {
    private VersionCompat() {
    }

    /**
     * 写入上一 tick 的视角。摄像机每帧用 {@code getYaw(tickDelta)} 从上一 tick 的角度插值,
     * 所以直接把视角按数值瞬移会让画面从上一次的角度扫过去;把 "上一 tick" 一起改掉才是瞬移。
     */
    public static void setPreviousRotation(Entity entity, float yaw, float pitch) {
        entity.prevYaw = yaw;
        entity.prevPitch = pitch;
    }

    /** 当前选中的快捷栏槽位(0-8)。 */
    public static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.selectedSlot;
    }

    /** 只改客户端手里的槽位;要让服务器也知道,得另外发 UpdateSelectedSlot 包。 */
    public static void setSelectedSlot(PlayerInventory inventory, int slot) {
        inventory.selectedSlot = slot;
    }
}
