package com.plumestweaks;

import com.plumestweaks.effect.MountProtectionEffect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = PlumesTweaks.MODID, dist = Dist.CLIENT)
public class PlumesTweaksClient {
    public PlumesTweaksClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(PlumesTweaksClient::registerClientExtensions);
    }

    /** 隐藏 MountProtectionEffect 的背包效果栏图标和 HUD 图标 */
    private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        IClientMobEffectExtensions hidden = new IClientMobEffectExtensions() {
            @Override
            public boolean isVisibleInInventory(net.minecraft.world.effect.MobEffectInstance instance) {
                return false;
            }
            @Override
            public boolean isVisibleInGui(net.minecraft.world.effect.MobEffectInstance instance) {
                return false;
            }
        };
        event.registerMobEffect(hidden, PlumesTweaks.MOUNT_PROTECTION);
    }
}
