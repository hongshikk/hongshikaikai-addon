package com.hongshikaikai.addon.utils;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * 1.21.10 起的实体速度包写法,对应 {@code src/families/velocity-legacy} 里的同名类。
 *
 * <p>1.21.10 把 {@code getVelocityX()} / {@code getVelocityY()} / {@code getVelocityZ()} 三个
 * double 访问器换成了一个 {@code getVelocity()},返回 {@code Vec3d}。1.21.10 和 1.21.11 都是这套
 * 写法。两个世代没有共同的访问器,所以这份差异只能分世代写,写不出通吃的代码。
 */
public final class VelocityCompat {
    private VelocityCompat() {
    }

    /** 包里携带的速度。调用方只跟 {@code Vec3d.ZERO} 比,单位无所谓。 */
    public static Vec3d velocityOf(EntityVelocityUpdateS2CPacket packet) {
        return packet.getVelocity();
    }
}
