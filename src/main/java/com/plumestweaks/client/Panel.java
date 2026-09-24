package com.plumestweaks.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 面板绘制：直角矩形，纯程序绘制，不使用任何贴图。
 * <p>
 * 半透明底色 + 1px 描边，让背景星云透出来；圆角与磨砂/呼吸灯效果已按要求移除。
 */
public final class Panel {

    /** 面板底色：ARGB 0x99 ≈ 60% 不透明 */
    public static final int FILL = 0x99101418;
    /** 1px 描边 */
    public static final int BORDER = 0x55FFFFFF;

    private Panel() {
    }

    /** 画面板：底色铺满 + 一圈 1px 描边 */
    public static void draw(GuiGraphics graphics, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        graphics.fill(x, y, x + w, y + h, FILL);
        outline(graphics, x, y, w, h, BORDER);
    }

    /** 1px 矩形描边 */
    public static void outline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }
}
