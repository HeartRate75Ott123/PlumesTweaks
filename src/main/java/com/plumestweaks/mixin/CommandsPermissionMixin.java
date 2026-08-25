package com.plumestweaks.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.plumestweaks.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 放开原版 {@code /tp}、{@code /teleport}、{@code /execute} 的权限要求为所有人可用。
 * <p>
 * 仅改写命令节点的 {@code requirement} 谓词；目标子命令（如 {@code /give}）自身权限仍生效，
 * 不会因此绕过 op 限制。受 {@link Config#FREE_CROSS_DIMENSION_TP} 开关控制。
 */
@Mixin(Commands.class)
public abstract class CommandsPermissionMixin {

    @Shadow private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(CallbackInfo ci) {
        if (!Config.FREE_CROSS_DIMENSION_TP.get()) return;
        for (String name : new String[]{"tp", "teleport", "execute"}) {
            try {
                LiteralCommandNode<CommandSourceStack> node =
                        (LiteralCommandNode<CommandSourceStack>) dispatcher.getRoot().getChild(name);
                if (node == null) continue;
                // requirement 字段声明在父类 CommandNode 上
                Field requirement = com.mojang.brigadier.tree.CommandNode.class
                        .getDeclaredField("requirement");
                requirement.setAccessible(true);
                try {
                    Field modifiers = Field.class.getDeclaredField("modifiers");
                    modifiers.setAccessible(true);
                    modifiers.setInt(requirement, requirement.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                } catch (Exception ignored) {
                    // 某些 JVM 不允许改 final 修饰符，直接 set 通常仍生效
                }
                requirement.set(node, (java.util.function.Predicate<CommandSourceStack>) (source -> true));
            } catch (Exception ignored) {
                // 字段名或节点不存在，跳过该命令
            }
        }
    }
}
