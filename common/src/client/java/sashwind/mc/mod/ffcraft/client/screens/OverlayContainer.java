package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖式容器 - 像 Windows 窗口一样叠加在屏幕上
 * 支持模态、自动焦点管理、事件转发
 */
public class OverlayContainer extends AbstractWidget {
    private final List<AbstractWidget> children = new ArrayList<>();
    private boolean modal = true;
    private AbstractWidget focusedChild = null; // 当前获得焦点的子控件

    public OverlayContainer(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    // ==================== 子控件管理 ====================

    /**
     * 添加子控件，后添加的位于上层
     */
    public <T extends AbstractWidget> T addChild(T child) {
        this.children.add(child);
        return child;
    }

    /**
     * 移除所有子控件
     */
    public void clearChildren() {
        this.children.clear();
        this.focusedChild = null;
    }

    /**
     * 设置是否模态（点击外部自动关闭）
     */
    public void setModal(boolean modal) {
        this.modal = modal;
    }

    // ==================== 焦点管理 ====================

    /**
     * 设置焦点到指定子控件，并取消旧焦点
     */
    public void setFocusedChild(AbstractWidget child) {
        // 取消旧焦点
        if (this.focusedChild != null && this.focusedChild != child) {
            this.focusedChild.setFocused(false);
        }
        this.focusedChild = child;
        if (child != null) {
            child.setFocused(true);
        }
    }

    /**
     * 获取当前获得焦点的子控件
     */
    public AbstractWidget getFocusedChild() {
        return this.focusedChild;
    }

    /**
     * 与 Minecraft 的焦点系统同步：
     * 当 Screen 调用 setFocused(true) 时，将焦点转移给第一个可聚焦的子控件
     * 当 Screen 调用 setFocused(false) 时，清除所有子控件焦点
     */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            // 失去焦点：取消子控件焦点，但记住是哪个（恢复时还原）
            if (this.focusedChild != null) {
                this.focusedChild.setFocused(false);
            }
        } else {
            // 获得焦点：恢复之前的焦点子控件，或自动聚焦第一个
            if (this.focusedChild != null && this.focusedChild.visible && this.focusedChild.isActive()) {
                this.focusedChild.setFocused(true);
            } else {
                // 之前的焦点子控件已不可用，清除并重新查找
                this.focusedChild = null;
                focusFirst();
            }
        }
    }

    /**
     * 尝试将焦点转移到第一个可聚焦的子控件（用于 Tab 键等）
     */
    public boolean focusFirst() {
        for (AbstractWidget child : this.children) {
            if (child.visible && child.isActive() && child instanceof GuiEventListener) {
                setFocusedChild(child);
                return true;
            }
        }
        return false;
    }

    public boolean isModal() {
        return this.modal;
    }

    // ==================== 渲染 ====================

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        // 1. 半透明遮罩
        if (this.modal) {
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0x88000000);
        }

        // 2. 弹窗背景
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;

        graphics.fill(x, y, x + w, y + h, 0xFF333333);
        // 白色边框
        graphics.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);

        // 3. 渲染所有子控件
        for (AbstractWidget child : this.children) {
            if (child.visible) {
                child.extractRenderState(graphics, mouseX, mouseY, delta);
            }
        }
    }

    // ==================== 鼠标事件转发 ====================
    // --- Event API (MouseButtonEvent) — Screen 默认分派调用此签名 ---

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.visible || !this.isActive()) return false;

        double mouseX = event.x();
        double mouseY = event.y();

        if (this.modal && !isMouseOver(mouseX, mouseY)) {
            this.visible = false;
            setFocusedChild(null);
            return true;
        }

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive() && child.isMouseOver(mouseX, mouseY)) {
                if (child.mouseClicked(event, doubleClick)) {
                    setFocusedChild(child);
                    return true;
                }
            }
        }

        setFocusedChild(null);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!this.visible || !this.isActive()) return false;

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
        if (!this.visible || !this.isActive()) return false;

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

    // ==================== 键盘事件转发（优先焦点） ====================

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.visible || !this.isActive()) return false;

        // ESC 关闭弹窗
        if (event.key() == 256) {
            this.visible = false;
            setFocusedChild(null);
            return true;
        }

        // 优先交给当前焦点子控件
        if (this.focusedChild != null && this.focusedChild.visible && this.focusedChild.isActive()) {
            if (this.focusedChild.keyPressed(event)) {
                return true;
            }
        }

        // 否则遍历所有子控件（但跳过焦点控件，避免重复）
        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive() && child != this.focusedChild) {
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

        if (this.focusedChild != null && this.focusedChild.visible && this.focusedChild.isActive()) {
            if (this.focusedChild.keyReleased(event)) {
                return true;
            }
        }

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive() && child != this.focusedChild) {
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

        if (this.focusedChild != null && this.focusedChild.visible && this.focusedChild.isActive()) {
            if (this.focusedChild.charTyped(event)) {
                return true;
            }
        }

        for (int i = this.children.size() - 1; i >= 0; i--) {
            AbstractWidget child = this.children.get(i);
            if (child.visible && child.isActive() && child != this.focusedChild) {
                if (child.charTyped(event)) {
                    return true;
                }
            }
        }
        return super.charTyped(event);
    }

    // ==================== 辅助方法 ====================

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.visible &&
                mouseX >= this.getX() && mouseX <= this.getX() + this.width &&
                mouseY >= this.getY() && mouseY <= this.getY() + this.height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("弹窗"));
    }

    // ==================== 可选：Tab 键切换焦点 ====================
    // 如果需要 Tab 键切换，可重写 nextFocusPath 等，但这里不强制实现
}