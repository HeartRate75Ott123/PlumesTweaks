package com.plumestweaks.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.plumestweaks.PlumesTweaks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首领实体清单加载器（数据包驱动）。
 * <p>
 * 路径：{@code data/plumestweaks/boss_entities/&lt;任意文件名&gt;.json}
 * <p>
 * 格式与 lensouls 模组一致 —— 数组，元素可为字符串 id 或 {@code {"id":"..."}} 对象：
 * <pre>
 * [
 *   { "id": "minecraft:wither" },
 *   { "id": "cataclysm:ignis" }
 * ]
 * </pre>
 * 取代旧版「反射扫描实体类是否有 {@code ServerBossEvent} 字段」的判定方式：
 * 旧方式会把任意持有血条字段的实体（含模组召唤物）误判为 Boss，且无法由整合包作者调整。
 * 现在只有清单内的实体才被视作 Boss。
 */
public class BossEntityLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final String FOLDER = "boss_entities";

    private static volatile Set<ResourceLocation> BOSSES = Set.of();

    public BossEntityLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
        Set<ResourceLocation> set = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            JsonElement json = entry.getValue();
            if (json.isJsonArray()) {
                for (JsonElement el : json.getAsJsonArray()) {
                    String id = null;
                    if (el.isJsonPrimitive()) {
                        id = el.getAsString();
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                        id = el.getAsJsonObject().get("id").getAsString();
                    }
                    addId(set, id, entry.getKey());
                }
            } else if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                if (obj.has("id")) {
                    addId(set, obj.get("id").getAsString(), entry.getKey());
                } else {
                    // 兼容 { "minecraft:wither": {...} } 形态
                    for (String key : obj.keySet()) {
                        addId(set, key, entry.getKey());
                    }
                }
            }
        }
        BOSSES = Set.copyOf(set);
        PlumesTweaks.LOGGER.info("[BossList] 加载了 {} 个首领实体", BOSSES.size());
    }

    private static void addId(Set<ResourceLocation> set, String id, ResourceLocation file) {
        if (id == null || id.isEmpty()) return;
        try {
            set.add(ResourceLocation.tryParse(id.trim()));
        } catch (Exception e) {
            PlumesTweaks.LOGGER.warn("[BossList] 无效 boss id '{}' (文件: {})", id, file);
        }
    }

    /** 当前首领清单快照 */
    public static Set<ResourceLocation> allBosses() {
        return BOSSES;
    }

    /** 实体注册名是否在首领清单中 */
    public static boolean isBossId(ResourceLocation id) {
        return id != null && BOSSES.contains(id);
    }

    /** 实体类型是否在首领清单中 */
    public static boolean isBoss(EntityType<?> type) {
        // 1.20.1 用 Forge 注册表取 key（BuiltInRegistries.ENTITY_TYPE 在 Forge 侧已标记废弃）
        return type != null && isBossId(ForgeRegistries.ENTITY_TYPES.getKey(type));
    }

    /**
     * 实体是否应触发「附近有 Boss」限制。
     * <p>
     * 1.20.1 侧只按名单判定（lensouls 没有 1.20.1 版本，故不做幻灵放行）。
     */
    public static boolean countsAsBoss(Entity entity) {
        return entity != null && entity.isAlive() && isBoss(entity.getType());
    }

    /** 半径内是否存在应触发限制的 Boss 实体 */
    public static boolean hasBossNearby(Level level, Entity center, double radius) {
        List<? extends Entity> found = level.getEntitiesOfClass(Entity.class,
                center.getBoundingBox().inflate(radius),
                e -> e != center && countsAsBoss(e));
        return !found.isEmpty();
    }
}
