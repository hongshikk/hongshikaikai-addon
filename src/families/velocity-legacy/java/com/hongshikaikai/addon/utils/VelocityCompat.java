package com.hongshikaikai.addon.utils;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * 1.21.8 及更早的实体速度包写法,对应 {@code src/families/velocity-modern} 里的同名类。
 *
 * <p>1.21.8 及以前(核对回 1.21.4),{@code EntityVelocityUpdateS2CPacket} 把速度拆成
 * {@code getVelocityX()} / {@code getVelocityY()} / {@code getVelocityZ()} 三个 double 访问器;
 * 1.21.10 起它们并成一个 {@code getVelocity()} 返回 {@code Vec3d}。两个世代没有共同的访问器,
 * 所以这份差异只能分世代写,写不出通吃的代码。
 */
public final class VelocityCompat {
    private VelocityCompat() {
    }

    /** 包里携带的速度。分组装回 {@code Vec3d},调用方只跟 {@code Vec3d.ZERO} 比,单位无所谓。 */
    public static Vec3d velocityOf(EntityVelocityUpdateS2CPacket packet) {
        return new Vec3d(packet.getVelocityX(), packet.getVelocityY(), packet.getVelocityZ());
    }
}
