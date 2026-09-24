package com.plumestweaks.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时空裂隙的「多维出口点」数据 —— 记录玩家从裂隙**外**各维度进入裂隙时的坐标。
 * <p>
 * 语义要点：
 * <ul>
 *   <li>键为维度 id 字符串，值为该维度最近一次进入裂隙的落点；</li>
 *   <li>只记录裂隙以外的维度（裂隙自身的落点由物品上的
 *       {@link RiftEnterData} 负责，右键潜行修改，二者互不影响）；</li>
 *   <li>从裂隙传送出去**不会**清除记录，只有下次从该维度再次进入才覆盖；</li>
 *   <li>用 {@link LinkedHashMap} 保持插入顺序，GUI 按「最近进入」倒序展示。</li>
 * </ul>
 * 附着在裂隙道具上，随物品跨维度/跨会话持久保存。
 */
public record RiftExitData(Map<String, RiftExitEntry> entries) {

    public static final RiftExitData EMPTY = new RiftExitData(Map.of());

    public static final Codec<RiftExitData> CODEC = RecordCodecBuilder.create(
            inst -> inst.group(
                    Codec.unboundedMap(Codec.STRING, RiftExitEntry.CODEC)
                            .optionalFieldOf("entries", Map.of())
                            .forGetter(RiftExitData::entries)
            ).apply(inst, RiftExitData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RiftExitData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.map(java.util.LinkedHashMap::new,
                            ByteBufCodecs.STRING_UTF8, RiftExitEntry.STREAM_CODEC),
                    RiftExitData::entries,
                    RiftExitData::new
            );

    public RiftExitData {
        entries = java.util.Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public RiftExitEntry get(String dimensionId) {
        return entries.get(dimensionId);
    }

    /** 返回一个新增/覆盖了指定维度记录的新实例（原实例不变） */
    public RiftExitData with(RiftExitEntry entry) {
        Map<String, RiftExitEntry> copy = new LinkedHashMap<>(entries);
        copy.remove(entry.dimensionId());
        copy.put(entry.dimensionId(), entry);
        return new RiftExitData(copy);
    }

    /** 返回一个移除了指定维度记录的新实例 */
    public RiftExitData without(String dimensionId) {
        if (!entries.containsKey(dimensionId)) return this;
        Map<String, RiftExitEntry> copy = new LinkedHashMap<>(entries);
        copy.remove(dimensionId);
        return new RiftExitData(copy);
    }

    /** 按插入顺序（越晚进入的越靠后）导出的不可变列表 */
    public java.util.List<RiftExitEntry> ordered() {
        return java.util.List.copyOf(entries.values());
    }

    /** 按插入顺序倒序（最近进入的排最前），供 GUI 列表使用 */
    public java.util.List<RiftExitEntry> newestFirst() {
        java.util.List<RiftExitEntry> list = new java.util.ArrayList<>(entries.values());
        java.util.Collections.reverse(list);
        return java.util.List.copyOf(list);
    }
}
