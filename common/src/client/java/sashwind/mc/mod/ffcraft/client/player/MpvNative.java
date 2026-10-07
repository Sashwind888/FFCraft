package sashwind.mc.mod.ffcraft.client.player;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;

import java.util.List;

/**
 * JNA 绑定 libmpv C API — 基于 mpv 官方 client.h / render.h。
 * 参照官方示例的 JNA 模式（long 句柄、mpv_event 按值返回）。
 * 运行时需要 mpv-2.dll (Win) / libmpv.so.2 (Linux) / libmpv.2.dylib (macOS)。
 */
public interface MpvNative extends Library {

    // ---- 格式 ----
    int MPV_FORMAT_NONE   = 0;
    int MPV_FORMAT_STRING = 1;
    int MPV_FORMAT_FLAG   = 3;
    int MPV_FORMAT_INT64  = 4;
    int MPV_FORMAT_DOUBLE = 5;

    // ---- 事件 ----
    int MPV_EVENT_NONE           = 0;
    int MPV_EVENT_SHUTDOWN       = 1;
    int MPV_EVENT_COMMAND_REPLY  = 6;
    int MPV_EVENT_END_FILE       = 7;
    int MPV_EVENT_FILE_LOADED    = 8;
    int MPV_EVENT_IDLE           = 11;
    int MPV_EVENT_PROPERTY_CHANGE = 22;
    int MPV_EVENT_LOG_MESSAGE     = 25;
    int MPV_EVENT_VIDEO_RECONFIG  = 27;

    // ---- 渲染参数类型（mpv 0.41 render.h 编号） ----
    int MPV_RENDER_PARAM_API_TYPE        = 1;
    int MPV_RENDER_PARAM_ADVANCED_CONTROL = 10;
    int MPV_RENDER_PARAM_SW_SIZE         = 17;
    int MPV_RENDER_PARAM_SW_FORMAT       = 18;
    int MPV_RENDER_PARAM_SW_STRIDE       = 19;
    int MPV_RENDER_PARAM_SW_POINTER      = 20;
    // 音频渲染参数（仅 mpv 0.33-0.36 存在，0.37+ 已移除该 API）
    int MPV_RENDER_PARAM_AUDIO_FORMAT     = 20;
    int MPV_RENDER_PARAM_AUDIO_SAMPLES    = 21;
    int MPV_RENDER_PARAM_AUDIO_DATA       = 22;
    int MPV_RENDER_PARAM_AUDIO_CHANNELS   = 23;
    int MPV_RENDER_PARAM_AUDIO_SAMPLERATE = 24;

    String MPV_RENDER_API_TYPE_SW = "sw";

    // ---- 音频采样格式（mpv_sample_format） ----
    int MPV_SAMPLE_FMT_UNKNOWN = 0;
    int MPV_SAMPLE_FMT_U8      = 1;
    int MPV_SAMPLE_FMT_S16     = 2;
    int MPV_SAMPLE_FMT_S32     = 3;
    int MPV_SAMPLE_FMT_FLOAT   = 4;
    int MPV_SAMPLE_FMT_DOUBLE  = 5;

    // ===== 核心 API ====

    long mpv_client_api_version();
    long mpv_create();
    int mpv_initialize(long handle);
    void mpv_terminate_destroy(long handle);
    int mpv_set_option_string(long handle, String name, String data);
    int mpv_command_string(long handle, String args);
    int mpv_command_async(long handle, long reply_userdata, String[] args);
    String mpv_error_string(int error);

    /** 按值返回 — 官方示例模式 */
    mpv_event mpv_wait_event(long handle, double timeout);

    // ===== 属性 ====

    int mpv_set_property(long handle, String name, int format, Pointer data);
    int mpv_get_property(long handle, String name, int format, Pointer data);
    int mpv_observe_property(long handle, long reply_userdata, String name, int format);
    Pointer mpv_get_property_string(long handle, String name);
    int mpv_set_property_string(long handle, String name, String data);
    void mpv_free(Pointer data);
    int mpv_request_log_messages(long handle, String min_level);

    // ===== 渲染上下文 ====

    int mpv_render_context_create(PointerByReference res, long mpv, MpvRenderParam[] params);
    int mpv_render_context_render(long ctx, MpvRenderParam[] params);
    void mpv_render_context_set_update_callback(long ctx, MpvUpdateCallback callback, long data);
    void mpv_render_context_free(long ctx);

    // ===== 音频渲染上下文（mpv 0.33+，需 ao=null） =====

    int mpv_audio_render_context_create(PointerByReference res, long mpv);
    int mpv_audio_render_context_render(long ctx, MpvRenderParam[] params);
    void mpv_audio_render_context_free(long ctx);


    // ===== 结构体 ====

    @Structure.FieldOrder({"event_id", "error", "reply_userdata", "data"})
    class mpv_event extends Structure {
        public int event_id;
        public int error;
        public long reply_userdata;
        public Pointer data;

        public mpv_event() {}
        public mpv_event(Pointer p) { super(p); read(); }
    }

    @Structure.FieldOrder({"name", "format", "data"})
    class mpv_event_property extends Structure {
        public String name;
        public int format;
        public Pointer data;

        public mpv_event_property() {}
        public mpv_event_property(Pointer p) { super(p); read(); }
    }

    @Structure.FieldOrder({"prefix", "level", "text", "log_level"})
    class mpv_event_log_message extends Structure {
        public String prefix;
        public String level;
        public String text;
        public int log_level;

        public mpv_event_log_message() {}
        public mpv_event_log_message(Pointer p) { super(p); read(); }
    }

    @Structure.FieldOrder({"type", "data"})
    class MpvRenderParam extends Structure {
        public int type;
        public Pointer data;

        public MpvRenderParam() {}

        /**
         * 分配连续的结构体数组（JNA 要求结构体数组参数内存连续）。
         * 之后用 set() 填充元素。
         */
        public static MpvRenderParam[] alloc(int count) {
            return (MpvRenderParam[]) new MpvRenderParam().toArray(count);
        }

        /** 填充一个元素：type + data（data 可为 null 表示终止符） */
        public static MpvRenderParam set(MpvRenderParam[] arr, int idx, int type, Pointer data) {
            arr[idx].type = type;
            arr[idx].data = data;
            return arr[idx];
        }

        // ---- data 指针分配器 ----

        /** C 字符串（type=MPV_RENDER_PARAM_API_TYPE 时用） */
        public static Pointer cstr(String value) {
            Memory m = new Memory(value.length() + 1);
            m.setString(0, value);
            return m;
        }

        /** int 值内存 */
        public static Pointer i32(int value) {
            Memory m = new Memory(4);
            m.setInt(0, value);
            return m;
        }

        /** 两个 int */
        public static Pointer i32x2(int v0, int v1) {
            Memory m = new Memory(8);
            m.setInt(0, v0);
            m.setInt(4, v1);
            return m;
        }

        /** int64 值内存 */
        public static Pointer i64(long value) {
            Memory m = new Memory(8);
            m.setLong(0, value);
            return m;
        }

        /** 原始地址指针（SW_POINTER 用） */
        public static Pointer ptr(long address) {
            return new Pointer(address);
        }

        /** 直接传 Pointer（SW_POINTER 传 renderTarget 指针 / 引用内存） */
        public static Pointer ptrP(Pointer p) {
            return p;
        }
    }

    /** 帧就绪回调：void (*)(void *data) */
    interface MpvUpdateCallback extends Callback {
        void invoke(long data);
    }
}
