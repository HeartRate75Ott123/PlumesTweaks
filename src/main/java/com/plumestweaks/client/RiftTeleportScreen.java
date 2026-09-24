package com.plumestweaks.client;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.RiftExitData;
import com.plumestweaks.component.RiftExitEntry;
import com.plumestweaks.network.RiftNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 裂隙传送界面：从裂隙维度返回曾经进入过裂隙的任意维度（1.20.1 / Forge 版）。
 * <p>
 * 交互：单击条目 → 白框选中；点「传送」→ 传送到该维度记录点。
 * 列表一页 6 行，可滚动（滚轮 / 拖动右侧滚动条），超出可视区域的行由 scissor 裁掉；
 * 内容不足一屏时滚动条整条不绘制。
 * <p>
 * 过长的维度名会横向循环滚动：位移用 float（亚像素），时间按真实帧间隔累加，与帧率无关。
 */
public class RiftTeleportScreen extends Screen {

    // ========== 配色 ==========
    private static final int TITLE = 0xFFFFFFFF;
    private static final int TEXT = 0xFFF2FFFF;
    private static final int TEXT_DIM = 0xFF9FB3B6;
    private static final int ROW_BG_SELECTED = 0x5552D6D6;
    private static final int ROW_HOVER = 0x28FFFFFF;
    private static final int ROW_SELECTED_BORDER = 0xFFFFFFFF;
    private static final int ROW_SEPARATOR = 0x1AFFFFFF;
    private static final int SCROLL_TRACK = 0x33000000;
    private static final int SCROLL_THUMB = 0xFFA7A7A7;
    private static final int BUTTON_BG = 0xCC0A0F12;
    private static final int BUTTON_BG_HOVER = 0xE61C2A2E;
    private static final int BUTTON_BORDER = 0xFF8FE3E3;
    private static final int BUTTON_BORDER_DISABLED = 0x55FFFFFF;
    private static final int BUTTON_TEXT = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_DISABLED = 0xFF7A8A8C;

    // ========== 版式常量（GUI 像素） ==========
    private static final int TITLE_H = 30;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    /** 一页显示的行数 */
    private static final int PAGE_ROWS = 6;
    private static final int SCROLLBAR_W = 5;
    private static final int PAD = 34;
    private static final int BTN_W = 124;
    private static final int BTN_H = 22;
    /** 列表与面板左/右边缘的留白 */
    private static final int LIST_SIDE_PAD = 10;
    /** 列表底 → 按钮顶 */
    private static final int LIST_BUTTON_GAP = 8;
    /** 按钮底 → 面板底 */
    private static final int BUTTON_BOTTOM_GAP = 12;

    /** 一页的列表高度（PAGE_ROWS 行 + 行间间隙，底部不留缝） */
    private static final int PAGE_LIST_H = PAGE_ROWS * (ROW_H + ROW_GAP) - ROW_GAP;
    /** 面板高度下限：刚好放下「标题 + 一页列表 + 按钮」 */
    private static final int MIN_PANEL_H = TITLE_H + PAGE_LIST_H + LIST_BUTTON_GAP + BTN_H + BUTTON_BOTTOM_GAP;
    /** 面板高度上限 */
    private static final int MAX_PANEL_H = 300;
    private static final float VIEWPORT_SCALE = 0.78f;
    /** 列表与按钮之间允许留出的最大空隙 */
    private static final int MAX_SURPLUS_GAP = 40;
    /** 面板竖版比例 1.2（宽 = 高 / 1.2） */
    private static final float PANEL_ASPECT = 1.0f / 1.20f;

    /** 过长条目横向循环滚动的速度（GUI 像素 / 秒） */
    private static final float MARQUEE_SPEED = 22.0f;
    /** 循环带里文本与下一份文本之间的空白 */
    private static final float MARQUEE_GAP = 24.0f;

    private static volatile boolean debugOverlay;
    private static volatile boolean debugForced;

    /** 由网络层反射调用，打开本界面 */
    public static void open(RiftExitData exits) {
        Minecraft.getInstance().setScreen(new RiftTeleportScreen(exits));
    }

    public static void setDebugOverlay(boolean on) {
        debugForced = on;
        debugOverlay = on;
    }

    public static boolean isDebugOverlay() {
        return debugOverlay;
    }

    /**
     * 列表行：维度显示名 + 惰性实测的像素宽度。
     * <p>
     * 宽度要用 {@code Screen.font} 量，而本对象在构造函数里就建好了 ——
     * 那时 {@code font} 还是 null（{@code Screen.init(Minecraft,...)} 才赋值），
     * 只能等渲染/init 时再补测。早期把它缓存成 0 导致跑马灯永远不触发。
     */
    private static final class Row {
        private final RiftExitEntry entry;
        private final String displayName;
        private int textWidth = -1;

        Row(RiftExitEntry entry, String displayName) {
            this.entry = entry;
            this.displayName = displayName;
        }

        RiftExitEntry entry() {
            return entry;
        }

        String displayName() {
            return displayName;
        }

        int textWidth(Font font) {
            if (textWidth < 0 && font != null) {
                textWidth = font.width(displayName);
            }
            return textWidth;
        }
    }

    private record Layout(int panelX, int panelY, int panelW, int panelH,
                          int listX, int listY, int listW, int listH,
                          int scrollX, int scrollY, int scrollW, int scrollH,
                          int btnX, int btnY, int btnW, int btnH) {}

    private final RiftExitData exits;
    private final List<Row> rows = new ArrayList<>();

    private Layout layout;
    private int selected = -1;
    private int hovered = -1;
    private float scrollOffset;
    private boolean draggingThumb;
    private double dragGrabY;
    private float dragStartOffset;

    /** 跑马灯相位（秒），按真实帧间隔累加，见 {@link #tickMarquee()} */
    private float marqueeSeconds;

    public RiftTeleportScreen(RiftExitData exits) {
        super(Component.translatable("gui.plumestweaks.rift.title"));
        this.exits = exits == null ? RiftExitData.EMPTY : exits;
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        for (RiftExitEntry e : exits.ordered()) {
            rows.add(new Row(e, ClientDimensionNames.resolve(e.dimensionId())));
        }
        rows.sort(Comparator.comparing(Row::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.entry().dimensionId()));
        debugOverlay = debugForced || rows.stream()
                .anyMatch(r -> r.entry().dimensionId().startsWith(PlumesTweaks.MODID + ":debug"));
    }

    // ========== 版式 ==========

    @Override
    protected void init() {
        super.init();

        // 量一次文本宽度（此时 font 已就绪），决定哪些行需要横向滚动
        for (Row row : rows) {
            row.textWidth(this.font);
        }

        int contentH = MIN_PANEL_H;
        int target = Math.round(this.height * VIEWPORT_SCALE);
        int surplus = Mth.clamp(target - contentH, 0, MAX_SURPLUS_GAP);
        int h = Math.min(contentH + surplus, MAX_PANEL_H);
        h = Math.min(h, this.height - 20);

        int w = Math.round(h * PANEL_ASPECT);
        if (w > this.width - 40) {
            w = this.width - 40;
            h = Math.round(w / PANEL_ASPECT);
        }
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;

        int listH = PAGE_LIST_H;
        int btnY = Math.max(y + h - BUTTON_BOTTOM_GAP - BTN_H, y + TITLE_H + listH + LIST_BUTTON_GAP);

        int listX = x + LIST_SIDE_PAD;
        int listW = Math.max(30, w - LIST_SIDE_PAD * 2 - SCROLLBAR_W - 4);
        int listY = y + TITLE_H;

        int btnW = Math.min(BTN_W, w - PAD * 2);

        this.layout = new Layout(x, y, w, h,
                listX, listY, listW, listH,
                listX + listW + 4, listY, SCROLLBAR_W, listH,
                x + (w - btnW) / 2, btnY, btnW, BTN_H);

        clampScroll();
    }

    // ========== 滚动 ==========

    private int contentHeight() {
        if (rows.isEmpty()) return 0;
        return rows.size() * ROW_H + (rows.size() - 1) * ROW_GAP;
    }

    private float maxScroll() {
        if (layout == null) return 0f;
        return Math.max(0f, contentHeight() - layout.listH());
    }

    private void clampScroll() {
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0f, maxScroll());
    }

    private int rowY(int index) {
        return layout.listY() + index * (ROW_H + ROW_GAP) - Math.round(scrollOffset);
    }

    // ========== 渲染 ==========

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Layout l = this.layout;

        // 跑马灯相位按真实帧间隔推进（与帧率无关）
        tickMarquee();

        // 不画任何全屏背景：只画那块半透明面板，其余让游戏画面透出来
        // 直角面板
        Panel.draw(graphics, l.panelX(), l.panelY(), l.panelW(), l.panelH());

        // 标题
        Component title = Component.translatable("gui.plumestweaks.rift.title");
        graphics.drawString(this.font, title,
                l.panelX() + (l.panelW() - this.font.width(title)) / 2,
                l.panelY() + 11, TITLE, false);

        // 列表（裁剪到可视区）
        graphics.enableScissor(l.listX(), l.listY(), l.listX() + l.listW(), l.listY() + l.listH());
        this.hovered = -1;
        if (rows.isEmpty()) {
            Component empty = Component.translatable("gui.plumestweaks.rift.empty");
            graphics.drawString(this.font, empty,
                    l.listX() + (l.listW() - this.font.width(empty)) / 2,
                    l.listY() + l.listH() / 2 - 4, TEXT_DIM, false);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                int ry = rowY(i);
                if (ry + ROW_H < l.listY() || ry > l.listY() + l.listH()) continue;
                boolean isSelected = i == selected;
                boolean isHovered = mouseX >= l.listX() && mouseX < l.listX() + l.listW()
                        && mouseY >= ry && mouseY < ry + ROW_H;
                if (isHovered) this.hovered = i;
                renderRow(graphics, rows.get(i), l.listX(), ry, l.listW(),
                        isSelected, isHovered, i < rows.size() - 1);
            }
        }
        graphics.disableScissor();

        renderScrollbar(graphics);
        renderButton(graphics, mouseX, mouseY);

        if (debugOverlay) renderDebug(graphics);
    }

    /** 单行：只有维度名称；过长则横向循环滚动；选中时加一圈白线 */
    private void renderRow(GuiGraphics graphics, Row row, int x, int y, int w,
                           boolean isSelected, boolean isHovered, boolean drawSeparator) {
        if (isSelected) {
            graphics.fill(x, y, x + w, y + ROW_H, ROW_BG_SELECTED);
        } else if (isHovered) {
            graphics.fill(x, y, x + w, y + ROW_H, ROW_HOVER);
        }

        int textY = y + (ROW_H - 8) / 2;
        drawRowText(graphics, row, x + 6, textY, w - 12);

        if (isSelected) {
            Panel.outline(graphics, x, y, w, ROW_H, ROW_SELECTED_BORDER);
        } else if (drawSeparator) {
            graphics.fill(x + 4, y + ROW_H - 1, x + w - 4, y + ROW_H, ROW_SEPARATOR);
        }
    }

    /**
     * 画行内文本：放得下就直接画，放不下就横向循环滚动（marquee）。
     * <p>
     * 滚动的是「文本 + 一段空白」的循环带：画两份、错开恰好一个带宽，
     * 于是文字往左走完会从右边无缝接上。
     * 位移必须是 float（取整会一跳一跳），时间按真实帧间隔累加（不能用 20Hz 游戏刻）。
     */
    private void drawRowText(GuiGraphics graphics, Row row, int textX, int textY, int avail) {
        int textWidth = row.textWidth(this.font);
        if (textWidth < 0 || textWidth <= avail) {
            graphics.drawString(this.font, row.displayName(), textX, textY, TEXT, false);
            return;
        }

        float band = textWidth + MARQUEE_GAP;
        float offset = (marqueeSeconds * MARQUEE_SPEED) % band;
        if (offset < 0f) offset += band;

        // float 重载：亚像素定位，滚动才顺滑；两份文本错开一个带宽实现无缝循环
        graphics.drawString(this.font, row.displayName(), textX - offset, textY, TEXT, false);
        graphics.drawString(this.font, row.displayName(), textX - offset + band, textY, TEXT, false);
    }

    /**
     * 按真实帧间隔推进跑马灯相位。
     * <p>
     * 1.20.1 的 {@code Minecraft#getDeltaFrameTime()} 返回 {@code Timer.tickDelta}，
     * 就是「本帧真实经过的 tick 小数」（真实时间流速，与暂停/冻结无关），
     * 等价于 1.21.1 的 {@code DeltaTracker#getRealtimeDeltaTicks()}；
     * 除以 20 得到秒。这样滚动距离只与真实时间成正比，与帧率无关。
     * <p>
     * 不能用 20Hz 的游戏刻 + partialTick 推相位 —— 那会把文字锁在游戏刻上，一卡一卡。
     */
    private void tickMarquee() {
        float deltaSeconds = 0f;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            deltaSeconds = mc.getDeltaFrameTime() / 20.0f;
        }
        // 卡顿/拖窗口/切出界面时会给出很大的间隔，夹住避免文字瞬移
        marqueeSeconds += Mth.clamp(deltaSeconds, 0f, 0.1f);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        // 内容不足一屏时整体不显示滚动条
        float max = maxScroll();
        if (max <= 0f) return;

        Layout l = this.layout;
        graphics.fill(l.scrollX(), l.scrollY(), l.scrollX() + l.scrollW(), l.scrollY() + l.scrollH(), SCROLL_TRACK);

        int trackH = l.scrollH();
        int thumbH = thumbHeight();
        int thumbY = l.scrollY() + Math.round((scrollOffset / max) * (trackH - thumbH));
        graphics.fill(l.scrollX(), thumbY, l.scrollX() + l.scrollW(), thumbY + thumbH, SCROLL_THUMB);
    }

    private int thumbHeight() {
        Layout l = this.layout;
        int content = contentHeight();
        if (content <= 0) return l.scrollH();
        return Math.max(12, Math.round((float) l.scrollH() * l.scrollH() / content));
    }

    private void renderButton(GuiGraphics graphics, int mouseX, int mouseY) {
        Layout l = this.layout;
        boolean enabled = selected >= 0 && selected < rows.size();
        boolean hovered = enabled && isOverButton(mouseX, mouseY);

        graphics.fill(l.btnX(), l.btnY(), l.btnX() + l.btnW(), l.btnY() + l.btnH(),
                hovered ? BUTTON_BG_HOVER : BUTTON_BG);
        Panel.outline(graphics, l.btnX(), l.btnY(), l.btnW(), l.btnH(),
                enabled ? BUTTON_BORDER : BUTTON_BORDER_DISABLED);

        Component label = Component.translatable("gui.plumestweaks.rift.teleport");
        graphics.drawString(this.font, label,
                l.btnX() + (l.btnW() - this.font.width(label)) / 2,
                l.btnY() + (l.btnH() - 8) / 2,
                enabled ? BUTTON_TEXT : BUTTON_TEXT_DISABLED, false);
    }

    private void renderDebug(GuiGraphics graphics) {
        Layout l = this.layout;
        int firstVisible = rows.isEmpty() ? -1
                : Mth.clamp((int) Math.floor(scrollOffset / (ROW_H + ROW_GAP)), 0, rows.size() - 1);
        int lastVisible = rows.isEmpty() ? -1
                : Math.min(rows.size() - 1,
                        (int) Math.floor((scrollOffset + l.listH()) / (ROW_H + ROW_GAP)));

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§b[RiftDebug]"));
        lines.add(Component.literal("entries=" + rows.size() + " selected=" + selected));
        lines.add(Component.literal(String.format("scroll=%.1f / max=%.1f", scrollOffset, maxScroll())));
        lines.add(Component.literal("visible=" + firstVisible + ".." + lastVisible));
        lines.add(Component.literal(String.format("panel=%dx%d rowH=%d gap=%d", l.panelW(), l.panelH(), ROW_H, ROW_GAP)));
        lines.add(Component.literal("list=" + l.listW() + "x" + l.listH() + " content=" + contentHeight()));

        int y = Math.max(2, l.panelY() - 4 - lines.size() * 10);
        for (Component line : lines) {
            graphics.drawString(this.font, line, l.panelX(), y, 0xFFB7FFB7, true);
            y += 10;
        }
    }

    // ========== 输入 ==========

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        if (maxScroll() > 0f && isOverScrollbar(mouseX, mouseY)) {
            draggingThumb = true;
            dragStartOffset = scrollOffset;
            dragGrabY = mouseY;
            return true;
        }

        if (selected >= 0 && isOverButton(mouseX, mouseY)) {
            teleport();
            return true;
        }

        int index = indexAt(mouseX, mouseY);
        if (index >= 0) {
            selected = index;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingThumb) {
            Layout l = this.layout;
            int trackH = l.scrollH() - thumbHeight();
            if (trackH > 0) {
                float max = maxScroll();
                float delta = (float) (mouseY - dragGrabY) / trackH * max;
                scrollOffset = Mth.clamp(dragStartOffset + delta, 0f, max);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingThumb = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 1.20.1 只有纵向滚轮增量 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0f) {
            scrollOffset = Mth.clamp(scrollOffset - (float) delta * (ROW_H + ROW_GAP), 0f, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ========== 命中测试 ==========

    private int indexAt(double mouseX, double mouseY) {
        Layout l = this.layout;
        if (mouseX < l.listX() || mouseX >= l.listX() + l.listW()) return -1;
        if (mouseY < l.listY() || mouseY >= l.listY() + l.listH()) return -1;
        for (int i = 0; i < rows.size(); i++) {
            int ry = rowY(i);
            if (mouseY >= ry && mouseY < ry + ROW_H) return i;
        }
        return -1;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        Layout l = this.layout;
        return mouseX >= l.scrollX() && mouseX < l.scrollX() + l.scrollW()
                && mouseY >= l.scrollY() && mouseY < l.scrollY() + l.scrollH();
    }

    private boolean isOverButton(double mouseX, double mouseY) {
        Layout l = this.layout;
        return mouseX >= l.btnX() && mouseX < l.btnX() + l.btnW()
                && mouseY >= l.btnY() && mouseY < l.btnY() + l.btnH();
    }

    // ========== 传送 ==========

    private void teleport() {
        if (selected < 0 || selected >= rows.size()) return;
        Row row = rows.get(selected);
        RiftNetwork.sendTeleport(row.entry().dimensionId());
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
