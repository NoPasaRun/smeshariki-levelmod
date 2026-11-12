package com.smeshariki.level.run;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.smeshariki.level.definition.Levels;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Команды оператора: {@code /level complete [уровень]} завершает идущий уровень принудительно, {@code /level skip [уровень]} пропускает его текущую миссию.
 * Без аргумента берётся идущий уровень игрока (с консоли — все идущие). Время такого прогона в статистику не идёт ({@link LevelRun#forced}).
 */
public final class LevelCommands {
	private LevelCommands() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(Commands.literal("level").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("complete").executes(ctx -> run(ctx.getSource(), true, null))
						.then(Commands.argument("level", StringArgumentType.greedyString()).executes(ctx -> run(ctx.getSource(), true, StringArgumentType.getString(ctx, "level")))))
				.then(Commands.literal("skip").executes(ctx -> run(ctx.getSource(), false, null))
						.then(Commands.argument("level", StringArgumentType.greedyString()).executes(ctx -> run(ctx.getSource(), false, StringArgumentType.getString(ctx, "level")))))));
	}

	private static int run(CommandSourceStack source, boolean complete, String level) {
		LevelStore store = LevelStore.get(source.getServer());
		List<LevelRun> candidates = new ArrayList<>();
		if (level != null) {
			Identifier id = Identifier.tryParse(level.contains(":") ? level : "levelmod:" + level);
			for (LevelRun run : store.runs()) if (run.level().equals(id)) candidates.add(run);
		} else {
			ServerPlayer player = source.getPlayer();
			candidates.addAll(player != null ? store.runsWith(player.getUUID()) : store.runs());
		}
		candidates.removeIf(run -> run.state() != LevelRun.State.RUNNING);
		if (candidates.isEmpty()) {
			source.sendFailure(Component.translatable("message.levelmod.command.none"));
			return 0;
		}
		int done = 0;
		for (LevelRun run : candidates) {
			Component title = Levels.get(run.level()) == null ? Component.literal(run.level().toString()) : Levels.get(run.level()).definition().title();
			if (complete ? run.forceComplete() : run.skipMission()) {
				done++;
				source.sendSuccess(() -> Component.translatable(complete ? "message.levelmod.command.completed" : "message.levelmod.command.skipped", title), true);
			}
		}
		store.setDirty();
		return done;
	}
}
