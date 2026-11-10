package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.definition.LevelKind;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * Уровень в редакторе. Сохраняется в тот же JSON, что и файлы уровней, места в нём — имена локаций. Кат-сцены в начале и после задач и правила
 * механик при сохранении становятся триггерами, а при открытии собираются из них обратно; остальные триггеры проходят как есть.
 */
final class LevelDraft {
	/**
	 * Правило механики: когда случилось событие, выполнить действие механики другого мода с параметрами. Параметры — строка {@code ключ=значение, ...}
	 * ({@code group=a}, {@code area=@засада}, {@code on=false}); что они значат, редактор не знает. На событие механики правило повторяемое: такие
	 * события случаются много раз (волна с одного берега кончилась — пустить с другого).
	 */
	static final class MechanicRule {
		LevelEvent when = LevelEvent.LEVEL_STARTED;
		Identifier action;
		String args = "";
		/** Задержка после события, секунды. */
		String delay = "0";
		/** Отменить отложенное действие, если до срока случится это событие ({@code unless}); {@code null} — не отменять. */
		LevelEvent unless;
		/** Срабатывать при каждом новом случении события, а не один раз за прохождение ({@code repeat}). У события механики по умолчанию да. */
		boolean repeat;
	}

	/** Наблюдатель: при каких проверках случается {@code custom:id}; {@code after} — событие, с которого наблюдать (строкой, пусто — с начала). */
	static final class WatchDraft {
		String id = "";
		String after = "";
		/** Задержка события, секунды. */
		String delay = "0";
		final List<ConditionDraft> conditions = new ArrayList<>();
	}

	/** Чекпоинт миссии в редакторе: место (локация по имени), для кого он и ограничения высоты, которые редактор не показывает, но сохраняет. */
	static final class CheckpointDraft {
		/** Локация по имени; пусто — место записано иначе ({@link #rawPlace}). */
		String location = "";
		com.google.gson.JsonElement rawPlace;
		/** Только для этого персонажа; {@code null} — для всех. */
		Identifier character;
		final JsonObject extras = new JsonObject();

		static CheckpointDraft of(JsonElement json) {
			CheckpointDraft draft = new CheckpointDraft();
			JsonElement place = json;
			if (json.isJsonObject() && json.getAsJsonObject().has("at")) {
				JsonObject object = json.getAsJsonObject();
				place = object.get("at");
				if (object.has("character")) draft.character = Identifier.tryParse(object.get("character").getAsString());
				for (String key : new String[] {"min_y", "max_y"}) if (object.has(key)) draft.extras.add(key, object.get(key));
			}
			if (place.isJsonPrimitive() && place.getAsString().startsWith("@")) draft.location = place.getAsString().substring(1);
			else draft.rawPlace = place;
			return draft;
		}

		JsonElement toJson() {
			JsonElement place = location.isEmpty() ? rawPlace : new com.google.gson.JsonPrimitive("@" + location);
			if (place == null) return null;
			if (character == null && extras.size() == 0) return place;
			JsonObject object = new JsonObject();
			object.add("at", place);
			if (character != null) object.addProperty("character", character.toString());
			extras.entrySet().forEach(e -> object.add(e.getKey(), e.getValue()));
			return object;
		}
	}

	/** Миссия в редакторе. */
	static final class MissionDraft {
		/** Постройки миссии и действия за отсутствующих: редактор их не показывает, но сохраняет. */
		com.google.gson.JsonElement areaRaw, orders, gear, guideRaw;
		/** Чекпоинты по приоритету: первый — начало миссии, последний — конец. */
		final List<CheckpointDraft> checkpoints = new ArrayList<>();
		/** Ожидание после сброса задачи, секунд (0 — нет). */
		int resetWait;
		/** Куда бежать (подсказка светом): локация по имени, пусто — нет. */
		String guide = "";
		/** Область миссии (локация): в ней всё возвращается при сбросе. */
		String area = "";
		final String id;
		String title = "";
		/** Описание миссии: показывается по кнопке «Подробнее» рядом со списком задач. */
		String description = "";
		final List<TaskDraft> tasks = new ArrayList<>();

		MissionDraft(String id) {
			this.id = id;
		}
	}

	/** {@code null} — новый уровень. */
	final Identifier id;
	LevelKind kind = LevelKind.STORY;
	String title = "", description = "", preview = "";
	/** Точка начала уровня, локация-точка: пусто — не задана. */
	String start = "";
	int minPlayers = 1, maxPlayers = 1, order;
	Identifier intro;
	/** За кого можно проходить; пусто — за любого. */
	final List<Identifier> characters = new ArrayList<>();
	/** Откуда начинает каждый персонаж: локация-точка. */
	final Map<Identifier, String> characterStarts = new HashMap<>();
	final List<MissionDraft> missions = new ArrayList<>();
	final List<MechanicRule> rules = new ArrayList<>();
	final List<WatchDraft> watches = new ArrayList<>();
	private final List<JsonElement> otherTriggers = new ArrayList<>();
	private int nextTask = 1, nextMission = 1;

	private LevelDraft(Identifier id) {
		this.id = id;
	}

	static LevelDraft blank() {
		return new LevelDraft(null);
	}

	/** Текстовое поле JSON или значение по умолчанию. */
	static String text(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	/** Имя локации из ссылки {@code "@имя"}; всё другое редактор не показывает. */
	static String location(JsonObject json, String key) {
		String value = text(json, key, "");
		return value.startsWith("@") ? value.substring(1) : "";
	}

	static LevelDraft of(Identifier id, String source) {
		// старые файлы с start и character_starts читаются как чекпоинты
		JsonObject json = com.smeshariki.level.definition.LegacyCheckpoints.migrate(JsonParser.parseString(source)).getAsJsonObject();
		LevelDraft draft = new LevelDraft(id);
		draft.kind = "side".equals(text(json, "kind", "story")) ? LevelKind.SIDE : LevelKind.STORY;
		draft.title = text(json, "title", "");
		draft.description = text(json, "description", "");
		draft.start = location(json, "start_pos");
		if (json.has("preview")) draft.preview = text(json.getAsJsonObject("preview"), "texture", "");
		if (json.has("min_players")) draft.minPlayers = json.get("min_players").getAsInt();
		if (json.has("max_players")) draft.maxPlayers = json.get("max_players").getAsInt();
		if (json.has("order")) draft.order = json.get("order").getAsInt();
		if (json.has("characters")) for (JsonElement character : json.getAsJsonArray("characters")) draft.characters.add(Identifier.parse(character.getAsString()));
		if (json.has("character_starts")) for (var entry : json.getAsJsonObject("character_starts").entrySet()) {
			String value = entry.getValue().getAsString();
			if (value.startsWith("@")) draft.characterStarts.put(Identifier.parse(entry.getKey()), value.substring(1));
		}
		for (JsonElement missionJson : json.getAsJsonArray("missions")) {
			JsonObject object = missionJson.getAsJsonObject();
			MissionDraft mission = new MissionDraft(text(object, "id", "m0"));
			mission.title = text(object, "title", "");
			mission.description = text(object, "description", "");
			if (object.has("checkpoints")) for (JsonElement checkpoint : object.getAsJsonArray("checkpoints")) mission.checkpoints.add(CheckpointDraft.of(checkpoint));
			// поле раньше называлось restore
			String areaKey = object.has("area") ? "area" : "restore";
			mission.area = location(object, areaKey);
			if (mission.area.isEmpty() && object.has(areaKey)) mission.areaRaw = object.get(areaKey);
			mission.orders = object.get("orders");
			mission.gear = object.get("gear");
			mission.resetWait = object.has("reset_wait") && object.get("reset_wait").isJsonPrimitive() ? Math.clamp(object.get("reset_wait").getAsInt(), 0, 60) : 0;
			mission.guide = location(object, "guide");
			if (mission.guide.isEmpty() && object.has("guide")) mission.guideRaw = object.get("guide");
			draft.missions.add(mission);
			draft.nextMission = Math.max(draft.nextMission, number(mission.id) + 1);
			for (JsonElement taskJson : object.getAsJsonArray("tasks")) {
				TaskDraft task = TaskDraft.of(taskJson.getAsJsonObject());
				mission.tasks.add(task);
				draft.nextTask = Math.max(draft.nextTask, number(task.id) + 1);
			}
		}
		if (json.has("watches")) for (JsonElement w : json.getAsJsonArray("watches")) {
			JsonObject object = w.getAsJsonObject();
			WatchDraft watch = new WatchDraft();
			watch.id = text(object, "id", "");
			watch.after = text(object, "after", "");
			if (object.has("delay")) watch.delay = seconds(object.get("delay").getAsInt());
			watch.conditions.addAll(ConditionDraft.list(object.get("conditions")));
			draft.watches.add(watch);
		}
		if (json.has("triggers")) for (JsonElement trigger : json.getAsJsonArray("triggers")) if (!draft.readCutscene(trigger.getAsJsonObject()) && !draft.readRule(trigger.getAsJsonObject())) draft.otherTriggers.add(trigger);
		return draft;
	}

	/** Кат-сцена в начале или после задачи: true, если триггер как раз такой. */
	private boolean readCutscene(JsonObject trigger) {
		if (trigger.has("delay") || !trigger.get("when").isJsonPrimitive() || !trigger.get("then").isJsonObject()) return false;
		JsonObject then = trigger.getAsJsonObject("then");
		if (!"play_cutscene".equals(text(then, "action", ""))) return false;
		Identifier cutscene = Identifier.parse(text(then, "cutscene", ""));
		String when = trigger.get("when").getAsString();
		if (when.equals(LevelEvent.LEVEL_STARTED.toString())) {
			intro = cutscene;
			return true;
		}
		String prefix = LevelEvent.taskCompleted("").toString();
		TaskDraft task = when.startsWith(prefix) ? task(when.substring(prefix.length())) : null;
		if (task == null) return false;
		task.outro = cutscene;
		return true;
	}

	/** Правило механики: true, если триггер как раз такой (одно событие, одно действие механики с простыми параметрами, повторяемость как у правила). */
	private boolean readRule(JsonObject trigger) {
		if (!trigger.get("when").isJsonPrimitive() || !trigger.get("then").isJsonObject()) return false;
		JsonObject then = trigger.getAsJsonObject("then");
		String type = text(then, "action", "");
		Identifier action = type.indexOf(':') > 0 ? Identifier.tryParse(type) : null;
		if (action == null) return false;
		List<String> args = new ArrayList<>();
		for (var entry : then.entrySet()) {
			if (entry.getKey().equals("action")) continue;
			if (!entry.getValue().isJsonPrimitive()) return false;
			args.add(entry.getKey() + "=" + entry.getValue().getAsString());
		}
		LevelEvent when = LevelEvent.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, trigger.get("when")).result().orElse(null);
		if (when == null) return false;
		MechanicRule rule = new MechanicRule();
		rule.when = when;
		rule.action = action;
		rule.args = String.join(", ", args);
		if (trigger.has("delay")) rule.delay = seconds(trigger.get("delay").getAsInt());
		rule.repeat = trigger.has("repeat") && trigger.get("repeat").getAsBoolean();
		if (trigger.has("unless")) {
			if (!trigger.get("unless").isJsonPrimitive()) return false;
			rule.unless = LevelEvent.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, trigger.get("unless")).result().orElse(null);
			if (rule.unless == null) return false;
		}
		rules.add(rule);
		return true;
	}

	/** Тики в секунды для поля окна: целые, если делится. */
	static String seconds(int ticks) {
		return ticks % 20 == 0 ? String.valueOf(ticks / 20) : String.valueOf(ticks / 20.0);
	}

	/** Секунды из поля окна в тики. */
	static int ticks(String seconds) throws TaskDraft.Invalid {
		try {
			return Math.max(0, (int) Math.round(Double.parseDouble(seconds.trim().replace(',', '.')) * 20));
		} catch (NumberFormatException e) {
			throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.rules.bad_args", seconds));
		}
	}

	/** Параметры правила из строки {@code ключ=значение, ...}: true и false становятся логическими, числа — числами. */
	static JsonObject parseArgs(String text) throws TaskDraft.Invalid {
		JsonObject out = new JsonObject();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			int eq = part.indexOf('=');
			if (eq <= 0) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.rules.bad_args", part.trim()));
			String key = part.substring(0, eq).trim(), value = part.substring(eq + 1).trim();
			if (value.equals("true") || value.equals("false")) out.addProperty(key, Boolean.parseBoolean(value));
			else if (value.matches("-?\\d+")) out.addProperty(key, Long.parseLong(value));
			else if (value.matches("-?\\d+\\.\\d+")) out.addProperty(key, Double.parseDouble(value));
			else out.addProperty(key, value);
		}
		return out;
	}

	/** Номер из имени вида {@code t3}: номера не повторяем, даже если часть удалена, на них ссылаются триггеры. */
	private static int number(String id) {
		try {
			return Integer.parseInt(id.substring(1));
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Персонажи этого уровня: кому можно назначать задачи. Никто не отмечен — подходят все. */
	List<Identifier> boundCharacters() {
		if (!characters.isEmpty()) return characters;
		List<Identifier> all = new ArrayList<>();
		for (var type : Possession.choices()) all.add(EntityType.getKey(type));
		return all;
	}

	List<TaskDraft> allTasks() {
		List<TaskDraft> all = new ArrayList<>();
		for (MissionDraft mission : missions) all.addAll(mission.tasks);
		return all;
	}

	TaskDraft task(String id) {
		for (TaskDraft task : allTasks()) if (task.id.equals(id)) return task;
		return null;
	}

	MissionDraft missionOf(TaskDraft task) {
		for (MissionDraft mission : missions) if (mission.tasks.contains(task)) return mission;
		return null;
	}

	MissionDraft addMission() {
		MissionDraft mission = new MissionDraft("m" + nextMission++);
		missions.add(mission);
		return mission;
	}

	TaskDraft addTask(MissionDraft mission) {
		TaskDraft task = new TaskDraft("t" + nextTask++);
		mission.tasks.add(task);
		return task;
	}

	void moveMission(MissionDraft mission, int by) {
		int index = missions.indexOf(mission), target = index + by;
		if (target < 0 || target >= missions.size()) return;
		missions.remove(index);
		missions.add(target, mission);
	}

	void removeMission(MissionDraft mission) {
		for (TaskDraft task : List.copyOf(mission.tasks)) removeTask(task);
		missions.remove(mission);
		for (MechanicRule rule : rules) if (rule.when.subject().equals(mission.id) && rule.when.kind() == LevelEvent.Kind.MISSION_STARTED) rule.when = LevelEvent.LEVEL_STARTED;
	}

	/** Те, кто ждал удалённую задачу, начинаются тогда, когда начиналась бы она: цепочка не рвётся. */
	void removeTask(TaskDraft removed) {
		MissionDraft mission = missionOf(removed);
		if (mission != null) mission.tasks.remove(removed);
		for (TaskDraft task : allTasks()) {
			if (task.start != null && task.start.subject().equals(removed.id) && (task.start.kind() == LevelEvent.Kind.TASK_COMPLETED || task.start.kind() == LevelEvent.Kind.TASK_STARTED)) task.start = removed.start;
		}
		for (TaskDraft task : allTasks()) task.after.remove(removed.id);
		// правило, ждавшее удалённую задачу, сработает тогда, когда начиналась бы она
		LevelEvent instead = removed.start != null ? removed.start : mission != null ? LevelEvent.missionStarted(mission.id) : LevelEvent.LEVEL_STARTED;
		for (MechanicRule rule : rules) if (rule.when.subject().equals(removed.id) && (rule.when.kind() == LevelEvent.Kind.TASK_COMPLETED || rule.when.kind() == LevelEvent.Kind.TASK_STARTED)) rule.when = instead;
	}

	JsonObject missionJson(MissionDraft mission) throws TaskDraft.Invalid {
		JsonObject json = new JsonObject();
		json.addProperty("id", mission.id);
		json.addProperty("title", mission.title.isBlank() ? mission.id : mission.title.trim());
		if (!mission.description.isBlank()) json.addProperty("description", mission.description.trim());
		JsonArray tasks = new JsonArray();
		for (TaskDraft task : mission.tasks) tasks.add(task.toJson());
		json.add("tasks", tasks);
		JsonArray checkpoints = new JsonArray();
		for (CheckpointDraft checkpoint : mission.checkpoints) {
			JsonElement element = checkpoint.toJson();
			if (element != null) checkpoints.add(element);
		}
		json.add("checkpoints", checkpoints);
		if (!mission.area.isEmpty()) json.addProperty("area", "@" + mission.area);
		else if (mission.areaRaw != null) json.add("area", mission.areaRaw);
		if (mission.orders != null) json.add("orders", mission.orders);
		if (mission.gear != null) json.add("gear", mission.gear);
		if (mission.resetWait > 0) json.addProperty("reset_wait", mission.resetWait);
		if (!mission.guide.isEmpty()) json.addProperty("guide", "@" + mission.guide);
		else if (mission.guideRaw != null) json.add("guide", mission.guideRaw);
		return json;
	}

	private static JsonObject cutsceneTrigger(String when, Identifier cutscene) {
		JsonObject then = new JsonObject();
		then.addProperty("action", "play_cutscene");
		then.addProperty("cutscene", cutscene.toString());
		JsonObject trigger = new JsonObject();
		trigger.addProperty("when", when);
		trigger.add("then", then);
		return trigger;
	}

	JsonObject toJson() throws TaskDraft.Invalid {
		if (title.isBlank()) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.editor.title_required"));
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind.getSerializedName());
		json.addProperty("title", title.trim());
		json.addProperty("description", description.trim());
		if (!preview.isBlank()) {
			if (Identifier.tryParse(preview.trim()) == null) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.editor.bad_preview"));
			JsonObject image = new JsonObject();
			image.addProperty("type", "image");
			image.addProperty("texture", preview.trim());
			json.add("preview", image);
		}
		json.addProperty("min_players", minPlayers);
		json.addProperty("max_players", Math.max(minPlayers, maxPlayers));
		json.addProperty("order", order);
		if (!start.isEmpty()) json.addProperty("start_pos", "@" + start);
		JsonArray list = new JsonArray();
		for (Identifier character : characters) list.add(character.toString());
		json.add("characters", list);
		JsonObject starts = new JsonObject();
		characterStarts.forEach((character, location) -> starts.addProperty(character.toString(), "@" + location));
		json.add("character_starts", starts);
		JsonArray missionList = new JsonArray();
		JsonArray triggers = new JsonArray();
		otherTriggers.forEach(triggers::add);
		if (intro != null) triggers.add(cutsceneTrigger(LevelEvent.LEVEL_STARTED.toString(), intro));
		for (MechanicRule rule : rules) {
			if (rule.action == null) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.rules.no_action"));
			JsonObject then = new JsonObject();
			then.addProperty("action", rule.action.toString());
			parseArgs(rule.args).entrySet().forEach(e -> then.add(e.getKey(), e.getValue()));
			JsonObject trigger = new JsonObject();
			trigger.addProperty("when", rule.when.toString());
			int ruleDelay = ticks(rule.delay);
			if (ruleDelay > 0) trigger.addProperty("delay", ruleDelay);
			if (rule.unless != null) trigger.addProperty("unless", rule.unless.toString());
			if (rule.repeat) trigger.addProperty("repeat", true);
			trigger.add("then", then);
			triggers.add(trigger);
		}
		for (MissionDraft mission : missions) {
			missionList.add(missionJson(mission));
			for (TaskDraft task : mission.tasks) if (task.outro != null) triggers.add(cutsceneTrigger(LevelEvent.taskCompleted(task.id).toString(), task.outro));
		}
		json.add("missions", missionList);
		json.add("triggers", triggers);
		if (!watches.isEmpty()) {
			JsonArray list2 = new JsonArray();
			for (WatchDraft watch : watches) {
				if (watch.id.isBlank()) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.watches.no_id"));
				JsonObject w = new JsonObject();
				w.addProperty("id", watch.id.trim());
				if (!watch.after.isBlank()) w.addProperty("after", watch.after.trim());
				int watchDelay = ticks(watch.delay);
				if (watchDelay > 0) w.addProperty("delay", watchDelay);
				w.add("conditions", ConditionDraft.toJson(watch.conditions, Component.literal(watch.id)));
				list2.add(w);
			}
			json.add("watches", list2);
		}
		return json;
	}
}
