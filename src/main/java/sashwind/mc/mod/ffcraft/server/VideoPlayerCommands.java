package sashwind.mc.mod.ffcraft.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import sashwind.mc.mod.ffcraft.common.model.PlaybackMode;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.server.state.ServerVideoPlayer;

public final class VideoPlayerCommands {
    private VideoPlayerCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register(VideoPlayerCommands::registerRoot);
    }

    private static void registerRoot(CommandDispatcher<CommandSourceStack> d, net.minecraft.commands.CommandBuildContext ctx, Commands.CommandSelection sel) {
        d.register(Commands.literal("ffcraft")
            .then(Commands.literal("list").executes(VideoPlayerCommands::list))
            .then(Commands.literal("info")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(VideoPlayerCommands::info)))
            .then(Commands.literal("play")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(c -> control(c, PlaybackStatus.PLAYING))))
            .then(Commands.literal("pause")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(c -> control(c, PlaybackStatus.PAUSED))))
            .then(Commands.literal("stop")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(c -> control(c, PlaybackStatus.STOPPED))))
            .then(Commands.literal("next")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(VideoPlayerCommands::next)))
            .then(Commands.literal("prev")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(VideoPlayerCommands::prev)))
            .then(Commands.literal("volume")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                        .executes(VideoPlayerCommands::volume))))
            .then(Commands.literal("mode")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> { b.suggest("顺序"); b.suggest("循环"); b.suggest("单曲"); b.suggest("随机"); return b.buildFuture(); })
                        .executes(VideoPlayerCommands::mode))))
            .then(Commands.literal("public")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(c -> setPublic(c, true))))
            .then(Commands.literal("private")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(playerSuggestions())
                    .executes(c -> setPublic(c, false))))
        );
    }

    private static com.mojang.brigadier.suggestion.SuggestionsBuilder suggestPlayers(com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        for (ServerVideoPlayer p : VideoPlayerServerRuntime.getService().players()) b.suggest(p.name());
        return b;
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (ctx, b) -> { suggestPlayers(b); return b.buildFuture(); };
    }

    private static ServerVideoPlayer resolve(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        return VideoPlayerServerRuntime.getService().players().stream()
                .filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在: " + name));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var svc = VideoPlayerServerRuntime.getService();
        if (svc.players().isEmpty()) { src.sendSuccess(() -> Component.literal("没有播放器"), false); return 1; }
        src.sendSuccess(() -> Component.literal("播放器列表 (" + svc.players().size() + "):"), false);
        for (ServerVideoPlayer p : svc.players())
            src.sendSuccess(() -> Component.literal("  " + p.name() + "  " + p.playbackState().status().name()), false);
        return svc.players().size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            var src = ctx.getSource();
            src.sendSuccess(() -> Component.literal("名称: " + p.name()), false);
            src.sendSuccess(() -> Component.literal("模式: " + (p.isPublic() ? "公开" : "私有")), false);
            src.sendSuccess(() -> Component.literal("状态: " + p.playbackState().status().name() + "  " + p.playbackState().mode().name()), false);
            src.sendSuccess(() -> Component.literal("音量: " + p.playbackState().volume() + "%"), false);
            src.sendSuccess(() -> Component.literal("列表: " + p.playlist().size() + " 首  屏幕: " + p.screens().size() + " 个"), false);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int control(CommandContext<CommandSourceStack> ctx, PlaybackStatus status) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            var old = p.playbackState();
            PlaybackState next = new PlaybackState(status, old.mode(), old.currentIndex(), old.progressSeconds(), old.volume(), System.currentTimeMillis() / 1000);
            VideoPlayerServerRuntime.getService().setPlaybackState(p.id(), next);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " → " + status.name()), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int next(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            int idx = (p.playbackState().currentIndex() + 1) % Math.max(1, p.playlist().size());
            PlaybackState next = new PlaybackState(PlaybackStatus.PLAYING, p.playbackState().mode(), idx, 0, p.playbackState().volume(), System.currentTimeMillis() / 1000);
            VideoPlayerServerRuntime.getService().setPlaybackState(p.id(), next);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " → 下一首"), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int prev(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            int idx = p.playbackState().currentIndex() - 1;
            if (idx < 0) idx = Math.max(0, p.playlist().size() - 1);
            PlaybackState next = new PlaybackState(PlaybackStatus.PLAYING, p.playbackState().mode(), idx, 0, p.playbackState().volume(), System.currentTimeMillis() / 1000);
            VideoPlayerServerRuntime.getService().setPlaybackState(p.id(), next);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " → 上一首"), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int volume(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            int v = IntegerArgumentType.getInteger(ctx, "value");
            PlaybackState next = new PlaybackState(p.playbackState().status(), p.playbackState().mode(), p.playbackState().currentIndex(), p.playbackState().progressSeconds(), v, p.playbackState().lastUpdatedEpochSeconds());
            VideoPlayerServerRuntime.getService().setPlaybackState(p.id(), next);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " 音量 → " + v), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int mode(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            PlaybackMode mode = switch (StringArgumentType.getString(ctx, "mode")) {
                case "顺序" -> PlaybackMode.SEQUENTIAL;
                case "循环" -> PlaybackMode.LOOP_LIST;
                case "单曲" -> PlaybackMode.SINGLE_LOOP;
                case "随机" -> PlaybackMode.RANDOM;
                default -> throw new IllegalArgumentException("无效 (顺序/循环/单曲/随机)");
            };
            PlaybackState next = new PlaybackState(p.playbackState().status(), mode, p.playbackState().currentIndex(), p.playbackState().progressSeconds(), p.playbackState().volume(), p.playbackState().lastUpdatedEpochSeconds());
            VideoPlayerServerRuntime.getService().setPlaybackState(p.id(), next);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " → " + mode.name()), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }

    private static int setPublic(CommandContext<CommandSourceStack> ctx, boolean pub) {
        try {
            ServerVideoPlayer p = resolve(ctx);
            VideoPlayerServerRuntime.getService().setPublic(p.id(), pub);
            VideoPlayerServerNetworking.syncAll();
            ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " → " + (pub ? "公开" : "私有")), true);
            return 1;
        } catch (Exception e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
    }
}
