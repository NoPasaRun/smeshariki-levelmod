package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.net.LevelTestTask;
import com.smeshariki.ui.SelectScreen;
import com.smeshariki.ui.CardList;
import com.smeshariki.ui.Ui;
import com.smeshariki.ui.UiScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;

/**
 * Меню миссий и задач, устроено как окно уровней: слева миссии с их задачами, справа выбранное. Логику длинных уровней удобнее писать в файле;
 * это меню нужно прежде всего для того, что делается в мире: границы и проверка.
 */
final class TaskEditorScreen extends UiScreen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 32, ROW = 24, H = 20, LINE = 11;
	private static final int WHITE = Ui.TEXT, GRAY = Ui.HINT, RED = Ui.BAD;

	private final LevelDraft level;
	private Component problem;
	private final CardList cards = new CardList(CARD_H, 2);
	private boolean testing;
	/** Первое нажатие «Удалить» только спрашивает: миссия уходит со всеми задачами. */
	private boolean confirmDelete;

	TaskEditorScreen(LevelDraft level) {
		super(Component.translatable("gui.levelmod.tasks.menu", level.title.isBlank() ? "…" : level.title));
		this.level = level;
	}

	/** Строка списка: заголовок миссии или её задача. */
	private record Row(LevelDraft.MissionDraft mission, TaskDraft task) {
	}

	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		for (LevelDraft.MissionDraft mission : level.missions) {
			rows.add(new Row(mission, null));
			for (TaskDraft task : mission.tasks) rows.add(new Row(mission, task));
		}
		return rows;
	}

	private TaskDraft task() {
		return ClientLevels.draftTask;
	}

	private LevelDraft.MissionDraft mission() {
		return ClientLevels.draftMission;
	}

	private void select(LevelDraft.MissionDraft mission, TaskDraft task) {
		ClientLevels.draftMission = mission;
		ClientLevels.draftTask = task;
		confirmDelete = false;
		problem = null;
		rebuildWidgets();
	}

	private int listTop() {
		return 24;
	}

	private int listBottom() {
		return height - MARGIN - H - 4;
	}

	@Override
	protected void init() {
		cards.layout(MARGIN, listTop(), LIST_W, listBottom());
		testing = ClientLevels.testing;
		if (mission() == null || !level.missions.contains(mission())) ClientLevels.draftMission = level.missions.isEmpty() ? null : level.missions.getFirst();
		int bottom = height - MARGIN - H;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.mission.add"), b -> select(level.addMission(), null)).bounds(MARGIN, bottom, LIST_W / 2 - 1, H).build());
		Button addTask = addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.task.add"), b -> select(mission(), level.addTask(mission()))).bounds(MARGIN + LIST_W / 2 + 1, bottom, LIST_W / 2 - 1, H).build());
		addTask.active = mission() != null;
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN;
		int third = (w - 4) / 3;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> {
			ClientLevels.inTaskMenu = false;
			LevelEditorScreen.reopen(minecraft);
		}).bounds(x + 2 * third + 4, bottom, third, H).build());
		TaskDraft task = task();
		LevelDraft.MissionDraft selected = mission();
		if (selected == null) return;
		addRenderableWidget(Button.builder(Component.translatable(testing ? "gui.levelmod.task.test.stop" : "gui.levelmod.task.test"), b -> test(selected, task)).bounds(x, bottom, third, H)
				.tooltip(Tooltip.create(Component.translatable(testing ? "gui.levelmod.task.test.stop" : "gui.levelmod.task.test.hint"))).build());
		addRenderableWidget(Button.builder(Component.translatable(confirmDelete ? "gui.levelmod.delete.confirm" : "gui.levelmod.task.delete"), b -> {
			if (!confirmDelete) {
				confirmDelete = true;
				rebuildWidgets();
				return;
			}
			confirmDelete = false;
			if (task != null) level.removeTask(task);
			else level.removeMission(selected);
			select(task != null ? selected : null, null);
		}).bounds(x + third + 2, bottom, third, H).build());
		int half = (w - 2) / 2;
		int y = listTop();
		if (task == null) {
			add(x, y, w, "gui.levelmod.mission.title", selected.title, 80, v -> selected.title = v);
			y += ROW;
			add(x, y, w, "gui.levelmod.mission.description", selected.description, 1000, v -> selected.description = v);
			y += ROW;
			button(x, y, half, Component.translatable("gui.levelmod.mission.up"), () -> level.moveMission(selected, -1));
			button(x + half + 2, y, half, Component.translatable("gui.levelmod.mission.down"), () -> level.moveMission(selected, 1));
			y += ROW;
			// чекпоинты: первый — начало миссии, последний — конец; сброс возвращает на самый дальний достигнутый
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.checkpoints.open", selected.checkpoints.size()), b -> minecraft.gui.setScreen(new CheckpointsScreen(level, selected)))
					.bounds(x, y, half, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.checkpoints.hint"))).build());
			// события миссии видны только в ней: её задачи, чекпоинты, сброс; механики с параметрами
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.rules.mission_open", selected.rules.size()), b -> minecraft.gui.setScreen(new MechanicRulesScreen(level, selected)))
					.bounds(x + half + 2, y, half, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.rules.mission_tip"))).build());
			y += ROW;
			// всё в области миссии возвращается при сбросе: подвижное к её началу, блоки к версии мира
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.mission.area", selected.area.isEmpty() ? Component.translatable("gui.levelmod.editor.none") : Component.literal(selected.area)), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, true, name -> {
				selected.area = name;
				selected.areaRaw = null;
			}))).bounds(x, y, w, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.mission.area.hint"))).build());
			y += ROW;
			// ожидание после сброса: игрок стоит, камеру крутит, на экране отсчёт; стрелы и волны лавы начинаются после него
			add(x, y, half, "gui.levelmod.mission.reset_wait", selected.resetWait == 0 ? "" : Integer.toString(selected.resetWait), 2, v -> {
				try {
					selected.resetWait = v.isBlank() ? 0 : Math.clamp(Integer.parseInt(v.trim()), 0, 60);
				} catch (NumberFormatException e) {
					selected.resetWait = 0;
				}
			});
			// куда бежать: свет подсказывает направление (чем дальше от цели, тем темнее)
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.mission.guide", selected.guide.isEmpty() ? Component.translatable("gui.levelmod.editor.none") : Component.literal(selected.guide)), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, (Boolean) null, name -> {
				selected.guide = name;
				selected.guideRaw = null;
			}))).bounds(x + half + 2, y, half, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.mission.guide.hint"))).build());
			return;
		}
		add(x, y, w, "gui.levelmod.task.title", task.title, 80, v -> task.title = v);
		y += ROW;
		select(x, y, half, Component.translatable("gui.levelmod.task.type", Component.translatable("gui.levelmod.task.type." + task.type)),
				() -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.type"), TaskDraft.TYPES, t -> Component.translatable("gui.levelmod.task.type." + t), task.type, t -> task.type = t));
		// назначить можно только персонажей этого уровня
		List<Identifier> roles = new ArrayList<>();
		roles.add(null);
		roles.addAll(level.boundCharacters());
		if (!roles.contains(task.role)) roles.add(task.role);
		java.util.function.Function<Identifier, Component> roleName = r -> r == null ? Component.translatable("gui.levelmod.task.role.any") : BuiltInRegistries.ENTITY_TYPE.getValue(r).getDescription();
		select(x + half + 2, y, half, Component.translatable("gui.levelmod.task.role", roleName.apply(task.role)),
				() -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.role"), roles, roleName, task.role, r -> task.role = r));
		y += ROW;
		switch (task.type) {
			case "reach" -> {
				pick(x, y, w - 72, "gui.levelmod.task.pos", task.point, false, name -> task.point = name);
				add(x + w - 70, y, 70, "gui.levelmod.task.radius", task.radius, 8, v -> task.radius = v);
			}
			case "reach_area" -> pick(x, y, w, "gui.levelmod.task.destination", task.target, true, name -> task.target = name);
			case "get_item" -> {
				add(x, y, w - 62, "gui.levelmod.task.item", task.item, 80, v -> task.item = v);
				add(x + w - 60, y, 60, "gui.levelmod.task.count", task.count, 8, v -> task.count = v);
			}
			case "talk" -> {
				// говорить можно с любым, не только с персонажами уровня
				List<Identifier> characters = new ArrayList<>();
				for (var type : Possession.choices()) characters.add(EntityType.getKey(type));
				select(x, y, half, Component.translatable("gui.levelmod.task.character", BuiltInRegistries.ENTITY_TYPE.getValue(task.with).getDescription()),
						() -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.character"), characters, c -> BuiltInRegistries.ENTITY_TYPE.getValue(c).getDescription(), task.with, c -> task.with = c));
				add(x + half + 2, y, half, "gui.levelmod.task.text", task.text, 200, v -> task.text = v);
			}
			case "wait" -> add(x, y, 100, "gui.levelmod.task.seconds", task.seconds, 8, v -> task.seconds = v);
			case "condition" -> {
				addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.task.conditions", task.conditions.size()), b -> minecraft.gui.setScreen(new ConditionsScreen(Component.literal(task.title.isBlank() ? task.id : task.title), task.conditions, () -> LevelEditorScreen.reopen(minecraft)))).bounds(x, y, half, H).build());
				addRenderableWidget(Button.builder(Component.translatable(task.together ? "gui.levelmod.task.together" : "gui.levelmod.task.in_turn"), b -> {
					task.together = !task.together;
					rebuildWidgets();
				}).bounds(x + half + 2, y, half, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.task.together.hint"))).build());
			}
			default -> {
				add(x, y, w - 62, "gui.levelmod.task.block", task.block, 80, v -> task.block = v);
				add(x + w - 60, y, 60, "gui.levelmod.task.count", task.count, 8, v -> task.count = v);
			}
		}
		y += ROW;
		List<LevelEvent> options = startOptions(task);
		Component startLabel = Component.translatable("gui.levelmod.task.start", startName(task, task.start));
		if (!task.after.isEmpty()) startLabel = startLabel.copy().append(Component.translatable("gui.levelmod.task.after", String.join(", ", task.after.stream().map(id -> {
			TaskDraft t = level.task(id);
			return t == null || t.title.isBlank() ? id : t.title;
		}).toList())));
		select(x, y, w, startLabel, () -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.start"), options, e -> startName(task, e), task.start, e -> task.start = e));
		y += ROW;
		add(x, y, 70, "gui.levelmod.task.delay", task.delay, 6, v -> task.delay = v);
		select(x + 72, y, w - 72, Component.translatable("gui.levelmod.task.outro", LevelEditorScreen.cutsceneName(task.outro)),
				() -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.cutscene"), LevelEditorScreen.cutsceneOptions(), LevelEditorScreen::cutsceneName, task.outro, c -> task.outro = c));
		y += ROW;
		pick(x, y, half, "gui.levelmod.task.boundary", task.area, true, name -> task.area = name);
		addRenderableWidget(Button.builder(Component.translatable(task.optional ? "gui.levelmod.task.optional" : "gui.levelmod.task.required"), b -> {
			task.optional = !task.optional;
			rebuildWidgets();
		}).bounds(x + half + 2, y, half, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.task.optional.hint"))).build());
	}

	/** Кнопка выбора локации: на месте задачи хранится только её имя. */
	private void pick(int x, int y, int w, String label, String current, boolean areas, java.util.function.Consumer<String> apply) {
		addRenderableWidget(Button.builder(Component.translatable(label, current.isEmpty() ? Component.translatable("gui.levelmod.editor.none") : Component.literal(current)), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, areas, apply))).bounds(x, y, w, H).build());
	}

	/** Проверяется выбранное: задача или миссия целиком. */
	private void test(LevelDraft.MissionDraft mission, TaskDraft task) {
		if (testing) {
			ClientPlayNetworking.send(new LevelTestTask(Optional.empty()));
			return;
		}
		try {
			JsonObject json;
			if (task != null) {
				json = new JsonObject();
				json.addProperty("id", task.id);
				json.addProperty("title", task.title);
				JsonArray tasks = new JsonArray();
				tasks.add(task.toJson());
				json.add("tasks", tasks);
			} else {
				json = level.missionJson(mission);
			}
			ClientPlayNetworking.send(new LevelTestTask(Optional.of(json.toString()), Optional.ofNullable(level.id)));
			minecraft.gui.setScreen(null);
		} catch (TaskDraft.Invalid e) {
			problem = e.problem;
		}
	}

	private void add(int x, int y, int w, String hint, String value, int max, java.util.function.Consumer<String> target) {
		EditBox box = LevelEditorScreen.field(this, font, x, y, w, hint, value, max, target);
		addRenderableWidget(box);
	}

	/** Кнопка, открывающая выбор из списка ({@link SelectScreen}). */
	private void select(int x, int y, int w, Component label, Runnable open) {
		addRenderableWidget(Button.builder(label, b -> {
			problem = null;
			open.run();
		}).bounds(x, y, w, H).build());
	}

	private void button(int x, int y, int w, Component label, Runnable action) {
		addRenderableWidget(Button.builder(label, b -> {
			action.run();
			problem = null;
			rebuildWidgets();
		}).bounds(x, y, w, H).build());
	}

	/** Что можно выбрать началом задачи: начало миссии (по умолчанию, порядок любой), задачи этой миссии, кат-сцены. */
	private List<LevelEvent> startOptions(TaskDraft task) {
		List<LevelEvent> options = new ArrayList<>();
		options.add(null);
		List<TaskDraft> tasks = level.missionOf(task).tasks;
		int index = tasks.indexOf(task);
		if (index > 0) options.add(LevelEvent.taskCompleted(tasks.get(index - 1).id));
		for (TaskDraft other : tasks) {
			if (other == task) continue;
			LevelEvent completed = LevelEvent.taskCompleted(other.id);
			if (!options.contains(completed)) options.add(completed);
			options.add(LevelEvent.taskStarted(other.id));
		}
		for (Identifier cutscene : ClientLevels.cutscenes) {
			options.add(LevelEvent.cutsceneFinished(cutscene));
			options.add(LevelEvent.cutsceneStarted(cutscene));
		}
		if (!options.contains(task.start)) options.add(task.start);
		return options;
	}

	private Component startName(TaskDraft task, LevelEvent event) {
		if (event == null) return Component.translatable("gui.levelmod.start.mission");
		List<TaskDraft> tasks = level.missionOf(task).tasks;
		int index = tasks.indexOf(task);
		String previous = index > 0 ? tasks.get(index - 1).id : null;
		TaskDraft other = level.task(event.subject());
		String name = other == null ? event.subject() : other.title.isBlank() ? other.id : other.title;
		return switch (event.kind()) {
			case LEVEL_STARTED -> Component.translatable("gui.levelmod.start.level_started");
			case TASK_COMPLETED -> event.subject().equals(previous) ? Component.translatable("gui.levelmod.start.previous", name) : Component.translatable("gui.levelmod.start.task_completed", name);
			case TASK_STARTED -> Component.translatable("gui.levelmod.start.task_started", name);
			case CUTSCENE_FINISHED -> Component.translatable("gui.levelmod.start.cutscene_finished", name);
			case CUTSCENE_STARTED -> Component.translatable("gui.levelmod.start.cutscene_started", name);
			default -> Component.literal(event.toString());
		};
	}

	@Override
	public void tick() {
		super.tick();
		if (testing != ClientLevels.testing && !isEditing()) rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		List<Row> rows = rows();
		int row = cards.rowAt(event.x(), event.y(), rows.size());
		if (event.button() == 0 && row >= 0) {
			select(rows.get(row).mission(), rows.get(row).task());
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		cards.wheel(x, scrollY, rows().size(), LINE * 2);
		return true;
	}

	private FormattedCharSequence firstLine(Component text, int w) {
		return Ui.firstLine(font, text, w);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		drawList(g, mouseX, mouseY);
		int x = MARGIN + LIST_W + MARGIN;
		TaskDraft task = task();
		if (mission() == null) {
			g.text(font, Component.translatable("gui.levelmod.task.empty"), x, listTop() + 4, GRAY);
			return;
		}
		if (problem != null) g.text(font, firstLine(problem, width - x - MARGIN), x, height - MARGIN - H - LINE - 2, RED);
	}

	private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<Row> rows = rows();
		cards.draw(g, rows.size(), mouseX, mouseY, (i, y, hover) -> {
			TaskDraft task = rows.get(i).task();
			LevelDraft.MissionDraft mission = rows.get(i).mission();
			boolean chosen = mission == mission() && task == task();
			cards.background(g, MARGIN + (task == null ? 0 : 8), y, chosen, hover);
			if (task == null) {
				g.text(font, firstLine(Component.translatable("gui.levelmod.mission.number", level.missions.indexOf(mission) + 1, mission.title.isBlank() ? mission.id : mission.title), LIST_W - 8), MARGIN + 4, y + 11, 0xFF80C0FF);
				return;
			}
			g.text(font, firstLine(Component.literal(task.title.isBlank() ? task.id : task.title), LIST_W - 16), MARGIN + 12, y + 5, WHITE);
			g.text(font, firstLine(Component.translatable("gui.levelmod.task.type." + task.type), LIST_W - 16), MARGIN + 12, y + 17, task.area.isEmpty() ? GRAY : Ui.ACCENT);
		});
	}
}
