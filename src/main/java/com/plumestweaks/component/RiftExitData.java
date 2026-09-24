package com.plumestweaks.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时空裂隙的「多维出口点」数据集合：记录玩家从裂隙**外**各维度进入裂隙时的坐标。
 * <p>
 * 语义要点：
 * <ul>
 *   <li>键为维度 id 字符串，值为该维度最近一次进入裂隙的落点；</li>
 *   <li>只记录裂隙以外的维度（裂隙自身的落点由 `rift_enter` 负责，二者互不影响）；</li>
 *   <li>从裂隙传送出去**不会**清除记录，只有下次从该维度再次进入才覆盖；</li>
 *   <li>用 LinkedHashMap 保持插入顺序。</li>
 * </ul>
 * 1.20.1 侧以 CompoundTag 存进物品 NBT（键 {@code plumestweaks:rift_exits}）。
 */
public final class RiftExitData {

    public static final String NBT_KEY = "plumestweaks:rift_exits";
    private static final String TAG_ENTRIES = "entries";

    public static final RiftExitData EMPTY = new RiftExitData(Collections.emptyMap());

    private final Map<String, RiftExitEntry> entries;

    public RiftExitData(Map<String, RiftExitEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    // ========== NBT ==========

    public static RiftExitData load(CompoundTag parent) {
        if (parent == null || !parent.contains(NBT_KEY, Tag.TAG_COMPOUND)) return EMPTY;
        CompoundTag root = parent.getCompound(NBT_KEY);
        ListTag list = root.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        if (list.isEmpty()) return EMPTY;

        Map<String, RiftExitEntry> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            RiftExitEntry entry = RiftExitEntry.load(list.getCompound(i));
            if (!entry.dimensionId().isEmpty()) {
                map.put(entry.dimensionId(), entry);
            }
        }
        return map.isEmpty() ? EMPTY : new RiftExitData(map);
    }

    public void save(CompoundTag parent) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (RiftExitEntry entry : entries.values()) {
            list.add(entry.save());
        }
        root.put(TAG_ENTRIES, list);
        parent.put(NBT_KEY, root);
    }

    // ========== 查询 / 变更 ==========

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Set<String> dimensionIds() {
        return entries.keySet();
    }

    public RiftExitEntry get(String dimensionId) {
        return entries.get(dimensionId);
    }

    /** 返回一个新增/覆盖了指定维度记录的新实例（原实例不变，覆盖时移到末尾） */
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

    /** 按插入顺序导出的不可变列表 */
    public List<RiftExitEntry> ordered() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }
}
