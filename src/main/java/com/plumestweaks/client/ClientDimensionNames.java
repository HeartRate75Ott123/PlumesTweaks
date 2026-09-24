package com.plumestweaks.client;

import com.plumestweaks.util.DimensionNames;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧维度显示名解析（引用了 {@code @OnlyIn(CLIENT)} 的 {@link I18n}，
 * 因此必须与服务端代码隔离，只能由客户端类加载）。
 * <p>
 * 翻译键格式见 {@link DimensionNames#translationKey}。
 * 未定义翻译键时回退为 {@code namespace:path} 原样显示。
 * 解析结果带缓存，GUI 每帧渲染不会反复查语言表。
 */
public final class ClientDimensionNames {

    private static final Map<String, String> CACHE = new HashMap<>();

    private ClientDimensionNames() {}

    /** 解析为字符串，未定义翻译键时回退维度 id */
    public static String resolve(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) return "";
        return CACHE.computeIfAbsent(dimensionId, id -> {
            String key = DimensionNames.translationKey(id);
            String translated = I18n.get(key);
            return translated.equals(key) ? id : translated;
        });
    }

    /** 已带记录名（服务端写入）时优先用记录名；为空才按 id 现解析 */
    public static String resolve(String dimensionId, String recordedName) {
        if (recordedName != null && !recordedName.isBlank()) return recordedName;
        return resolve(dimensionId);
    }

    /** 切换语言/资源包后清缓存 */
    public static void invalidate() {
        CACHE.clear();
    }
}
