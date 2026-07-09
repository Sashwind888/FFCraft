package sashwind.mc.mod.ffcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

public class ParticlesShower {
    static ClientLevel level = Minecraft.getInstance().level; // 注意是 .level 而不是 .world
    static List<Particle> particles = new ArrayList<>();
    double border = 0.1f;


    public void drawLine(net.minecraft.core.particles.ParticleOptions particle, double x1, double y1, double z1, double x2, double y2, double z2) {
        if (level != null) {
            List<Double> x = new ArrayList<>();
            List<Double> y = new ArrayList<>();
            List<Double> z = new ArrayList<>();

            if (x2 > x1) for (int i = 0; i < (x2 - x1) / border; i++) {
                x.add(x1 + border * i);
            } else for (int i = 0; i < (x1 - x2) / border; i++) {
                x.add(x2 + border * i);
            }
            if (y2 > y1) for (int i = 0; i < (y2 - y1) / border; i++) {
                y.add(y1 + border * i);
            } else for (int i = 0; i < (y1 - y2) / border; i++) {
                y.add(y2 + border * i);
            }

            if (z2 > z1) for (int i = 0; i < (z2 - z1) / border; i++) {
                z.add(z1 + border * i);
            } else for (int i = 0; i < (z1 - z2) / border; i++) {
                z.add(z2 + border * i);
            }

            for (int i = 0; i < x.size(); i++) {
                particles.add(new Particle(ParticleTypes.ENCHANT, x.get(i), y.get(i), z.get(i), 0.0, 0.0, 0.0));
            }
        }
    }

    public static void ParticlesShowerTick() {
        for (Particle p : particles) level.addParticle(
                    p.particle,
                    p.x, p.y, p.z,
                    p.xd, p.yd, p.zd
            );
    }
}
