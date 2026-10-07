package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;

import java.util.UUID;

/**
 * 进度/搜藏条 —— 可拖拽的播放进度条，带时间标签和索引显示。
 */
public class SeekBarHelper {

    public static final int BAR_H = 14;
    private static final int COLOR_BG       = 0xFF333333;
    private static final int COLOR_PROGRESS = 0xFF44AA44;
    private static final int COLOR_LIVE     = 0xFFFF4444;
    private static final int COLOR_BORDER   = 0xFF555555;

    int barX, barY, barW;
    public int currentSecs;   // 当前显示的秒数
    boolean dragging;
    float dragFraction;
    int maxSecs;
    boolean isLive;

    public void reset() { currentSecs = 0; dragging = false; dragFraction = 0f; }

    public int render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
                       VideoPlayerData player, int x, int y, int w) {
        barX = x; barY = y; barW = w;
        PlaybackState pb = player.playbackState();
        int ci = pb.currentIndex();
        int tot = player.playlist().size();

        if (pb.status() == PlaybackStatus.STOPPED) { reset(); }

        float frac = maxSecs > 0 ? (float) currentSecs / maxSecs : 0f;

        graphics.fill(x, y, x + w, y + BAR_H, COLOR_BG);
        graphics.fill(x, y, x + (int) (w * frac), y + BAR_H, isLive ? COLOR_LIVE : COLOR_PROGRESS);
        graphics.fill(x, y, x + w, y + 1, COLOR_BORDER);
        graphics.fill(x, y + BAR_H - 1, x + w, y + BAR_H, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + BAR_H, COLOR_BORDER);
        graphics.fill(x + w - 1, y, x + w, y + BAR_H, COLOR_BORDER);

        String ts = String.format("%d:%02d / %d:%02d", currentSecs / 60, currentSecs % 60, maxSecs / 60, maxSecs % 60);
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + BAR_H;
        int tsW = font.width(ts);
        graphics.text(font, ts, (int) (x + (w - tsW) / 2f), y + 3, hover ? 0xFFFFFF00 : 0xFFAAAAAA);

        String idx = (ci + 1) + "/" + Math.max(tot, 1);
        graphics.text(font, idx, x + w - font.width(idx) - 4, y + 3, 0xFF888888);

        return BAR_H;
    }

    public void syncToDuration(double durationSecs, int localSecs) {
        maxSecs = Math.max((int) durationSecs, localSecs + 1);
        if (!dragging) currentSecs = localSecs;
    }

    public void setIsLive(boolean live) { isLive = live; }

    public boolean isInBar(int mx, int my) {
        return mx >= barX && mx <= barX + barW && my >= barY && my <= barY + BAR_H;
    }

    public boolean startDrag(int mx) {
        dragging = true;
        dragFraction = Math.max(0f, Math.min(1f, (float) (mx - barX) / barW));
        currentSecs = (int) (dragFraction * maxSecs);
        return true;
    }

    public void updateDrag(int mx) {
        if (!dragging) return;
        dragFraction = Math.max(0f, Math.min(1f, (float) (mx - barX) / barW));
        currentSecs = (int) (dragFraction * maxSecs);
    }

    public boolean finishDrag(UUID playerId) {
        if (!dragging) return false;
        dragging = false;
        VideoPlayerClientNetworking.seekPlayback(playerId, currentSecs);
        ClientVideoPlaybackManager.seekStream(playerId, currentSecs);
        return true;
    }
}
