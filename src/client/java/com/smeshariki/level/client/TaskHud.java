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
		if (mc.player == null) return;
		int y = MARGIN;
		if (!ClientLevels.testTasks.isEmpty()) y = panel(g, mc.font, y, Component.translatable("gui.levelmod.hud.test", ""), null, -1, 0, ClientLevels.testTasks);
		List<LevelList.LevelInfo> running = running(mc);
		for (LevelList.LevelInfo level : running) y = panel(g, mc.font, y, level.title(), level.missions().isEmpty() ? null : level.missions(), currentMission(level.tasks()), level.missions().size(), level.tasks());
		if (!running.isEmpty()) button(g, mc.font, y);
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

	/** Кнопка «Подробнее»: клавиша в рамке и подпись. */
	private static void button(GuiGraphicsExtractor g, Font font, int y) {
		Component key = LevelModClient.DETAILS.getTranslatedKeyMessage();
		Component label = Component.translatable("gui.levelmod.hud.details");
		int keyW = font.width(key) + 8, labelW = font.width(label);
		int x = MARGIN, h = 15;
		rounded(g, x, y, x + keyW + labelW + 14, y + h, BUTTON);
		rounded(g, x + 2, y + 2, x + 2 + keyW, y + h - 2, KEY);
		g.text(font, key, x + 6, y + 4, 0xFFFFFFFF);
		g.text(font, label, x + keyW + 8, y + 4, 0xFFD0D6DE);
	}
}
