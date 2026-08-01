package com.plumestweaks.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 骑马保护效果 — 纯标记效果，无任何运行时逻辑。
 *
 * 效果本身没有粒子、没有图标，完全隐藏。
 * 持有此效果期间，{@link com.plumestweaks.mixin.PlayerMountProtectionMixin}
 * 会在 {@link net.minecraft.world.entity.player.Player#hurt} 时取消对玩家的伤害，
 * 让玩家在骑马时有持续保护，下马后仍有约 6 秒的无敌窗口。
 */
public class MountProtectionEffect extends MobEffect {

    public MountProtectionEffect() {
        // category=受益, color=灰色（图标隐藏所以颜色无关紧要）
        super(MobEffectCategory.BENEFICIAL, 0x888888);
    }
}
