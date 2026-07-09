package sashwind.mc.mod.ffcraft;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import org.lwjgl.system.MemoryUtil;

public class FastImageConverter {

    /**
     * 将 BufferedImage (TYPE_INT_ARGB) 快速转换为 NativeImage (Format.RGBA)
     *
     * BufferedImage 像素格式：ARGB (0xAARRGGBB)
     * NativeImage RGBA 原生内存：字节序 R,G,B,A → 在 little-endian 上读作 int 即 ABGR (0xAABBGGRR)
     */
    public static NativeImage bufferedImageToNativeImageFast(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        // 获取 BufferedImage 的底层 ARGB 像素数组
        int[] argbPixels = ((DataBufferInt) bufferedImage.getRaster().getDataBuffer()).getData();

        // 创建 NativeImage
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        // ★ 关键修复：通过 getPointer() 获取原生内存地址，直接写入
        // IntBuffer.wrap(nativeImage.getPixels()) 包装的是 getPixels() 返回的拷贝，
        // 写入会丢失！必须直接操作原生内存。
        long ptr = nativeImage.getPointer();
        java.nio.IntBuffer nativeIntBuf = MemoryUtil.memIntBuffer(ptr, width * height);

        // ARGB → ABGR 转换后直接写入原生内存
        // 在 little-endian 系统上，int 0xAABBGGRR 写入内存后字节序为 R,G,B,A
        // 与 NativeImage RGBA 格式的原生内存布局完全一致
        int[] abgrPixels = new int[argbPixels.length];
        for (int i = 0; i < argbPixels.length; i++) {
            int argb = argbPixels[i];
            int a = (argb >> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            abgrPixels[i] = (a << 24) | (b << 16) | (g << 8) | r;  // → 0xAABBGGRR
        }
        nativeIntBuf.put(abgrPixels);

        return nativeImage;
    }
}