package com.smeshariki.level.definition;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Старые файлы уровней знали у миссии {@code start}, {@code character_starts} и {@code return_to}; теперь это чекпоинты. Перед разбором такая миссия получает
 * {@code checkpoints}: её старты (общий и свои у персонажей), затем конец — старты следующей миссии, а у последней — область её последней задачи «добраться до области».
 * Миссия, у которой {@code checkpoints} уже есть, остаётся как есть (старые поля из неё просто убираются). {@code return_to} пропадает: возвращает чекпоинт.
 */
public final class LegacyCheckpoints {
	private LegacyCheckpoints() {
	}

	/** Копия JSON уровня с чекпоинтами вместо старых полей; не уровень — как есть. */
	public static JsonElement migrate(JsonElement json) {
		if (!json.isJsonObject() || !json.getAsJsonObject().has("missions") || !json.getAsJsonObject().get("missions").isJsonArray()) return json;
		JsonObject level = json.getAsJsonObject().deepCopy();
		JsonArray missions = level.getAsJsonArray("missions");
		List<List<JsonElement>> starts = new ArrayList<>();
		for (JsonElement mission : missions) starts.add(mission.isJsonObject() ? startsOf(mission.getAsJsonObject()) : List.of());
		for (int i = 0; i < missions.size(); i++) {
			if (!missions.get(i).isJsonObject()) continue;
			JsonObject mission = missions.get(i).getAsJsonObject();
			if (!mission.has("checkpoints")) {
				JsonArray out = new JsonArray();
				starts.get(i).forEach(out::add);
				if (i + 1 < missions.size()) starts.get(i + 1).forEach(out::add);
				else {
					JsonElement end = lastDestination(mission);
					if (end != null) out.add(end);
				}
				if (!out.isEmpty()) mission.add("checkpoints", out);
			}
			mission.remove("start");
			mission.remove("character_starts");
			mission.remove("return_to");
		}
		return level;
	}

	private static List<JsonElement> startsOf(JsonObject mission) {
		List<JsonElement> out = new ArrayList<>();
		if (mission.has("character_starts") && mission.get("character_starts").isJsonObject()) {
			for (var entry : mission.getAsJsonObject("character_starts").entrySet()) {
				JsonObject own = new JsonObject();
				own.add("at", entry.getValue());
				own.addProperty("character", entry.getKey());
				out.add(own);
			}
		}
		if (mission.has("start")) out.add(mission.get("start"));
		return out;
	}

	private static JsonElement lastDestination(JsonObject mission) {
		if (!mission.has("tasks") || !mission.get("tasks").isJsonArray()) return null;
		JsonElement end = null;
		for (JsonElement task : mission.getAsJsonArray("tasks")) {
			if (task.isJsonObject() && task.getAsJsonObject().has("target") && "reach_area".equals(task.getAsJsonObject().has("type") ? task.getAsJsonObject().get("type").getAsString() : "")) end = task.getAsJsonObject().get("target");
		}
		return end;
	}
}
