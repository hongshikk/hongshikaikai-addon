package com.hongshikaikai.addon.utils.crystal;

/** Hard-coded global settings that the crystal code reads. */
public final class CrystalConfig {
    private CrystalConfig() {
    }

    // ---- AntiCheat.General ----

    /** Refuse to place into a cell this session has already placed into. Default: true. */
    public static boolean multiPlace = true;

    /** Send block interactions as packets instead of letting the client predict them. Default: true. */
    public static boolean packetPlace = true;

    /** Entity interaction range used when a crystal is ignored for blocking a placement. Default: 3.0. */
    public static double ieRange = 3.0;

    /** Search the hotbar first when looking for an item in the whole inventory. */
    public static boolean priorHotbar = false;

    /** Close the handled screen after an inventory swap, so a server-side inventory holder does not linger. */
    public static boolean closeScreen = false;

    /** Armour stands do not block a placement. Default: false. */
    public static boolean ignoreArmorStand = false;

    /** How the swing that accompanies an interaction is reported. */
    public static SwingSide interactSwing = SwingSide.All;

    /** How the swing that accompanies an attack is reported. */
    public static SwingSide attackSwing = SwingSide.All;

    /** Minimum seconds between crystal attacks. Default: 0.2. */
    public static double attackDelay = 0.2;

    /** Rotate towards the crystal before attacking it. Default: false. */
    public static boolean attackRotate = false;

    // ---- ClientSetting.Game ----

    /** Optimised (12-sample) exposure calc. Default: false, i.e. the exact per-axis sweep. */
    public static boolean optimizedCalc = false;

    /** Target a 1.12 server, which has no upper crystal cell. Default: false. */
    public static boolean lowVersion = false;

    /** Reset the values the two crystal modules intentionally do not expose as settings. */
    public static void reset() {
        multiPlace = true;
        packetPlace = true;
        ieRange = 3.0;
        priorHotbar = false;
        closeScreen = false;
        ignoreArmorStand = false;
        interactSwing = SwingSide.All;
        attackSwing = SwingSide.All;
        attackDelay = 0.2;
        attackRotate = false;
        optimizedCalc = false;
        lowVersion = false;
    }
}
