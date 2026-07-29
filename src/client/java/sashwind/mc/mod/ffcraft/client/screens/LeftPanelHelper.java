package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sashwind.mc.mod.ffcraft.common.model.*;

import java.util.*;

/**
 * 左侧面板 —— 播放器和屏幕列表的渲染与交互。
 */
public class LeftPanelHelper {

    public static final int PANE_X = 8;
    public static final int PANE_Y = 28;
    public static final int PANE_W = 130;
    private static final int PANE_BOTTOM_MARGIN = 55;
    private static final int ENTRY_H = 14;
    private static final int COLOR_PANEL_BG = 0xAA111111;
    private static final int COLOR_HEADER    = 0xFFAAAAAA;
    private static final int COLOR_TEXT       = 0xFFE0E0E0;
    private static final int COLOR_TEXT_DIM   = 0xFF888888;
    private static final int COLOR_SELECTED   = 0xFF444444;
    private static final int COLOR_ACCENT     = 0xFF55FF55;
    private static final int COLOR_BORDER     = 0xFF555555;

    public static int selectedPlayerIndex = -1;
    public static int selectedScreenIndex = -1;

    int playerListScroll = 0;
    int screenListScroll = 0;
    int renamingPlayerIdx = -1;
    java.util.UUID renamingScreenId;
    String renameTarget = "";
    boolean renameInputVisible = false;

    int playerListX, playerListY, playerListW, playerListH;
    int screenListX, screenListY, screenListW, screenListH;

    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
                       List<VideoPlayerData> players) {
        render(graphics, font, mouseX, mouseY, players, 400); // default fallback height
    }

    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
                       List<VideoPlayerData> players, int screenHeight) {
        int x = PANE_X, y = PANE_Y, w = PANE_W;
        int maxY = screenHeight - PANE_BOTTOM_MARGIN;
        int availH = maxY - y;
        int curY = y;

        // ---- 播放器列表 ----
        graphics.text(font, "▸ 播放器列表", x, curY, COLOR_HEADER);
        curY += ENTRY_H + 2;
        graphics.fill(x, curY, x + w, curY + 1, COLOR_BORDER);
        curY += 4;

        // 动态高度：播放器列表占可用高度的 55%
        int playerAreaH = Math.max(60, (availH - 60) * 55 / 100);
        playerListX = x; playerListY = curY; playerListW = w; playerListH = playerAreaH;
        graphics.fill(x - 1, curY - 1, x + w + 1, curY + playerAreaH + 1, COLOR_PANEL_BG);
        graphics.fill(x - 1, curY - 1, x + w + 1, curY, COLOR_BORDER);

        if (players.isEmpty()) {
            graphics.text(font, "（暂无）", x + 2, curY + 2, COLOR_TEXT_DIM);
        } else {
            int vis = playerAreaH / (ENTRY_H + 2);
            int maxScroll = Math.max(0, players.size() - vis);
            if (playerListScroll > maxScroll) playerListScroll = maxScroll;
            for (int i = playerListScroll; i < Math.min(players.size(), playerListScroll + vis); i++) {
                VideoPlayerData p = players.get(i);
                int ey = curY + 2 + (i - playerListScroll) * (ENTRY_H + 2);
                boolean sel = (i == selectedPlayerIndex);
                boolean hov = mouseX >= x && mouseX <= x + w && mouseY >= ey && mouseY <= ey + ENTRY_H;
                if (sel) graphics.fill(x, ey, x + w, ey + ENTRY_H, COLOR_SELECTED);
                else if (hov) graphics.fill(x, ey, x + w, ey + ENTRY_H, 0x55333333);
                String nm = p.name();
                if (nm.length() > 16) nm = nm.substring(0, 15) + "…";
                graphics.text(font, nm, x + 3, ey + 1, sel ? COLOR_ACCENT : COLOR_TEXT);
            }
        }
        curY += playerAreaH + 4;

        // ---- 屏幕列表 ----
        graphics.fill(x, curY, x + w, curY + 1, COLOR_BORDER);
        curY += 4;
        graphics.text(font, "▸ 屏幕列表", x, curY, COLOR_HEADER);
        curY += ENTRY_H + 2;

        // 动态高度：屏幕列表占剩余空间
        int screenAreaH = Math.max(40, maxY - curY - 4);
        screenListX = x; screenListY = curY; screenListW = w; screenListH = screenAreaH;
        graphics.fill(x - 1, curY - 1, x + w + 1, curY + screenAreaH + 1, COLOR_PANEL_BG);
        graphics.fill(x - 1, curY - 1, x + w + 1, curY, COLOR_BORDER);

        List<VideoScreenData> screens = (selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size())
                ? players.get(selectedPlayerIndex).screens() : List.of();
        if (screens.isEmpty()) {
            graphics.text(font, "（暂无）", x + 2, curY + 2, COLOR_TEXT_DIM);
        } else {
            int vis = screenAreaH / (ENTRY_H + 2);
            int maxScroll = Math.max(0, screens.size() - vis);
            if (screenListScroll > maxScroll) screenListScroll = maxScroll;
            for (int i = screenListScroll; i < Math.min(screens.size(), screenListScroll + vis); i++) {
                VideoScreenData sc = screens.get(i);
                int ey = curY + 2 + (i - screenListScroll) * (ENTRY_H + 2);
                boolean sel = (i == selectedScreenIndex);
                boolean hov = mouseX >= x && mouseX <= x + w && mouseY >= ey && mouseY <= ey + ENTRY_H;
                if (sel) graphics.fill(x, ey, x + w, ey + ENTRY_H, COLOR_SELECTED);
                else if (hov) graphics.fill(x, ey, x + w, ey + ENTRY_H, 0x55333333);
                String nm = sc.name();
                if (nm.length() > 16) nm = nm.substring(0, 15) + "…";
                graphics.text(font, nm, x + 3, ey + 1, sel ? COLOR_ACCENT : COLOR_TEXT);
            }
        }

        renameInputVisible = (renamingPlayerIdx >= 0 || renamingScreenId != null);
    }

    // ---- 鼠标交互 ----

    public boolean mouseClicked(int mouseX, int mouseY, List<VideoPlayerData> players) {
        if (isIn(mouseX, mouseY, playerListX, playerListY, playerListW, playerListH)) {
            int idx = (mouseY - playerListY - 2) / (ENTRY_H + 2) + playerListScroll;
            if (idx >= 0 && idx < players.size()) {
                selectedPlayerIndex = idx; selectedScreenIndex = -1;
                renamingPlayerIdx = -1; renamingScreenId = null;
                return true;
            }
        }
        if (isIn(mouseX, mouseY, screenListX, screenListY, screenListW, screenListH)
                && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            var screens = players.get(selectedPlayerIndex).screens();
            int idx = (mouseY - screenListY - 2) / (ENTRY_H + 2) + screenListScroll;
            if (idx >= 0 && idx < screens.size()) {
                selectedScreenIndex = idx; renamingPlayerIdx = -1; renamingScreenId = null;
                return true;
            }
        }
        return false;
    }

    public boolean mouseDoubleClicked(int mouseX, int mouseY, List<VideoPlayerData> players) {
        if (isIn(mouseX, mouseY, playerListX, playerListY, playerListW, playerListH)) {
            int idx = (mouseY - playerListY - 2) / (ENTRY_H + 2) + playerListScroll;
            if (idx >= 0 && idx < players.size()) {
                renamingPlayerIdx = idx; renameTarget = players.get(idx).name(); renamingScreenId = null;
                return true;
            }
        }
        if (isIn(mouseX, mouseY, screenListX, screenListY, screenListW, screenListH)
                && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            var screens = players.get(selectedPlayerIndex).screens();
            int idx = (mouseY - screenListY - 2) / (ENTRY_H + 2) + screenListScroll;
            if (idx >= 0 && idx < screens.size()) {
                renamingScreenId = screens.get(idx).id(); renameTarget = screens.get(idx).name(); renamingPlayerIdx = -1;
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(int mouseX, int mouseY, double scrollY, List<VideoPlayerData> players) {
        if (isIn(mouseX, mouseY, playerListX, playerListY, playerListW, playerListH)) {
            int vis = playerListH / (ENTRY_H + 2);
            playerListScroll = clampScroll(playerListScroll - (int) scrollY * 2, players.size(), vis);
            return true;
        }
        if (isIn(mouseX, mouseY, screenListX, screenListY, screenListW, screenListH)
                && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            int vis = screenListH / (ENTRY_H + 2);
            screenListScroll = clampScroll(screenListScroll - (int) scrollY * 2,
                    players.get(selectedPlayerIndex).screens().size(), vis);
            return true;
        }
        return false;
    }

    // ---- 重命名 ----

    public boolean isRenamingPlayer() { return renamingPlayerIdx >= 0; }
    public java.util.UUID getRenamingScreenId() { return renamingScreenId; }
    public String getRenameTarget() { return renameTarget; }
    public boolean isRenameInputVisible() { return renameInputVisible; }
    public int getRenameInputX() { return PANE_X + 2; }
    public int getRenameInputW() { return PANE_W - 4; }
    public int getRenameInputH() { return ENTRY_H; }

    public int getRenameInputY(List<VideoPlayerData> players) {
        if (renamingPlayerIdx >= 0) {
            int vis = playerListH / (ENTRY_H + 2);
            int relIdx = renamingPlayerIdx - playerListScroll;
            if (relIdx >= 0 && relIdx < vis) return playerListY + 2 + relIdx * (ENTRY_H + 2);
        }
        if (renamingScreenId != null && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            var screens = players.get(selectedPlayerIndex).screens();
            for (int i = 0; i < screens.size(); i++) {
                if (screens.get(i).id().equals(renamingScreenId)) {
                    int vis = screenListH / (ENTRY_H + 2);
                    int relIdx = i - screenListScroll;
                    if (relIdx >= 0 && relIdx < vis) return screenListY + 2 + relIdx * (ENTRY_H + 2);
                }
            }
        }
        return 0;
    }

    public void cancelRename() { renamingPlayerIdx = -1; renamingScreenId = null; }

    /** 返回 "player:<uuid>" 或 "screen:<uuid>"，用于调用方发送重命名请求 */
    public String confirmRename(List<VideoPlayerData> players, String newName) {
        String nm = newName.trim();
        if (nm.isEmpty()) { cancelRename(); return null; }
        if (renamingPlayerIdx >= 0 && renamingPlayerIdx < players.size()) {
            var id = players.get(renamingPlayerIdx).id();
            cancelRename();
            return "player:" + id;
        }
        if (renamingScreenId != null && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            cancelRename();
            return "screen:" + renamingScreenId;
        }
        cancelRename();
        return null;
    }

    private static boolean isIn(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }

    private static int clampScroll(int scroll, int totalItems, int visibleCount) {
        return Math.max(0, Math.min(scroll, Math.max(0, totalItems - visibleCount)));
    }
}
