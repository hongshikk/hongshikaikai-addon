package com.hongshikaikai.addon.utils.crystal;

import net.minecraft.client.MinecraftClient;

/** Shared {@code mc} handle for everything under {@code utils.crystal}. */
public interface Wrapper {
    MinecraftClient mc = MinecraftClient.getInstance();
}
