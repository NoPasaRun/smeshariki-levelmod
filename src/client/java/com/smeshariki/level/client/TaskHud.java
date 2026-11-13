package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.level.LevelMod;
import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.run.LevelRun;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * Список в углу экрана: у каждого идущего уровня игрока панель с текущей миссией и задачами, которые касаются его самого, а под ней кнопка «Подробнее»
 * (клавиша {@link LevelModClient#DETAILS}, окно {@link MissionDetailsScreen}) с описанием миссии.
 */
final class TaskHud {
	private static final int LINE = 11, MARGIN = 6, WIDTH = 190, PAD = 6, GAP = 5;
	private static final int PANEL = 0xA00E1118, BAR = 0xFFFFC04D, TITLE = 0xFFA8B0BC, MISSION = 0xFFFFD98A;
	private static final int ACTIVE = 0xFFFFE066, DONE = 0xFF6FBF73, WAITING = 0xFF9AA3AE, KEY = 0xFF2B303B, BUTTON = 0xA01B2029;

	/** Список задач развёрнут (по умолчанию свёрнут: в углу только кнопка). */
	static boolean expanded;

	private TaskHud() {
	}

	static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "tasks"), TaskHud::extract);
	}

	/** Идущие уровни, в которых участвует игрок. */
	static List<LevelList.LevelInfo> running(Minecraft mc) {
		List<LevelList.LevelInfo> out = new ArrayList<>();
		if (mc.player == null) return out;
		for (LevelList.LevelInfo level : ClientLevels.levels) {
			var run = level.run().orElse(null);
			if (run != null && run.state() == LevelRun.State.RUNNING && run.participants().stream().anyMatch(p -> p.id().equals(mc.player.getUUID()))) out.add(level);
		}
		return out;
	}

	/** Номер текущей миссии: первой, где не готова обязательная задача; необязательные миссию не держат. {@code -1} — всё готово. */
	static int currentMission(List<LevelList.TaskInfo> tasks) {
		for (LevelList.TaskInfo task : tasks) if (!task.completed() && !task.optional()) return task.mission();
		return -1;
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || com.smeshariki.behavior.client.CutsceneClient.hidesHud()) return;
		List<LevelList.LevelInfo> running = running(mc);
		int y = chip(g, mc.font, running) + GAP;
		if (!ClientLevels.testTasks.isEmpty()) y = panel(g, mc.font, y, Component.translatable("gui.levelmod.hud.test", ""), null, -1, 0, ClientLevels.testTasks);
		if (!expanded && !running.isEmpty()) y = currentTask(g, mc.font, y, running.get(0));
		if (expanded) for (LevelList.LevelInfo level : running) if (y < g.guiHeight() - 70) y = panel(g, mc.font, y, level.title(), level.missions().isEmpty() ? null : level.missions(), currentMission(level.tasks()), level.missions().size(), level.tasks());
	}

	/** В свёрнутом виде: одна текущая задача игрока (начатая, а если таких нет — первая невыполненная). */
	private static int currentTask(GuiGraphicsExtractor g, Font font, int y, LevelList.LevelInfo level) {
		int current = currentMission(level.tasks());
		if (current < 0) return y;
		LevelList.TaskInfo pick = null;
		for (LevelList.TaskInfo task : level.tasks()) {
			if (task.mission() != current || !task.mine() || task.missed() || task.completed()) continue;
			if (task.started()) {
				pick = task;
				break;
			}
			if (pick == null) pick = task;
		}
		if (pick == null) return y;
		String counter = pick.counter().isEmpty() ? "" : " " + pick.counter();
		String text = font.substrByWidth(Component.literal("▶ ").append(titleOf(pick)).append(counter), WIDTH - 2 * PAD).getString();
		int h = LINE + 6;
		rounded(g, MARGIN, y, MARGIN + font.width(text) + 2 * PAD, y + h, PANEL);
		g.fill(MARGIN, y + 2, MARGIN + 2, y + h - 2, BAR);
		g.text(font, text, MARGIN + PAD, y + 3, ACTIVE);
		return y + h + GAP;
	}

	/** Подпись задачи; у необязательной — пометка. */
	static Component titleOf(LevelList.TaskInfo task) {
		return task.optional() ? Component.empty().append(task.title()).append(Component.translatable("gui.levelmod.task.optional_mark")) : task.title();
	}

	/** Прямоугольник со скруглёнными углами (срезан угловой пиксель). */
	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
	}

	private static int panel(GuiGraphicsExtractor g, Font font, int y, Component title, List<Component> missions, int current, int total, List<LevelList.TaskInfo> tasks) {
		if (missions != null && current < 0) return y;
		int textW = WIDTH - 2 * PAD - 4;
		List<FormattedCharSequence> header = new ArrayList<>();
		if (missions != null) header.addAll(font.split(Component.translatable("gui.levelmod.hud.mission", current + 1, total, missions.get(current)).withStyle(ChatFormatting.BOLD), textW));
		// строки задач: первая с отметкой, остальные с отступом
		record Row(FormattedCharSequence text, int color) {
		}
		List<Row> rows = new ArrayList<>();
		for (LevelList.TaskInfo task : tasks) {
			if (missions != null && task.mission() != current || !task.mine() || task.missed()) continue;
			String mark = task.completed() ? "✔ " : task.started() ? "▶ " : "○ ";
			String counter = task.counter().isEmpty() || task.completed() ? "" : " " + task.counter();
			int color = task.completed() ? DONE : task.started() ? ACTIVE : WAITING;
			List<FormattedCharSequence> lines = font.split(Component.literal(mark).append(titleOf(task)).append(counter), textW);
			for (FormattedCharSequence line : lines) rows.add(new Row(line, color));
		}
		// не выше низа экрана (под ним хотбар и чат): лишние строки заменяет «…»
		int room = (g.guiHeight() - 60 - y - PAD - LINE - header.size() * LINE - (header.isEmpty() ? 0 : 2) - PAD + 2) / LINE;
		if (room >= 1 && rows.size() > room) {
			rows = new ArrayList<>(rows.subList(0, room - 1));
			rows.add(new Row(Component.literal("…").getVisualOrderText(), WAITING));
		}
		int height = PAD + LINE + header.size() * LINE + (header.isEmpty() ? 0 : 2) + rows.size() * LINE + PAD - 2;
		int x = MARGIN;
		rounded(g, x, y, x + WIDTH, y + height, PANEL);
		g.fill(x, y + 2, x + 2, y + height - 2, BAR);
		int tx = x + PAD + 2, ty = y + PAD - 1;
		g.text(font, title, tx, ty, TITLE);
		ty += LINE;
		for (FormattedCharSequence line : header) {
			g.text(font, line, tx, ty, MISSION);
			ty += LINE;
		}
		if (!header.isEmpty()) ty += 2;
		for (Row row : rows) {
			g.text(font, row.text(), tx, ty, row.color());
			ty += LINE;
		}
		return y + height + GAP;
	}

	/**
	 * Кнопка в левом верхнем углу: в уровне — меню уровня (клавиша {@link LevelModClient#DETAILS}, окно {@link MissionDetailsScreen}: описание, стрелка, список задач),
	 * вне уровня — меню уровней (клавиша {@link LevelModClient#OPEN}). У HUD нет курсора, поэтому открывает её клавиша, показанная в рамке. Возвращает нижний край.
	 */
	private static int chip(GuiGraphicsExtractor g, Font font, List<LevelList.LevelInfo> running) {
		Component key, label;
		if (running.isEmpty()) {
			key = LevelModClient.OPEN.getTranslatedKeyMessage();
			label = Component.translatable(ClientLevels.testing ? "gui.levelmod.hud.test_stop" : "gui.levelmod.hud.levels");
		} else {
			key = LevelModClient.DETAILS.getTranslatedKeyMessage();
			LevelList.LevelInfo level = running.get(0);
			int current = currentMission(level.tasks());
			label = current < 0 ? Component.translatable("gui.levelmod.hud.level") : Component.translatable("gui.levelmod.hud.chip", current + 1, level.missions().size());
		}
		int keyW = font.width(key) + 8, labelW = font.width(label);
		int x = MARGIN, y = MARGIN, h = 15;
		rounded(g, x, y, x + keyW + labelW + 14, y + h, BUTTON);
		rounded(g, x + 2, y + 2, x + 2 + keyW, y + h - 2, KEY);
		g.text(font, key, x + 6, y + 4, 0xFFFFFFFF);
		g.text(font, label, x + keyW + 8, y + 4, 0xFFD0D6DE);
		// рядом с кнопкой уровней или уровня, пока у стрелки есть цель (в уровне и в проверке): клавиша прячет её и возвращает
		if (GuideArrow.available()) {
			Component arrowKey = LevelModClient.TOGGLE_ARROW.getTranslatedKeyMessage(), arrowLabel = Component.translatable(GuideArrow.hidden ? "gui.levelmod.hud.arrow.show" : "gui.levelmod.hud.arrow.hide");
			int akW = font.width(arrowKey) + 8, alW = font.width(arrowLabel), ax = x + keyW + labelW + 14 + 4;
			rounded(g, ax, y, ax + akW + alW + 14, y + h, BUTTON);
			rounded(g, ax + 2, y + 2, ax + 2 + akW, y + h - 2, KEY);
			g.text(font, arrowKey, ax + 6, y + 4, 0xFFFFFFFF);
			g.text(font, arrowLabel, ax + akW + 8, y + 4, 0xFFD0D6DE);
		}
		return y + h;
	}
}
