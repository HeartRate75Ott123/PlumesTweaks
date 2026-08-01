package com.plumestweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CLEAR_ITEMS_RADIUS = BUILDER
            .comment("Default radius for the /clearitems command", "Default: 30, Range: 1 ~ 100")
            .defineInRange("clearItemsRadius", 30, 1, 100);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
