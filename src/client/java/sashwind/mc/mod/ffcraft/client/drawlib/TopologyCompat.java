package sashwind.mc.mod.ffcraft.client.drawlib;

/**
 * 跨 MC 版本兼容的拓扑类型枚举。
 * 替代 26.1 的 VertexFormat.Mode 和 26.2 的 PrimitiveTopology。
 */
public enum TopologyCompat {
    TRIANGLES,
    LINES,
    DEBUG_LINE_STRIP,
    QUADS;

    boolean isLine() {
        return this == LINES || this == DEBUG_LINE_STRIP;
    }

    boolean isQuads() {
        return this == QUADS;
    }
}
