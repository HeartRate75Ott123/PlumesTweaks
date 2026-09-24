package com.plumestweaks.util;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.resources.ResourceLocation;

/**
 * 维度显示名的通用工具（服务端/客户端都可用，不触碰任何客户端类）。
 * <p>
 * 翻译键格式（本模组自带 zh_cn / en_us，资源包可覆盖）：
 * <pre>
 * dimension.&lt;namespace&gt;.&lt;path&gt;
 * </pre>
 * 例：{@code dimension.minecraft.overworld}、{@code dimension.twilightforest.twilight_forest}。
 * 客户端侧解析见 {@code ClientDimensionNames}。
 */
public final class DimensionNames {

    private DimensionNames() {
    }

    /** 翻译键（固定格式；未定义时调用方回退成维度 id） */
    public static String translationKey(ResourceLocation dimensionId) {
        return "dimension." + dimensionId.getNamespace() + "." + dimensionId.getPath();
    }

    public static String translationKey(String dimensionId) {
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        return id == null ? "dimension.plumestweaks.unknown" : translationKey(id);
    }

    /** 列表左侧色块颜色（内置主世界/下界/末地/暮色森林取色，其余按 id 哈希取稳定颜色） */
    public static int accentColor(String dimensionId) {
        if (dimensionId == null) return 0xFF5AC8C8;
        switch (dimensionId) {
            case "minecraft:overworld":
                return 0xFF6FCF6F;
            case "minecraft:the_nether":
                return 0xFFD9603B;
            case "minecraft:the_end":
                return 0xFF9B6FD9;
            case "twilightforest:twilight_forest":
                return 0xFF4FBF7F;
            default:
                if (dimensionId.startsWith(PlumesTweaks.MODID + ":")) {
                    return 0xFF8FE3E3;
                }
                int h = dimensionId.hashCode();
                float hue = Math.floorMod(h, 360) / 360.0f;
                return 0xFF000000 | hsvToRgb(hue, 0.5f, 0.9f);
        }
    }

    private static int hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6.0f) % 6;
        float f = h * 6.0f - (int) (h * 6.0f);
        float p = v * (1.0f - s);
        float q = v * (1.0f - f * s);
        float t = v * (1.0f - (1.0f - f) * s);
        float r;
        float g;
        float b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
