package com.smeshariki.level.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Прежние файлы уровней хранили все триггеры в одном списке уровня. Теперь события миссии живут в самой миссии ({@code missions[].triggers}) и видны только
 * в ней; в уровне остаются события уровня. Перед разбором триггер уровня переезжает в миссию, если его событие и его действия касаются одной миссии:
 * <ul>
 * <li>хозяин — миссия первого события {@code when}, касающегося миссии или её задачи; нет такого — миссия задач из {@code start_task}/{@code fail_task};</li>
 * <li>касающееся другой миссии (в {@code when}, {@code unless} или действиях) остаётся в уровне;</li>
 * <li>события механик ({@code gameplaymod:lava_wave_finished:lava1}) идут за группой в конце: если эту группу запускает действие уже переехавшего триггера
 * ровно одной миссии, триггер переезжает туда же (так цепочка волн остаётся целой);</li>
 * <li>события и действия самого уровня ({@code level_started}, кат-сцены без задач) остаются в уровне.</li>
 * </ul>
 * Повторный проход ничего не меняет.
 */
public final class LegacyTriggers {
	private static final Set<String> KINDS = Set.of("level_started", "level_completed", "level_abandoned", "mission_started", "mission_completed", "mission_reset", "mission_resumed",
			"task_started", "task_completed", "task_failed", "task_reset", "cutscene_started", "cutscene_finished", "custom", "checkpoint_reached", "checkpoint_reset", "checkpoint_resumed");

	private LegacyTriggers() {
	}

	/** Копия JSON уровня с триггерами, разложенными по миссиям; не уровень — как есть. */
	public static JsonElement migrate(JsonElement json) {
		if (!json.isJsonObject() || !json.getAsJsonObject().has("triggers") || !json.getAsJsonObject().get("triggers").isJsonArray()) return json;
		JsonObject level = json.getAsJsonObject().deepCopy();
		if (!level.has("missions") || !level.get("missions").isJsonArray()) return json;
		Map<String, String> taskMission = new HashMap<>();
		Map<String, JsonObject> missions = new HashMap<>();
		for (JsonElement m : level.getAsJsonArray("missions")) {
			if (!m.isJsonObject() || !m.getAsJsonObject().has("id")) continue;
			JsonObject mission = m.getAsJsonObject();
			String id = mission.get("id").getAsString();
			missions.put(id, mission);
			if (mission.has("tasks") && mission.get("tasks").isJsonArray()) {
				for (JsonElement t : mission.getAsJsonArray("tasks")) if (t.isJsonObject() && t.getAsJsonObject().has("id")) taskMission.put(t.getAsJsonObject().get("id").getAsString(), id);
			}
		}
		JsonArray triggers = level.getAsJsonArray("triggers");
		Map<JsonObject, String> owner = new java.util.IdentityHashMap<>();
		// группа механики → миссии, чьи триггеры её запускают
		Map<String, Set<String>> groupMissions = new HashMap<>();
		for (JsonElement t : triggers) if (t.isJsonObject()) {
			String own = ownerOf(t.getAsJsonObject(), taskMission, missions.keySet(), null);
			if (own != null) owner.put(t.getAsJsonObject(), own);
		}
		for (boolean changed = true; changed; ) {
			groupMissions.clear();
			for (var e : owner.entrySet()) for (String group : groupsOf(e.getKey())) groupMissions.computeIfAbsent(group, g -> new HashSet<>()).add(e.getValue());
			changed = false;
			for (JsonElement t : triggers) {
				if (!t.isJsonObject() || owner.containsKey(t.getAsJsonObject())) continue;
				String own = ownerOf(t.getAsJsonObject(), taskMission, missions.keySet(), groupMissions);
				if (own != null) {
					owner.put(t.getAsJsonObject(), own);
					changed = true;
				}
			}
		}
		if (owner.isEmpty()) return json;
		JsonArray left = new JsonArray();
		for (JsonElement t : triggers) {
			String own = t.isJsonObject() ? owner.get(t.getAsJsonObject()) : null;
			if (own == null) {
				left.add(t);
				continue;
			}
			JsonObject mission = missions.get(own);
			if (!mission.has("triggers") || !mission.get("triggers").isJsonArray()) mission.add("triggers", new JsonArray());
			mission.getAsJsonArray("triggers").add(t);
		}
		level.add("triggers", left);
		return level;
	}

	/** Хозяин триггера или {@code null}: триггер уровня или касающийся нескольких миссий. {@code groups} — вторая проход: по группе в событии механики. */
	private static String ownerOf(JsonObject trigger, Map<String, String> taskMission, Set<String> missionIds, Map<String, Set<String>> groups) {
		List<String> when = strings(trigger.get("when")), unless = strings(trigger.get("unless"));
		Set<String> found = new LinkedHashSet<>();
		String first = null;
		for (String event : when) {
			String m = missionOf(event, taskMission, missionIds);
			if (m != null) {
				found.add(m);
				if (first == null) first = m;
			}
		}
		for (String event : unless) {
			String m = missionOf(event, taskMission, missionIds);
			if (m != null) found.add(m);
		}
		String fromActions = null;
		for (JsonObject action : actions(trigger.get("then"))) {
			String type = action.has("action") ? action.get("action").getAsString() : "";
			if (!type.equals("start_task") && !type.equals("fail_task") || !action.has("task")) continue;
			String m = taskMission.get(action.get("task").getAsString());
			if (m == null) continue;
			found.add(m);
			if (fromActions == null) fromActions = m;
		}
		// показ кат-сцены — действие уровня (окно «События уровня»): «начало миссии → кат-сцена» остаётся в уровне, иначе после перезагрузки его в окне уровня не видно,
		// а каждое сохранение дописывает копию. В миссии остаётся только кат-сцена после задачи (task_*), её ведёт сама задача
		boolean cutscene = actions(trigger.get("then")).stream().anyMatch(a -> a.has("action") && a.get("action").getAsString().equals("play_cutscene"));
		if (cutscene && when.stream().noneMatch(e -> e.startsWith("task_"))) return null;
		if (found.size() > 1) return null;
		if (!found.isEmpty()) return found.iterator().next();
		if (groups == null) return null;
		// события механик: группа в конце названия, её запускает ровно одна миссия
		Set<String> byGroup = new HashSet<>();
		boolean external = false;
		for (String event : when) {
			if (isKnown(event)) continue;
			external = true;
			Set<String> owners = groups.get(event.substring(event.lastIndexOf(':') + 1));
			if (owners == null || owners.size() != 1) return null;
			byGroup.addAll(owners);
		}
		return external && byGroup.size() == 1 ? byGroup.iterator().next() : null;
	}

	private static boolean isKnown(String event) {
		int colon = event.indexOf(':');
		return KINDS.contains(colon < 0 ? event : event.substring(0, colon));
	}

	/** Миссия, которой касается событие миссии или задачи; {@code null} — событие другого рода. */
	private static String missionOf(String event, Map<String, String> taskMission, Set<String> missionIds) {
		int colon = event.indexOf(':');
		if (colon < 0) return null;
		String key = event.substring(0, colon), subject = event.substring(colon + 1);
		if (key.startsWith("mission_")) return missionIds.contains(subject) ? subject : null;
		if (key.startsWith("task_")) return taskMission.get(subject);
		if (key.startsWith("checkpoint_")) {
			int c = subject.lastIndexOf(':');
			return c > 0 && missionIds.contains(subject.substring(0, c)) ? subject.substring(0, c) : null;
		}
		return null;
	}

	/** Группы механик из действий триггера (поле {@code group}). */
	private static List<String> groupsOf(JsonObject trigger) {
		List<String> out = new ArrayList<>();
		for (JsonObject action : actions(trigger.get("then"))) {
			String type = action.has("action") ? action.get("action").getAsString() : "";
			if (type.indexOf(':') > 0 && action.has("group") && action.get("group").isJsonPrimitive()) out.add(action.get("group").getAsString());
		}
		return out;
	}

	private static List<String> strings(JsonElement element) {
		List<String> out = new ArrayList<>();
		if (element == null) return out;
		if (element.isJsonArray()) element.getAsJsonArray().forEach(e -> {
			if (e.isJsonPrimitive()) out.add(e.getAsString());
		});
		else if (element.isJsonPrimitive()) out.add(element.getAsString());
		return out;
	}

	private static List<JsonObject> actions(JsonElement element) {
		List<JsonObject> out = new ArrayList<>();
		if (element == null) return out;
		if (element.isJsonArray()) element.getAsJsonArray().forEach(e -> {
			if (e.isJsonObject()) out.add(e.getAsJsonObject());
		});
		else if (element.isJsonObject()) out.add(element.getAsJsonObject());
		return out;
	}
}
