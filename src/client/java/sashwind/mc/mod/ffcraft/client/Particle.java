package sashwind.mc.mod.ffcraft.client;

public class Particle {
    net.minecraft.core.particles.ParticleOptions particle;
    double x;
    double y;
    double z;
    double xd;
    double yd;
    double zd;
    public Particle( net.minecraft.core.particles.ParticleOptions _particle,
                     double _x,
                     double _y,
                     double _z,
                     double _xd,
                     double _yd,
                     double _zd) {
        particle = _particle;
        x = _x;
        y = _y;
        z = _z;
        xd = _xd;
        yd = _yd;
        zd = _zd;
    }
}
