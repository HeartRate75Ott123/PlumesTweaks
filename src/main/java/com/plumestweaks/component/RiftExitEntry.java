package com.plumestweaks.component;

import net.minecraft.nbt.CompoundTag;

/**
 * 裂隙「出口点」记录 —— 玩家从某个裂隙外维度进入裂隙时，在该维度留下的坐标。
 * <p>
 * 与裂隙内的落点（`rift_enter`）互为反向：本记录描述「回到裂隙外的哪里」。
 * 只在从该维度**进入**裂隙时写入/覆盖；从裂隙传送出去时读取但不修改，
 * 因此同一个维度的记录会一直保留到下次从该维度进入。
 * <p>
 * 1.20.1 没有 DataComponent，直接以 CompoundTag 存进物品 NBT。
 */
public final class RiftExitEntry {

    private static final String TAG_DIM = "dim";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";
    private static final String TAG_YAW = "yaw";
    private static final String TAG_PITCH = "pitch";

    private final String dimensionId;
    private final int x;
    private final int y;
    private final int z;
    private final float yaw;
    private final float pitch;

    public RiftExitEntry(String dimensionId, int x, int y, int z, float yaw, float pitch) {
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static RiftExitEntry load(CompoundTag tag) {
        return new RiftExitEntry(
                tag.getString(TAG_DIM),
                tag.getInt(TAG_X), tag.getInt(TAG_Y), tag.getInt(TAG_Z),
                tag.getFloat(TAG_YAW), tag.getFloat(TAG_PITCH));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIM, dimensionId);
        tag.putInt(TAG_X, x);
        tag.putInt(TAG_Y, y);
        tag.putInt(TAG_Z, z);
        tag.putFloat(TAG_YAW, yaw);
        tag.putFloat(TAG_PITCH, pitch);
        return tag;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }
}
