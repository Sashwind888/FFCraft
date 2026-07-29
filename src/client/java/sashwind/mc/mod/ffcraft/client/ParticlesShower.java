package sashwind.mc.mod.ffcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

public class ParticlesShower {
    // 不再使用静态 level 引用，改为每次从 Minecraft.getInstance() 动态获取，
    // 避免切换维度/重连时持有旧 ClientLevel 引用导致泄漏。
    static final List<Particle> particles = new ArrayList<>();
    private static final double BORDER = 0.1;

    /** 获取当前 ClientLevel（动态获取，避免静态引用泄漏） */
    private static ClientLevel getClientLevel() {
        return Minecraft.getInstance().level;
    }

    public void drawLine(net.minecraft.core.particles.ParticleOptions particle, double x1, double y1, double z1, double x2, double y2, double z2) {
        ClientLevel level = getClientLevel();
        if (level != null) {
            List<Double> x = new ArrayList<>();
            List<Double> y = new ArrayList<>();
            List<Double> z = new ArrayList<>();

            if (x2 > x1) for (int i = 0; i < (x2 - x1) / BORDER; i++) {
                x.add(x1 + BORDER * i);
            } else for (int i = 0; i < (x1 - x2) / BORDER; i++) {
                x.add(x2 + BORDER * i);
            }
            if (y2 > y1) for (int i = 0; i < (y2 - y1) / BORDER; i++) {
                y.add(y1 + BORDER * i);
            } else for (int i = 0; i < (y1 - y2) / BORDER; i++) {
                y.add(y2 + BORDER * i);
            }

            if (z2 > z1) for (int i = 0; i < (z2 - z1) / BORDER; i++) {
                z.add(z1 + BORDER * i);
            } else for (int i = 0; i < (z1 - z2) / BORDER; i++) {
                z.add(z2 + BORDER * i);
            }

            for (int i = 0; i < x.size(); i++) {
                particles.add(new Particle(ParticleTypes.ENCHANT, x.get(i), y.get(i), z.get(i), 0.0, 0.0, 0.0));
            }
        }
    }

    public static void ParticlesShowerTick() {
        ClientLevel level = getClientLevel();
        if (level == null) return;
        for (Particle p : particles) {
            level.addParticle(
                    p.particle,
                    p.x, p.y, p.z,
                    p.xd, p.yd, p.zd
            );
        }
        // 每 tick 渲染完毕后清理，防止列表无界增长
        particles.clear();
    }

    /** 在维度切换/断连时主动清理 */
    public static void clearAll() {
        particles.clear();
    }
}
