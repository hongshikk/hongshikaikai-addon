package com.hongshikaikai.addon.utils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;

/**
 * Minecraft 1.21.5 起的字段写法,对应 {@code src/families/compat-legacy} 里的同名类。
 *
 * <p>1.21.5 把 {@code Entity.prevYaw/prevPitch} 改名为 {@code lastYaw/lastPitch},并把
 * {@code PlayerInventory.selectedSlot} 收了可见性、改成走 {@code getSelectedSlot()} /
 * {@code setSelectedSlot(int)}。1.21.5 及以后各版本(核对到 1.21.11)都是这套写法。
 */
public final class VersionCompat {
    private VersionCompat() {
    }

    /**
     * 写入上一 tick 的视角。摄像机每帧用 {@code getYaw(tickDelta)} 从上一 tick 的角度插值,
     * 所以直接把视角按数值瞬移会让画面从上一次的角度扫过去;把 "上一 tick" 一起改掉才是瞬移。
     */
    public static void setPreviousRotation(Entity entity, float yaw, float pitch) {
        entity.lastYaw = yaw;
        entity.lastPitch = pitch;
    }

    /** 当前选中的快捷栏槽位(0-8)。 */
    public static int getSelectedSlot(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    /** 只改客户端手里的槽位;要让服务器也知道,得另外发 UpdateSelectedSlot 包。 */
    public static void setSelectedSlot(PlayerInventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }
}
