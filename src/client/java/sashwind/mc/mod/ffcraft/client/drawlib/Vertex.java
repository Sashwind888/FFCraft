package sashwind.mc.mod.ffcraft.client.drawlib;

public class Vertex {
    public float x, y, z;
    public float r, g, b, a;
    public float u, v;
    public int light;
    public float nx, ny, nz;

    public Vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int light, float nx, float ny, float nz) {
        this.x = x; this.y = y; this.z = z;
        this.r = r; this.g = g; this.b = b; this.a = a;
        this.u = u; this.v = v;
        this.light = light;
        this.nx = nx; this.ny = ny; this.nz = nz;
    }
}
