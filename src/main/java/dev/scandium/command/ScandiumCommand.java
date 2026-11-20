package dev.scandium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.gc.AllocationProfiler;
import dev.scandium.gc.GcEnvironment;
import dev.scandium.gc.GcScheduler;
import dev.scandium.gc.JvmProfiles;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /scandium enable}, {@code /scandium disable} (A/B testing against vanilla behaviour) and
 * {@code /scandium bench} (handled client-side, see {@code FrameBenchmark}), {@code /scandium alloc} (allocation
 * profile) and {@code /scandium jvm} (recommended JVM arguments).
 * <p>
 * Allowed for operators (level 2) and, in singleplayer, for the world owner even without cheats.
 */
public final class ScandiumCommand {
    private ScandiumCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("scandium")
                .requires(source -> source.hasPermission(2) || !source.getServer().isDedicatedServer())
                .then(Commands.literal("enable").executes(ctx -> toggle(ctx, true)))
                .then(Commands.literal("disable").executes(ctx -> toggle(ctx, false)))
                // Executed by the client mod before it reaches the server; registered here for tab completion.
                // Only reaches this point if the player's client doesn't have Scandium.
                .then(Commands.literal("bench").executes(ctx -> {
                    ctx.getSource().sendFailure(Component.literal(
                            "/scandium bench measures client FPS and needs Scandium installed on the client"));
                    return 0;
                }))
                .then(Commands.literal("vkshot").executes(ctx -> {
                    ctx.getSource().sendFailure(Component.literal(
                            "/scandium vkshot captures the client's Vulkan frame and needs Scandium on the client"));
                    return 0;
                }))
                .then(Commands.literal("present")
                        .then(Commands.literal("vulkan").executes(ctx -> 0))
                        .then(Commands.literal("opengl").executes(ctx -> 0)))
                .then(Commands.literal("alloc").executes(ScandiumCommand::alloc))
                .then(Commands.literal("jvm").executes(ScandiumCommand::jvm)));
    }

    /** Allocation profile of this JVM (in singleplayer: client and integrated server together). */
    private static int alloc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean started = AllocationProfiler.start(lines -> source.getServer().execute(() -> {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Scandium.LOGGER.info("[alloc] {}", line);
                ChatFormatting style = i == 0 ? ChatFormatting.AQUA
                        : line.startsWith("Top") ? ChatFormatting.WHITE : ChatFormatting.GRAY;
                source.sendSuccess(() -> Component.literal(line).withStyle(style), false);
            }
        }));
        if (!started) {
            source.sendFailure(Component.literal("Allocation profiling is already running"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Scandium allocation profiling: 15 s, keep playing normally...")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Recommended JVM arguments for this machine, click to copy. */
    private static int jvm(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        JvmProfiles.Profile profile = JvmProfiles.recommend();
        GcEnvironment env = GcEnvironment.get();
        source.sendSuccess(() -> Component.literal("Current: " + env.collector.displayName + ", max heap "
                + (env.maxHeapBytes >> 20) + " MB, explicit GC "
                + (env.explicitGcDisabled ? "disabled" : env.explicitGcIsConcurrent ? "concurrent" : "stop-the-world"))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Recommended: " + profile.kind().displayName + " ("
                + profile.reason() + ")" + (JvmProfiles.matchesCurrent(profile) ? " - already in use" : ""))
                .withStyle(ChatFormatting.AQUA), false);
        String line = profile.commandLine();
        source.sendSuccess(() -> Component.literal(line).withStyle(style -> style
                .withColor(ChatFormatting.WHITE)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, line))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")))),
                false);
        source.sendSuccess(() -> Component.literal("Paste into the launcher's JVM arguments (replace existing -Xmx/-Xms "
                + "and GC flags) and restart.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx, boolean enable) {
        CommandSourceStack source = ctx.getSource();
        if (ScandiumConfig.get().enabled == enable) {
            source.sendSuccess(() -> Component.literal("Scandium is already " + (enable ? "enabled" : "disabled"))
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        GcScheduler.get().setEnabled(enable);
        // Region batching keeps terrain outside vanilla buffers: switching needs a rebuild of all sections.
        Runnable clientHook = Scandium.clientToggleHook;
        if (clientHook != null) {
            clientHook.run();
        }
        source.sendSuccess(() -> Component.literal("Scandium " + (enable ? "enabled" : "disabled"))
                .withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        source.sendSuccess(() -> Component.literal(enable
                ? "GC scheduling, JVM hints, region batching and runtime optimizations are active again (terrain "
                        + "is being rebuilt). Restart the game to re-apply the mixin-level optimizations "
                        + "(FerriteCore, leak fixes, mesh thread pool)."
                : "GC scheduling stopped, JVM hints reverted, terrain rebuilt with vanilla rendering. Restart the "
                        + "game to also remove the mixin-level optimizations and get a clean vanilla baseline.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
