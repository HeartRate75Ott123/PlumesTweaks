package com.plumestweaks.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.plumestweaks.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在指令解析入口 {@code parseCommand} 拦截玩家的 {@code /execute}、{@code /ftbquests}。
 * 当玩家缺少 2 级权限时，用 {@code player.createCommandSourceStack().withPermission(2)} 重新解析，
 * 使这些指令对非 op 放行，且不影响 {@code /op}、{@code /stop} 等更高权限命令。
 *
 * <p>参考 StellarRTP：其同样 hook {@code parseCommand} 在权限校验前改写命令，证明此点是运行时可靠拦截点。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "parseCommand", at = @At("HEAD"), cancellable = true)
    private void onParseCommand(String command, CallbackInfoReturnable<ParseResults<CommandSourceStack>> cir) {
        if (player == null || player.hasPermissions(2)) return;

        String token = firstToken(command);
        if (token == null) return;

        // 总开关关闭时不解禁任何指令
        if (!Config.FREE_CROSS_DIMENSION_TP.get()) return;

        // 不同指令需要的权限等级不同：execute/ftbquests 为 2 级；
        // place 的子命令存在 3~4 级要求，故整体提到 4 级（place 自身无 op/stop 等危险子命令）。
        int level = switch (token) {
            case "execute" -> Config.FREE_EXECUTE.get() ? 2 : -1;
            case "ftbquests" -> Config.FREE_FTBQUESTS.get() ? 2 : -1;
            case "place" -> Config.FREE_PLACE.get() ? 4 : -1;
            default -> -1;
        };
        if (level < 0) return;

        CommandSourceStack elevated = player.createCommandSourceStack().withPermission(level);
        CommandDispatcher<CommandSourceStack> dispatcher =
                player.getServer().getCommands().getDispatcher();
        cir.setReturnValue(dispatcher.parse(command, elevated));
        cir.cancel();
    }

    private static String firstToken(String command) {
        if (command == null) return null;
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        int space = c.indexOf(' ');
        String token = (space < 0) ? c : c.substring(0, space);
        int colon = token.indexOf(':');
        if (colon >= 0) token = token.substring(colon + 1);
        return token.isEmpty() ? null : token;
    }
}
