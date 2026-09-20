package com.plumestweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CLEAR_ITEMS_RADIUS = BUILDER
            .comment("Default radius for the /clearitems command", "Default: 30, Range: 1 ~ 100")
            .defineInRange("clearItemsRadius", 30, 1, 100);

    public static final ModConfigSpec.BooleanValue AUTO_XAERO_WAYPOINT = BUILDER
            .comment("Auto-add a Xaero waypoint when Nature's/Explorer's Compass finds a target",
                     "Requires Xaero's Minimap (and/or World Map) installed on the client", "Default: true")
            .define("autoXaeroWaypoint", true);

    /** 命令解禁总开关 */
    public static final ModConfigSpec.BooleanValue FREE_CROSS_DIMENSION_TP = BUILDER
            .comment("Master switch: allow /execute, /place and FTB Quests /ftbquests without operator permission",
                     "Each command can still be toggled separately below", "Default: true")
            .define("freeCrossDimensionTp", true);

    public static final ModConfigSpec.BooleanValue FREE_EXECUTE = BUILDER
            .comment("Allow vanilla /execute without operator permission",
                     "Requires the master switch 'freeCrossDimensionTp' to be enabled", "Default: true")
            .define("freeExecute", true);

    public static final ModConfigSpec.BooleanValue FREE_FTBQUESTS = BUILDER
            .comment("Allow FTB Quests /ftbquests (all subcommands) without operator permission",
                     "Requires the master switch 'freeCrossDimensionTp' to be enabled", "Default: true")
            .define("freeFtbQuests", true);

    public static final ModConfigSpec.BooleanValue FREE_PLACE = BUILDER
            .comment("Allow vanilla /place without operator permission",
                     "Requires the master switch 'freeCrossDimensionTp' to be enabled", "Default: true")
            .define("freePlace", true);

    public static final ModConfigSpec.BooleanValue FREE_COMPASS_TELEPORT = BUILDER
            .comment("Allow Nature's/Explorer's Compass teleport button without operator permission",
                     "When disabled, the original (cheat mode or OP) requirement is restored", "Default: true")
            .define("freeCompassTeleport", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
