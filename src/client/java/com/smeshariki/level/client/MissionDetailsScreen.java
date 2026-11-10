package com.smeshariki.level.client;

import java.util.List;

import com.smeshariki.level.net.LevelList;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** «Подробнее» с экрана: текущая миссия идущих уровней игрока — описание, её задачи и ход всех миссий. */
final class MissionDetailsScreen extends Screen {
	private static final int W = 360, LINE = 11, WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, GOLD = 0xFFFFD98A, GREEN = 0xFF6FBF73, YELLOW = 0xFFFFE066;

	MissionDetailsScreen() {
		super(Component.translatable("gui.levelmod.details"));
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.close"), b -> onClose()).bounds((width - 120) / 2, height - 28, 120, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int w = Math.min(W, width - 24), x = (width - w) / 2, y = 14;
		g.centeredText(font, title, width / 2, y, WHITE);
		y += 18;
		List<LevelList.LevelInfo> running = TaskHud.running(minecraft);
		if (running.isEmpty()) {
			g.centeredText(font, Component.translatable("gui.levelmod.details.none_running"), width / 2, y, GRAY);
			return;
		}
		for (LevelList.LevelInfo level : running) {
			int current = TaskHud.currentMission(level.tasks());
			if (current < 0) continue;
			g.text(font, level.title().copy().withStyle(ChatFormatting.BOLD), x, y, WHITE);
			y += LINE + 2;
			g.text(font, Component.translatable("gui.levelmod.hud.mission", current + 1, level.missions().size(), level.missions().get(current)).withStyle(ChatFormatting.BOLD), x, y, GOLD);
			y += LINE + 2;
			Component description = current < level.missionDescriptions().size() ? level.missionDescriptions().get(current) : Component.empty();
			if (description.getString().isBlank()) description = Component.translatable("gui.levelmod.details.no_description").withStyle(ChatFormatting.ITALIC);
			for (FormattedCharSequence line : font.split(description, w)) {
				g.text(font, line, x, y, WHITE);
				y += LINE;
			}
			y += 6;
			g.text(font, Component.translatable("gui.levelmod.tasks"), x, y, GRAY);
			y += LINE;
			for (LevelList.TaskInfo task : level.tasks()) {
				if (task.mission() != current || !task.mine() || task.missed()) continue;
				String mark = task.completed() ? "✔ " : task.started() ? "▶ " : "○ ";
				String counter = task.counter().isEmpty() || task.completed() ? "" : " " + task.counter();
				for (FormattedCharSequence line : font.split(Component.literal(mark).append(TaskHud.titleOf(task)).append(counter), w - 8)) {
					g.text(font, line, x + 8, y, task.completed() ? GREEN : task.started() ? YELLOW : GRAY);
					y += LINE;
				}
			}
			y += 6;
			// ход всех миссий уровня
			for (int i = 0; i < level.missions().size(); i++) {
				boolean done = true;
				for (LevelList.TaskInfo task : level.tasks()) if (task.mission() == i && !task.completed() && !task.optional()) done = false;
				String mark = done ? "✔ " : i == current ? "▶ " : "○ ";
				g.text(font, Component.literal(mark).append(level.missions().get(i)), x, y, done ? GREEN : i == current ? YELLOW : GRAY);
				y += LINE;
			}
			y += 10;
		}
	}
}
