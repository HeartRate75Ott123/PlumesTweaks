package com.plumestweaks.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 FoodData.exhaustion 私有字段的 Accessor 接口。
 * 由 Mixin 自动处理混淆映射，开发环境和生产环境均有效。
 */
@Mixin(FoodData.class)
public interface FoodDataAccessor {

    @Accessor(value = "exhaustionLevel", remap = false)
    float getExhaustion();

    @Accessor(value = "exhaustionLevel", remap = false)
    void setExhaustion(float value);
}
