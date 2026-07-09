package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 纵向滚动区域 - 右侧带滚动条
 * 支持动态添加子控件，自动计算内容高度，滚动条自动显示/隐藏
 */
public class Area extends AbstractScrollArea {
    private final List<AbstractWidget> children = new ArrayList<>();
    private int totalContentHeight = 0;
    private int scrollOffset = 0; // 当前滚动偏移（像素）
    private int scrollbarWidth = 8; // 滚动条宽度
    private int scrollbarMargin = 2; // 滚动条边距

    // ==================== 构造函数 ====================

    /**
     * 创建滚动区域
     * @param x 左上角 X 坐标
     * @param y 左上角 Y 坐标
     * @param width 宽度
     * @param height 高度
     */

    public Area(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(),
                new ScrollbarSettings(
                        Identifier.fromNamespaceAndPath("minecraft", "textures/gui/sprites/widget/scroll_bar.png"),  // scrollerSprite
                        null,                                                           // disabledScrollerSprite
                        Identifier.fromNamespaceAndPath("minecraft","textures/gui/sprites/widget/scroll_bar_background.png"), // backgroundSprite
                        8,    // scrollbarWidth
                        20,   // scrollbarMinHeight
                        10,   // scrollRate
                        false // resizingScrollbar
                ));
    }

    // ==================== 子控件管理 ====================

    public <T extends AbstractWidget> T addChild(T child) {
        this.children.add(child);
        this.recalculateContentHeight();
        return child;
    }

    public void removeChild(AbstractWidget child) {
        this.children.remove(child);
        this.recalculateContentHeight();
    }

    public void clearChildren() {
        this.children.clear();
        this.totalContentHeight = 0;
        this.scrollOffset = 0;
    }

    public List<AbstractWidget> getChildren() {
        return this.children;
    }

    // ==================== 内容高度计算 ====================

    private void recalculateContentHeight() {
        int maxBottom = 0;
        for (AbstractWidget child : this.children) {
            int bottom = child.getY() + child.getHeight();
            if (bottom > maxBottom) {
                maxBottom = bottom;
            }
        }
        this.totalContentHeight = Math.max(maxBottom, this.height);
    }

    @Override
    protected int contentHeight() {
        return this.totalContentHeight;
    }

    // ==================== 滚动控制 ====================

    public void scrollTo(int y) {
        int maxScroll = Math.max(0, this.totalContentHeight - this.height);
        this.scrollOffset = Math.max(0, Math.min(y, maxScroll));
    }

    public void scrollToChild(AbstractWidget child) {
        if (this.children.contains(child)) {
            int childTop = child.getY();
            int childBottom = child.getY() + child.getHeight();
            int viewportTop = this.getY();
            int viewportBottom = this.getY() + this.height;

            if (childTop < viewportTop) {
                scrollTo(this.scrollOffset - (viewportTop - childTop));
            } else if (childBottom > viewportBottom) {
                scrollTo(this.scrollOffset + (childBottom - viewportBottom));
            }
        }
    }

    public int getScrollOffset() {
        return this.scrollOffset;
    }

    // ==================== 渲染 ====================

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;

        // 1. 绘制区域背景
        graphics.fill(x, y, x + w, y + h, 0xFF222222);
        // 边框
        graphics.fill(x, y, x + w, y + 1, 0xFF666666);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF666666);
        graphics.fill(x, y, x + 1, y + h, 0xFF666666);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF666666);

        // 2. 设置裁剪区域（防止内容溢出）
        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);

        // 3. 应用滚动偏移
        graphics.pose().translate(0.0f,-(float) this.scrollOffset);

        // 4. 渲染所有子控件（传入调整后的鼠标 Y 坐标）
        for (AbstractWidget child : this.children) {
            if (child.visible) {
                child.extractRenderState(graphics, mouseX, mouseY + this.scrollOffset, delta);
            }
        }

        graphics.pose().translate(0.0f,(float) this.scrollOffset);
        graphics.disableScissor();

        // 5. 绘制滚动条（内容超出时显示）
        if (this.totalContentHeight > this.height) {
            this.drawScrollbar(graphics);
        }
    }

    // ==================== 滚动条绘制 ====================

    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        int x = this.getX() + this.width - this.scrollbarWidth - this.scrollbarMargin;
        int y = this.getY() + this.scrollbarMargin;
        int barHeight = this.height - this.scrollbarMargin * 2;

        // 滚动条背景
        graphics.fill(x, y, x + this.scrollbarWidth, y + barHeight, 0x33FFFFFF);

        // 滚动条滑块
        float contentRatio = (float) this.height / this.totalContentHeight;
        int thumbHeight = Math.max(20, (int) (barHeight * contentRatio));
        int maxThumbY = barHeight - thumbHeight;
        float scrollRatio = (float) this.scrollOffset / (this.totalContentHeight - this.height);
        int thumbY = y + (int) (maxThumbY * scrollRatio);

        graphics.fill(x, thumbY, x + this.scrollbarWidth, thumbY + thumbHeight, 0x88FFFFFF);
        // 滑块边框
        graphics.fill(x, thumbY, x + this.scrollbarWidth, thumbY + 1, 0xFFFFFFFF);
        graphics.fill(x, thumbY + thumbHeight - 1, x + this.scrollbarWidth, thumbY + thumbHeight, 0xFFFFFFFF);
        graphics.fill(x, thumbY, x + 1, thumbY + thumbHeight, 0xFFFFFFFF);
        graphics.fill(x + this.scrollbarWidth - 1, thumbY, x + this.scrollbarWidth, thumbY + thumbHeight, 0xFFFFFFFF);
    }

    // ==================== 滚轮事件 ====================


    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!this.visible || !this.isActive()) return false;

        double scrollDelta = scrollY;
        if (scrollDelta == 0) return false;

        // 检查鼠标是否在区域内
        if (!this.isMouseOver(mx, my)) return false;

        int newOffset = (int) (this.scrollOffset - scrollDelta * 20);
        this.scrollTo(newOffset);
        return true;
    }


    // ==================== 鼠标拖拽滚动 ====================

    private boolean isDraggingScrollbar = false;
    private int dragStartY = 0;
    private int dragStartOffset = 0;

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.visible || !this.isActive()) return false;

        double mouseX = event.x();
        double mouseY = event.y();

        // 点击滚动条
        if (this.isMouseOverScrollbar(mouseX, mouseY)) {
            this.isDraggingScrollbar = true;
            this.dragStartY = (int) mouseY;
            this.dragStartOffset = this.scrollOffset;
            return true;
        }

        // 转发给子控件（从后往前，后添加的在上层）
        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive() && child.isMouseOver(mouseX, mouseY)) {
                if (child.mouseClicked(event, doubleClick)) {
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.isDraggingScrollbar) {
            this.isDraggingScrollbar = false;
            return true;
        }

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive()) {
                if (child.mouseReleased(event)) {
                    return true;
                }
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.isDraggingScrollbar) {
            int deltaY = (int) event.y() - this.dragStartY;
            int barHeight = this.height - this.scrollbarMargin * 2;
            float maxDelta = this.totalContentHeight - this.height;
            float ratio = (float) deltaY / barHeight;
            int newOffset = this.dragStartOffset + (int) (maxDelta * ratio);
            this.scrollTo(newOffset);
            return true;
        }

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive()) {
                if (child.mouseDragged(event, dx, dy)) {
                    return true;
                }
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    // ==================== 键盘事件（转发给子控件） ====================

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.visible || !this.isActive()) return false;

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive()) {
                if (child.keyPressed(event)) {
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (!this.visible || !this.isActive()) return false;

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive()) {
                if (child.keyReleased(event)) {
                    return true;
                }
            }
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!this.visible || !this.isActive()) return false;

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive()) {
                if (child.charTyped(event)) {
                    return true;
                }
            }
        }
        return super.charTyped(event);
    }

    // ==================== 辅助方法 ====================

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        int x = this.getX() + this.width - this.scrollbarWidth - this.scrollbarMargin;
        int y = this.getY() + this.scrollbarMargin;
        int barHeight = this.height - this.scrollbarMargin * 2;
        return mouseX >= x && mouseX <= x + this.scrollbarWidth &&
                mouseY >= y && mouseY <= y + barHeight;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.visible &&
                mouseX >= this.getX() && mouseX <= this.getX() + this.width &&
                mouseY >= this.getY() && mouseY <= this.getY() + this.height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("滚动区域"));
        output.add(NarratedElementType.USAGE, Component.literal("使用鼠标滚轮滚动"));
    }
}