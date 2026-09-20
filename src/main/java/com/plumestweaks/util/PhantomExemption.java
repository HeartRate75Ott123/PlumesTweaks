package com.plumestweaks.util;

import net.minecraft.world.entity.Entity;

/**
 * lensouls「虚影幻灵」赦免判定。
 * <p>
 * lensouls 的 BOSS 镜魂演出会「借体」——用反射直接构造其它模组的 Boss 实体
 * （灾变 Ignis/利维坦、传奇怪物云巨人、暮色森林九头蛇/幻影骑士等），
 * 并在演出期间把周围新生成的生物标记为召唤物。这些实体对玩家是友好单位，
 * 但它们的实体类型仍与真 Boss 完全相同。
 * <p>
 * lensouls 用 persistentData 打标：
 * <ul>
 *   <li>{@code lensouls:phantom} —— 借体 Boss 本体；</li>
 *   <li>{@code lensouls:phantom_minion} —— 借体 Boss 的召唤物；</li>
 *   <li>{@code lensouls:phantom_owner} —— 施法玩家 UUID。</li>
 * </ul>
 * 本模组的「附近有 Boss 则禁止使用裂隙/设置重生点」检查据此放行幻灵，
 * 使幻灵演出不会误触发限制。未安装 lensouls 时判定恒为 false。
 */
public final class PhantomExemption {

    /** 借体 Boss 本体标记 */
    public static final String TAG_PHANTOM = "lensouls:phantom";
    /** 借体 Boss 召唤物标记 */
    public static final String TAG_PHANTOM_MINION = "lensouls:phantom_minion";

    private PhantomExemption() {}

    /** 实体是否属于 lensouls 虚影幻灵（借体本体或其召唤物） */
    public static boolean isPhantom(Entity entity) {
        if (entity == null) return false;
        var tag = entity.getPersistentData();
        return tag.getBoolean(TAG_PHANTOM) || tag.getBoolean(TAG_PHANTOM_MINION);
    }
}
