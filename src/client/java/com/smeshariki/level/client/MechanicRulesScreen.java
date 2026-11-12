package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.SelectScreen;
import com.smeshariki.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Правила механик ({@link LevelDraft.MechanicRule}): строка — «когда событие → действие механики с параметрами». Какие механики и события есть,
 * окно берёт из {@link Mechanics} (их регистрирует GameplayMod), названия — из языковых ключей {@code mechanic.<пр>.<имя>} и
 * {@code event.<пр>.<имя>}; параметры — строка {@code ключ=значение, ...}, их смысл знает только механика.
 */
final class MechanicRulesScreen extends ListScreen {
	private static final int W = 660, ROW = 24, H = 20, TOP = 50;
	private static final int WHEN_BASE = 150, SUBJECT_BASE = 60, ACTION_BASE = 100, DELAY_BASE = 40, UNLESS_BASE = 110, REPEAT_W = 20, DELETE_W = 20;
	/** Ширины колонок в текущем окне: в узком окне все сжимаются поровну, поле параметров сохраняет хотя бы {@link #ARGS_MIN}. */
	private static final int ARGS_MIN = 40;
	private int whenW, subjectW, actionW, delayW, unlessW;

	private final LevelDraft draft;

	MechanicRulesScreen(LevelDraft draft) {
		super(Component.translatable("gui.levelmod.rules"));
		this.draft = draft;
	}

	/** Левые края колонок для заголовков над строками. */
	private int colWhen, colSubject, colAction, colArgs, colDelay, colUnless;

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	private static List<Identifier> actions() {
		List<Identifier> all = new ArrayList<>(Mechanics.actions().keySet());
		all.addAll(Mechanics.toggles().keySet());
		return all;
	}

	@Override
	protected void init() {
		int w = listWidth(W), x = centered(w);
		int fixed = WHEN_BASE + SUBJECT_BASE + ACTION_BASE + DELAY_BASE + UNLESS_BASE;
		double k = Math.min(1.0, (w - REPEAT_W - DELETE_W - 14 - ARGS_MIN) / (double) fixed);
		int argsW;
		// в узком окне колонки сжимаются, пока на параметры остаётся не меньше ARGS_MIN: иначе «1×» налезает на «✕»
		for (int attempt = 0; ; attempt++) {
			whenW = (int) (WHEN_BASE * k);
			subjectW = (int) (SUBJECT_BASE * k);
			actionW = (int) (ACTION_BASE * k);
			delayW = Math.max(28, (int) (DELAY_BASE * k));
			unlessW = (int) (UNLESS_BASE * k);
			argsW = w - whenW - subjectW - actionW - delayW - unlessW - REPEAT_W - DELETE_W - 14;
			if (argsW >= ARGS_MIN || attempt >= 12) break;
			k *= 0.9;
		}
		argsW = Math.max(ARGS_MIN, argsW);
		int visible = visible();
		bar.layout(x + w + 3, TOP, visible * ROW - 2, draft.rules.size(), visible);
		colWhen = x;
		colSubject = x + whenW + 2;
		colAction = x + whenW + subjectW + 4;
		colArgs = colAction + actionW + 2;
		colDelay = colAction + actionW + argsW + 4;
		colUnless = colAction + actionW + argsW + delayW + 6;
		for (int i = bar.scroll; i < Math.min(draft.rules.size(), bar.scroll + visible); i++) {
			LevelDraft.MechanicRule rule = draft.rules.get(i);
			int y = TOP + (i - bar.scroll) * ROW;
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.rules.when", eventName(draft, rule.when)), b -> {
				List<LevelEvent> all = eventOptions(draft);
				LevelEvent current = rule.when.kind() == LevelEvent.Kind.EXTERNAL ? LevelEvent.external(externalName(rule.when)) : rule.when;
				if (!all.contains(current)) all.add(current);
				SelectScreen.open(this, Component.translatable("gui.levelmod.select.event"), all, e -> eventName(draft, e), current, e -> {
					rule.when = e;
					// событие механики случается много раз: правило на него повторяемое
					if (e.kind() == LevelEvent.Kind.EXTERNAL) rule.repeat = true;
				});
			}).bounds(x, y, whenW, H).tooltip(Tooltip.create(Component.translatable(rule.repeat ? "gui.levelmod.rules.repeat" : "gui.levelmod.rules.once"))).build());
			// уточнение события механики: группа и т. п.
			if (rule.when.kind() == LevelEvent.Kind.EXTERNAL) {
				String name = externalName(rule.when), subject = rule.when.subject().length() > name.length() ? rule.when.subject().substring(name.length() + 1) : "";
				String hint = Mechanics.eventNames().getOrDefault(name, "");
				EditBox box = new EditBox(font, x + whenW + 2, y, subjectW, H, Component.literal(hint));
				box.setMaxLength(64);
				box.setHint(Component.literal(hint));
				box.setValue(subject);
				box.setResponder(v -> rule.when = LevelEvent.external(v.isBlank() ? name : name + ":" + v.trim()));
				if (!hint.isEmpty()) box.setTooltip(Tooltip.create(Component.literal(hint)));
				addRenderableWidget(box);
			}
			int ax = x + whenW + subjectW + 4;
			addRenderableWidget(Button.builder(rule.action == null ? Component.translatable("gui.levelmod.rules.no_action") : mechanicName(rule.action),
					b -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.action"), actions(),
							id -> mechanicName(id).copy().append(Component.literal("  " + id).withStyle(net.minecraft.ChatFormatting.GRAY)), rule.action, id -> rule.action = id)).bounds(ax, y, actionW, H).tooltip(Tooltip.create(rule.action == null ? Component.empty() : Component.literal(rule.action.toString()))).build());
			addRenderableWidget(LevelEditorScreen.field(this, font, ax + actionW + 2, y, argsW, "gui.levelmod.rules.args", rule.args, 256, v -> rule.args = v));
			addRenderableWidget(LevelEditorScreen.field(this, font, ax + actionW + argsW + 4, y, delayW, "gui.levelmod.rules.delay", rule.delay, 8, v -> rule.delay = v));
			int ux = ax + actionW + argsW + delayW + 6;
			// «отменить, если случится»: отложенное действие не выполнится, когда до срока произойдёт это событие (например, сброс миссии)
			addRenderableWidget(Button.builder(rule.unless == null ? Component.translatable("gui.levelmod.rules.unless_none") : Component.translatable("gui.levelmod.rules.unless", eventName(draft, rule.unless)), b -> {
				List<java.util.Optional<LevelEvent>> all = new ArrayList<>();
				all.add(java.util.Optional.empty());
				for (LevelEvent e : eventOptions(draft)) all.add(java.util.Optional.of(e));
				java.util.Optional<LevelEvent> current = java.util.Optional.ofNullable(rule.unless);
				if (!all.contains(current)) all.add(current);
				SelectScreen.open(this, Component.translatable("gui.levelmod.rules.unless_title"), all,
						o -> o.map(e -> eventName(draft, e)).orElse(Component.translatable("gui.levelmod.rules.unless_none")), current, o -> rule.unless = o.orElse(null));
			}).bounds(ux, y, unlessW, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.rules.unless_tip"))).build());
			addRenderableWidget(Button.builder(Component.literal(rule.repeat ? "↻" : "1×"), b -> {
				rule.repeat = !rule.repeat;
				rebuildWidgets();
			}).bounds(ux + unlessW + 2, y, REPEAT_W, H).tooltip(Tooltip.create(Component.translatable(rule.repeat ? "gui.levelmod.rules.repeat" : "gui.levelmod.rules.once"))).build());
			addDelete(x + w - DELETE_W, y, () -> {
				draft.rules.remove(rule);
				rebuildWidgets();
			});
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.rules.add"), b -> {
			LevelDraft.MechanicRule rule = new LevelDraft.MechanicRule();
			List<Identifier> all = actions();
			if (!all.isEmpty()) rule.action = all.getFirst();
			draft.rules.add(rule);
			bar.scroll = Math.max(0, draft.rules.size() - visible());
			rebuildWidgets();
		}).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> LevelEditorScreen.reopen(minecraft)).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	/** Имя события механики без уточнения: самое длинное известное имя, с которого оно начинается. */
	static String externalName(LevelEvent event) {
		String text = event.subject(), best = null;
		for (String name : Mechanics.eventNames().keySet()) {
			if ((text.equals(name) || text.startsWith(name + ":")) && (best == null || name.length() > best.length())) best = name;
		}
		if (best != null) return best;
		int first = text.indexOf(':'), second = first < 0 ? -1 : text.indexOf(':', first + 1);
		return second < 0 ? text : text.substring(0, second);
	}

	/** События уровня для выбора: начало уровня, начало и конец миссий, начало, конец и сброс задач, кат-сцены, наблюдатели и события механик. */
	static List<LevelEvent> eventOptions(LevelDraft draft) {
		List<LevelEvent> options = new ArrayList<>();
		options.add(LevelEvent.LEVEL_STARTED);
		for (LevelDraft.MissionDraft mission : draft.missions) {
			options.add(LevelEvent.missionStarted(mission.id));
			for (TaskDraft task : mission.tasks) {
				options.add(LevelEvent.taskStarted(task.id));
				options.add(LevelEvent.taskCompleted(task.id));
				options.add(LevelEvent.taskReset(task.id));
			}
			options.add(LevelEvent.missionCompleted(mission.id));
			options.add(LevelEvent.missionReset(mission.id));
			options.add(LevelEvent.missionResumed(mission.id));
		}
		for (Identifier cutscene : ClientLevels.cutscenes) {
			options.add(LevelEvent.cutsceneStarted(cutscene));
			options.add(LevelEvent.cutsceneFinished(cutscene));
		}
		for (LevelDraft.WatchDraft watch : draft.watches) if (!watch.id.isBlank()) options.add(LevelEvent.custom(watch.id.trim()));
		for (String name : Mechanics.eventNames().keySet()) options.add(LevelEvent.external(name));
		return options;
	}

	private static Component mechanicName(Identifier id) {
		return Component.translatableWithFallback("mechanic." + id.getNamespace() + "." + id.getPath(), id.getPath());
	}

	/** Подпись события: имена миссий и задач из черновика. */
	static Component eventName(LevelDraft draft, LevelEvent event) {
		if (event == null) return Component.translatable("gui.levelmod.editor.none");
		String subject = event.subject();
		TaskDraft task = draft.task(subject);
		String taskName = task == null ? subject : task.title.isBlank() ? task.id : task.title;
		return switch (event.kind()) {
			case LEVEL_STARTED -> Component.translatable("gui.levelmod.start.level_started");
			case MISSION_STARTED -> {
				LevelDraft.MissionDraft mission = draft.missions.stream().filter(m -> m.id.equals(subject)).findFirst().orElse(null);
				yield Component.translatable("gui.levelmod.start.mission_started", mission == null || mission.title.isBlank() ? subject : mission.title);
			}
			case MISSION_COMPLETED -> {
				LevelDraft.MissionDraft mission = draft.missions.stream().filter(m -> m.id.equals(subject)).findFirst().orElse(null);
				yield Component.translatable("gui.levelmod.start.mission_completed", mission == null || mission.title.isBlank() ? subject : mission.title);
			}
			case TASK_RESET -> Component.translatable("gui.levelmod.start.task_reset", taskName);
			case MISSION_RESET -> {
				LevelDraft.MissionDraft mission = draft.missions.stream().filter(m -> m.id.equals(subject)).findFirst().orElse(null);
				yield Component.translatable("gui.levelmod.start.mission_reset", mission == null || mission.title.isBlank() ? subject : mission.title);
			}
			case MISSION_RESUMED -> {
				LevelDraft.MissionDraft mission = draft.missions.stream().filter(m -> m.id.equals(subject)).findFirst().orElse(null);
				yield Component.translatable("gui.levelmod.start.mission_resumed", mission == null || mission.title.isBlank() ? subject : mission.title);
			}
			case TASK_FAILED -> Component.translatable("gui.levelmod.start.task_failed", taskName);
			case LEVEL_COMPLETED -> Component.translatable("gui.levelmod.start.level_completed");
			case TASK_STARTED -> Component.translatable("gui.levelmod.start.task_started", taskName);
			case TASK_COMPLETED -> Component.translatable("gui.levelmod.start.task_completed", taskName);
			case CUTSCENE_STARTED -> Component.translatable("gui.levelmod.start.cutscene_started", subject);
			case CUTSCENE_FINISHED -> Component.translatable("gui.levelmod.start.cutscene_finished", subject);
			case CUSTOM -> Component.translatable("gui.levelmod.start.custom", subject);
			case EXTERNAL -> {
				Identifier id = Identifier.tryParse(externalName(event));
				yield id == null ? Component.literal(subject) : Component.translatableWithFallback("event." + id.getNamespace() + "." + id.getPath(), id.getPath());
			}
			default -> Component.literal(event.toString());
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, Component.translatable("gui.levelmod.rules.hint"));
		if (draft.rules.isEmpty()) {
			drawNote(g, Component.translatable("gui.levelmod.rules.empty"), TOP + 6);
			return;
		}
		// заголовки колонок: когда поле заполнено, подсказка в нём уже не видна
		int unlessEnd = colUnless + unlessW;
		column(g, "when", colWhen, colSubject);
		column(g, "subject", colSubject, colAction);
		column(g, "action", colAction, colArgs);
		column(g, "args", colArgs, colDelay);
		column(g, "delay", colDelay, colUnless);
		column(g, "unless", colUnless, unlessEnd);
	}

	private void column(GuiGraphicsExtractor g, String key, int x, int next) {
		String text = font.plainSubstrByWidth(Component.translatable("gui.levelmod.rules.col." + key).getString(), Math.max(10, next - x - 2));
		g.text(font, text, x + 2, TOP - 9, Ui.DIM);
	}
}
