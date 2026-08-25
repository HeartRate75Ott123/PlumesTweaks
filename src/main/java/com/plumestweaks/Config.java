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

    public static final ModConfigSpec.BooleanValue FREE_CROSS_DIMENSION_TP = BUILDER
            .comment("Allow vanilla /tp, /teleport and /execute without operator permission (survival, no cheats)",
                     "WARNING: any player can teleport anyone/anywhere across dimensions", "Default: true")
            .define("freeCrossDimensionTp", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
