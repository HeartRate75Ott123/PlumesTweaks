package com.plumestweaks;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置。
 * <p>
 * 1.20.1 侧保留 {@code server} 类型，因为 {@code RIFT_ALLOW_TWILIGHT_PORTAL}
 * 影响的是服务端的建门判定。
 */
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue RIFT_ALLOW_TWILIGHT_PORTAL = BUILDER
            .comment("Allow building Twilight Forest portals inside the Dimensional Rift dimension",
                     "Twilight Forest only allows its portal in the origin dimension by default;",
                     "this opens it for the rift only, without touching TF's global switch.",
                     "Default: true")
            .define("riftAllowTwilightPortal", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
