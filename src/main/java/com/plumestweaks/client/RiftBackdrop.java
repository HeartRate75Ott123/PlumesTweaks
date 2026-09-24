package com.plumestweaks.client;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 裂隙传送界面的背景层：把 {@code textures/gui/tp_background3.png} 以 <b>cover</b> 方式铺满窗口
 * （等比放大到刚好盖满，多余部分居中裁掉），再压一层柔纱保证文字对比度。
 * <p>
 * 面板区的磨砂与背景高亮呼吸灯已按要求移除，因此这里不再涉及任何着色器。
 * 贴图缺失时回退纯色背景，界面照常可用。
 */
public final class RiftBackdrop {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "textures/gui/tp_background.png");
    private static final int TEX_W = 1920;
    private static final int TEX_H = 1080;

    private static final int FALLBACK_BG = 0xFF06181C;

    /** 压在贴图上的柔纱（25% 黑），保证文字对比度 */
    private static final int VEIL = 0x40000000;

    private static Boolean textureAvailable;

    private RiftBackdrop() {
    }

    /** 绘制整个界面背景（必须在本帧所有 GUI 元素之前调用） */
    public static void render(GuiGraphics graphics, int width, int height) {
        if (textureAvailable == null) {
            boolean ok;
            try {
                ok = Minecraft.getInstance().getResourceManager().getResource(TEXTURE).isPresent();
            } catch (Throwable t) {
                ok = false;
            }
            textureAvailable = ok;
            if (!ok) {
                PlumesTweaks.LOGGER.warn("[RiftGui] 背景贴图缺失：{}，回退纯色背景", TEXTURE);
            }
        }

        if (textureAvailable) {
            blitCover(graphics, width, height);
        } else {
            graphics.fill(0, 0, width, height, FALLBACK_BG);
        }
        graphics.fill(0, 0, width, height, VEIL);
    }

    /** cover 铺法：等比放大到刚好盖满窗口，多余部分居中裁掉 */
    private static void blitCover(GuiGraphics graphics, int width, int height) {
        float scale = Math.max((float) width / TEX_W, (float) height / TEX_H);
        int srcW = Math.min(TEX_W, Math.max(1, Math.round(width / scale)));
        int srcH = Math.min(TEX_H, Math.max(1, Math.round(height / scale)));
        int u = (TEX_W - srcW) / 2;
        int v = (TEX_H - srcH) / 2;
        graphics.blit(TEXTURE, 0, 0, width, height, (float) u, (float) v, srcW, srcH, TEX_W, TEX_H);
    }
}
