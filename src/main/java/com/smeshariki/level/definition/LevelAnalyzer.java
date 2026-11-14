package com.smeshariki.level.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.task.ConditionEntry;
import com.smeshariki.level.task.ConditionTask;
import com.smeshariki.level.task.MissionEntry;
import com.smeshariki.level.task.TaskEntry;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Анализ уровня без запуска: когда случится каждое событие, если играть без ошибок. Время и позиция расходятся от
 * {@code level_started} по триггерам (время триггера — позднейшее из его условий плюс задержка) и по задачам (начало задачи плюс её
 * {@code Estimate}); из нескольких путей к одному событию берётся ранний, потому что первый и сработает. Уровень заканчивается с
 * последней миссией, поэтому ожидаемая длительность — это критический путь.
 * <p>
 * Заодно находит ошибки: событие, до которого не дойти, означает мёртвый триггер или задачу, которая никогда не начнётся (в том
 * числе циклы в зависимостях), а такой уровень нельзя завершить.
 */
public final class LevelAnalyzer {
	private final Map<LevelEvent, Long> times = new HashMap<>();
	private final Map<LevelEvent, Vec3> positions = new HashMap<>();
	private boolean changed;

	/**
	 * @param expectedTicks ожидаемая длительность; при ошибках смысла не имеет
	 * @param problems что не так с уровнем; непустой список означает, что уровень не загружается
	 */
	public record Result(long expectedTicks, List<String> problems) {
	}

	private LevelAnalyzer() {
	}

	/**
	 * @param cutscenes длительность кат-сцены по идентификатору, {@code null} — такой нет
	 * @param players для скольких игроков считать (общую работу команда делит между собой)
	 */
	public static Result analyze(LevelDefinition level, Function<Identifier, Integer> cutscenes, int players) {
		return new LevelAnalyzer().run(level, cutscenes, players);
	}

	private Result run(LevelDefinition level, Function<Identifier, Integer> cutscenes, int players) {
		List<String> problems = new ArrayList<>();
		if (level.missions().isEmpty()) problems.add("нет миссий");
		Set<String> missionIds = new HashSet<>();
		for (MissionEntry mission : level.missions()) {
			if (mission.tasks().isEmpty()) problems.add("в миссии \"" + mission.id() + "\" нет задач");
			if (!missionIds.add(mission.id())) problems.add("миссия \"" + mission.id() + "\" объявлена дважды");
		}
		if (level.minPlayers() > level.maxPlayers()) problems.add("min_players больше max_players");
		if (level.kind() == LevelKind.SIDE && level.startPos().isEmpty()) problems.add("у побочного уровня нет точки начала");
		// за другого персонажа уровень не пройти, так что и задача для него никогда не выполнится
		if (!level.characters().isEmpty()) {
			for (TaskEntry entry : level.tasks()) entry.character().filter(character -> !level.characters().contains(character)).ifPresent(character -> problems.add("задача \"" + entry.id() + "\" для персонажа не из этого уровня: " + character));
			for (Identifier character : level.characterStarts().keySet()) if (!level.characters().contains(character)) problems.add("старт для персонажа не из этого уровня: " + character);
		}
		Set<String> ids = new HashSet<>();
		for (TaskEntry entry : level.tasks()) if (!ids.add(entry.id())) problems.add("задача \"" + entry.id() + "\" объявлена дважды");
		// миссия без обязательных кончилась бы в тот же миг; обязательная после необязательной может не начаться никогда, и миссия встанет
		for (com.smeshariki.level.task.MissionEntry mission : level.missions()) {
			if (mission.tasks().stream().allMatch(TaskEntry::optional)) problems.add("в миссии \"" + mission.id() + "\" нет обязательных задач");
		}
		// у миссии не меньше двух чекпоинтов (начало и конец) для каждого персонажа, который её проходит
		for (MissionEntry mission : level.missions()) {
			Set<Identifier> roles = new HashSet<>(level.characters());
			for (TaskEntry entry : mission.tasks()) entry.character().ifPresent(roles::add);
			for (var checkpoint : mission.checkpoints()) checkpoint.character().ifPresent(roles::add);
			if (!level.characters().isEmpty()) for (var checkpoint : mission.checkpoints()) checkpoint.character().filter(c -> !level.characters().contains(c)).ifPresent(c -> problems.add("чекпоинт миссии \"" + mission.id() + "\" для персонажа не из этого уровня: " + c));
			if (roles.isEmpty()) {
				if (mission.checkpointsFor(null).size() < 2) problems.add("у миссии \"" + mission.id() + "\" меньше двух чекпоинтов (нужны начало и конец)");
				continue;
			}
			for (Identifier role : roles) {
				if (mission.checkpointsFor(role).size() < 2) problems.add("у миссии \"" + mission.id() + "\" для " + role + " меньше двух чекпоинтов (нужны начало и конец)");
			}
		}
		for (Trigger trigger : level.triggers()) {
			for (LevelEvent event : trigger.when()) {
				if (event.kind() != LevelEvent.Kind.TASK_COMPLETED) continue;
				TaskEntry waited = level.task(event.subject());
				if (waited == null || !waited.optional()) continue;
				for (LevelAction action : trigger.then()) {
					if (action instanceof LevelAction.StartTask(String task) && level.task(task) != null && !level.task(task).optional())
						problems.add("обязательная задача \"" + task + "\" начинается после необязательной \"" + waited.id() + "\"");
				}
			}
		}
		// действия при входе в чекпоинт проверяются как действия триггера; ячейки отката — известные виды блоков и сущностей
		List<Trigger> toCheck = new ArrayList<>(level.triggers());
		for (MissionEntry mission : level.missions()) {
			for (var checkpoint : mission.checkpoints()) {
				checkpoint.onEnter().ifPresent(action -> toCheck.add(new Trigger(List.of(LevelEvent.LEVEL_STARTED), 0, List.of(action))));
				for (var cell : checkpoint.resets()) {
					boolean known = cell.kind() == com.smeshariki.level.task.ResetCell.Kind.BLOCK ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(cell.type()) : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(cell.type());
					if (!known) problems.add("ячейка отката миссии \"" + mission.id() + "\": неизвестный " + (cell.kind() == com.smeshariki.level.task.ResetCell.Kind.BLOCK ? "блок " : "вид сущности ") + cell.type());
				}
			}
			scopeProblems(level, mission, ids, problems);
		}
		for (Trigger trigger : toCheck) {
			for (LevelAction action : trigger.then()) {
				switch (action) {
					case LevelAction.StartTask(String task) -> {
						if (!ids.contains(task)) problems.add("триггер запускает неизвестную задачу \"" + task + "\"");
					}
					case LevelAction.PlayCutscene(Identifier cutscene) -> {
						if (cutscenes.apply(cutscene) == null) problems.add("триггер запускает неизвестную кат-сцену " + cutscene);
					}
					case LevelAction.FailTask(String task) -> {
						if (!ids.contains(task)) problems.add("триггер сбрасывает неизвестную задачу \"" + task + "\"");
					}
					case LevelAction.Mechanic(Identifier id, var args) -> {
						Mechanics.Action mechanic = Mechanics.action(id);
						if (mechanic == null && Mechanics.toggle(id) == null) problems.add("неизвестная механика " + id);
						if (mechanic != null) for (String problem : mechanic.problems(new MechanicParams(args))) problems.add(id + ": " + problem);
					}
					case LevelAction.Order(OrderCall call) -> orderProblems("триггер", call, level, problems);
					case LevelAction.Emit ignored -> {
					}
					case LevelAction.Say ignored -> {
					}
				}
			}
		}
		for (com.smeshariki.level.task.MissionEntry mission : level.missions()) {
			for (OrderEntry order : mission.orders()) orderProblems("миссия \"" + mission.id() + "\"", order.call(), level, problems);
		}
		for (TaskEntry entry : level.tasks()) {
			if (entry.task() instanceof ConditionTask task) for (ConditionEntry c : task.conditions()) for (String p : c.problems()) problems.add("задача \"" + entry.id() + "\": " + p);
		}
		Set<String> watchIds = new HashSet<>();
		for (Watch watch : level.watches()) {
			if (!watchIds.add(watch.id())) problems.add("наблюдатель \"" + watch.id() + "\" объявлен дважды");
			for (ConditionEntry c : watch.conditions()) for (String p : c.problems()) problems.add("наблюдатель \"" + watch.id() + "\": " + p);
		}
		if (!problems.isEmpty()) return new Result(0, problems);

		reach(LevelEvent.LEVEL_STARTED, 0, level.startPos().orElse(null));
		reach(LevelEvent.missionStarted(level.missions().getFirst().id()), 0, level.startPos().orElse(null));
		// время только уменьшается и есть нижняя граница, так что проходы сходятся; предел — страховка от колебаний из-за позиций
		for (int pass = 0, limit = 2 * (level.tasks().size() + level.triggers().size()) + 2; pass < limit; pass++) {
			changed = false;
			for (Trigger trigger : level.triggers()) fire(trigger, cutscenes);
			for (TaskEntry entry : level.tasks()) finish(entry, players);
			advanceMissions(level);
			if (!changed) break;
		}

		for (TaskEntry entry : level.tasks()) if (!times.containsKey(LevelEvent.taskStarted(entry.id()))) problems.add("задача \"" + entry.id() + "\" никогда не начнётся");
		for (Trigger trigger : level.triggers())
			for (LevelEvent event : trigger.when())
				// выход, выход за границу, гибель и события механик (шар сел) не часть обычного прохождения: их триггеры живые, хотя времени у событий нет
				if (event.kind() != LevelEvent.Kind.LEVEL_ABANDONED && event.kind() != LevelEvent.Kind.TASK_FAILED && event.kind() != LevelEvent.Kind.TASK_RESET && event.kind() != LevelEvent.Kind.MISSION_RESET && event.kind() != LevelEvent.Kind.MISSION_RESUMED && event.kind() != LevelEvent.Kind.CHECKPOINT_REACHED && event.kind() != LevelEvent.Kind.CHECKPOINT_RESET && event.kind() != LevelEvent.Kind.CHECKPOINT_RESUMED && event.kind() != LevelEvent.Kind.EXTERNAL && !(event.kind() == LevelEvent.Kind.CUSTOM && watchIds.contains(event.subject())) && !times.containsKey(event)) problems.add("событие " + event + " никогда не случится, триггер не сработает");
		return new Result(times.getOrDefault(LevelEvent.LEVEL_COMPLETED, 0L), problems);
	}

	private void fire(Trigger trigger, Function<Identifier, Integer> cutscenes) {
		long time = -1;
		Vec3 pos = null;
		for (LevelEvent event : trigger.when()) {
			Long at = times.get(event);
			if (at == null) return;
			if (at > time) {
				time = at;
				pos = positions.get(event);
			}
		}
		time += trigger.delay();
		for (LevelAction action : trigger.then()) {
			switch (action) {
				case LevelAction.StartTask(String task) -> reach(LevelEvent.taskStarted(task), time, pos);
				case LevelAction.PlayCutscene(Identifier cutscene) -> {
					reach(LevelEvent.cutsceneStarted(cutscene), time, pos);
					reach(LevelEvent.cutsceneFinished(cutscene), time + cutscenes.apply(cutscene), pos);
				}
				case LevelAction.Emit(String event) -> reach(LevelEvent.custom(event), time, pos);
				// сколько идёт механика, знает она сама
				case LevelAction.Mechanic(Identifier id, var args) -> {
					Mechanics.Action mechanic = Mechanics.action(id);
					if (mechanic != null) for (var expected : mechanic.expectedEvents(new MechanicParams(args)).entrySet()) reach(LevelEvent.external(expected.getKey()), time + expected.getValue(), pos);
				}
				case LevelAction.FailTask ignored -> {
				}
				case LevelAction.Say ignored -> {
				}
				case LevelAction.Order ignored -> {
				}
			}
		}
	}

	/**
	 * События и триггеры миссии видны только в ней: она ссылается лишь на своё (начало, конец, сброс, свои задачи и чекпоинты), чужие миссии и уровень в её
	 * событиях не нужны, а действия трогают только её задачи. Кат-сцены, наблюдатели и события механик общие.
	 */
	private static void scopeProblems(LevelDefinition level, MissionEntry mission, Set<String> allTasks, List<String> problems) {
		Set<String> own = new HashSet<>();
		for (TaskEntry entry : mission.tasks()) own.add(entry.id());
		String where = "триггер миссии \"" + mission.id() + "\": ";
		for (Trigger trigger : mission.triggers()) {
			List<LevelEvent> events = new ArrayList<>(trigger.when());
			events.addAll(trigger.unless());
			for (LevelEvent event : events) {
				switch (event.kind()) {
					case LEVEL_STARTED, LEVEL_COMPLETED, LEVEL_ABANDONED -> problems.add(where + "событие уровня " + event + " в миссии не видно");
					case MISSION_STARTED, MISSION_COMPLETED, MISSION_RESET, MISSION_RESUMED -> {
						if (!event.subject().equals(mission.id())) problems.add(where + "событие другой миссии " + event);
					}
					case TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_RESET -> {
						if (!own.contains(event.subject())) problems.add(where + "событие чужой задачи " + event);
					}
					case CHECKPOINT_REACHED, CHECKPOINT_RESET, CHECKPOINT_RESUMED -> {
						String number = event.subject().substring(event.subject().lastIndexOf(':') + 1);
						int n = number.matches("\\d{1,4}") ? Integer.parseInt(number) : 0;
						if (!event.checkpointMission().equals(mission.id())) problems.add(where + "чекпоинт другой миссии " + event);
						else if (n < 1 || n > mission.checkpoints().size()) problems.add(where + "нет такого чекпоинта: " + event);
						else if (event.kind() == LevelEvent.Kind.CHECKPOINT_REACHED && n == 1) problems.add(where + "первый чекпоинт — начало миссии, вход в него не событие: " + event);
					}
					default -> {
					}
				}
			}
			for (LevelAction action : trigger.then()) {
				String task = action instanceof LevelAction.StartTask(String t) ? t : action instanceof LevelAction.FailTask(String t) ? t : null;
				if (task != null && allTasks.contains(task) && !own.contains(task)) problems.add(where + "действие над чужой задачей \"" + task + "\"");
			}
		}
	}

	/** Приказ напарнику: известен BehaviorMod, параметры верны, персонаж из этого уровня. */
	private static void orderProblems(String where, OrderCall call, LevelDefinition level, List<String> problems) {
		var order = com.smeshariki.behavior.order.Orders.get(call.order());
		if (order == null) problems.add(where + ": неизвестный приказ " + call.order());
		else for (String problem : order.problems(call.params())) problems.add(where + ": " + call.order() + ": " + problem);
		call.character().filter(c -> !level.characters().isEmpty() && !level.characters().contains(c)).ifPresent(c -> problems.add(where + ": приказ персонажу не из этого уровня: " + c));
	}

	private void finish(TaskEntry entry, int players) {
		LevelEvent started = LevelEvent.taskStarted(entry.id());
		Long at = times.get(started);
		if (at == null) return;
		Vec3 from = positions.get(started);
		Vec3 end = entry.task().endPosition();
		reach(LevelEvent.taskCompleted(entry.id()), at + entry.task().estimate(from).total(entry.scope(), players), end != null ? end : from);
	}

	/** Миссия кончается с последней своей задачей, тогда же начинается следующая, а за последней кончается уровень. */
	private void advanceMissions(LevelDefinition level) {
		List<MissionEntry> missions = level.missions();
		for (int m = 0; m < missions.size(); m++) {
			long last = -1;
			Vec3 pos = null;
			boolean finished = true;
			for (TaskEntry entry : missions.get(m).tasks()) {
				// необязательные миссию не держат
				if (entry.optional()) continue;
				LevelEvent completed = LevelEvent.taskCompleted(entry.id());
				Long at = times.get(completed);
				if (at == null) {
					finished = false;
					break;
				}
				if (at > last) {
					last = at;
					pos = positions.get(completed);
				}
			}
			if (!finished || last < 0) return;
			reach(LevelEvent.missionCompleted(missions.get(m).id()), last, pos);
			reach(m + 1 < missions.size() ? LevelEvent.missionStarted(missions.get(m + 1).id()) : LevelEvent.LEVEL_COMPLETED, last, pos);
		}
	}

	private void reach(LevelEvent event, long time, Vec3 pos) {
		Long known = times.get(event);
		if (known != null && known <= time) return;
		times.put(event, time);
		positions.put(event, pos);
		changed = true;
	}
}
