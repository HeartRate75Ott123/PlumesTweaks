package com.plumestweaks.client;

import com.plumestweaks.util.DimensionNames;
import net.minecraft.client.resources.language.I18n;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧维度显示名解析（引用客户端类 {@link I18n}，只能由客户端加载）。
 * <p>
 * 翻译键格式见 {@link DimensionNames#translationKey}；
 * 未定义翻译键时回退为 {@code namespace:path} 原样显示。
 * 解析结果带缓存，GUI 每帧渲染不会反复查语言表。
 */
public final class ClientDimensionNames {

    private static final Map<String, String> CACHE = new HashMap<>();

    private ClientDimensionNames() {
    }

    /** 解析为字符串，未定义翻译键时回退维度 id */
    public static String resolve(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) return "";
        String cached = CACHE.get(dimensionId);
        if (cached != null) return cached;

        String key = DimensionNames.translationKey(dimensionId);
        String translated = I18n.get(key);
        String result = translated.equals(key) ? dimensionId : translated;
        CACHE.put(dimensionId, result);
        return result;
    }

    /** 切换语言/资源包后清缓存 */
    public static void invalidate() {
        CACHE.clear();
    }
}
