package com.hongshikaikai.addon.utils.crystal;

/**
 * Which side a swing is reported to.
 *
 * <ul>
 *     <li>{@code All} - the vanilla swing, which animates the arm and sends the packet;</li>
 *     <li>{@code Client} - animate only, send nothing;</li>
 *     <li>{@code Server} - send the swing packet without animating.</li>
 * </ul>
 */
public enum SwingSide {
    All,
    Client,
    Server
}
