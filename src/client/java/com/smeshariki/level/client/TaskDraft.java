package com.smeshariki.level.client;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.task.TaskScope;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Задача в редакторе. Места — только имена локаций: перерисованная локация сразу действует во всех задачах. Сохраняется в JSON файла уровня. */
final class TaskDraft {
	static final List<String> TYPES = List.of("reach_area", "reach", "get_item", "condition", "mine_block", "talk", "wait");

	/** Ввод автора, который не удалось разобрать. */
	static final class Invalid extends Exception {
		final Component problem;

		Invalid(Component problem) {
			this.problem = problem;
		}
	}

	final String id;
	String title = "";
	String type = "reach_area";
	/** Куда дойти: локация-точка, для {@code reach}. */
	String point = "";
	String radius = "2";
	String block = "minecraft:oak_log", item = "minecraft:egg", count = "1", seconds = "10";
	/** Компоненты сюжетного предмета ({@code components} у {@code get_item}): редактор их не показывает, но сохраняет. */
	JsonElement components;
	/** Проверки состояния задачи {@code condition}. */
	final List<ConditionDraft> conditions = new java.util.ArrayList<>();
	/** Проверки разом (по умолчанию) или по очереди, каждая хоть раз. */
	boolean together = true;
	/** Необязательная: миссия её не ждёт. */
	boolean optional;
	/** С кем говорить: с любым персонажем, даже не из этого уровня. */
	Identifier with = EntityType.getKey(Possession.choices().getFirst());
	String text = "";
	/** Куда добраться: локация-область, для {@code reach_area}. */
	String target = "";
	TaskScope scope = TaskScope.TEAM;
	/** Для кого задача; {@code null} — для всех. */
	Identifier role;
	/** Когда начинается; {@code null} — сразу с началом миссии. */
	LevelEvent start;
	/** Задачи, которые нужны кроме {@code start} (поле {@code after}, когда их несколько): окно их не меняет, но сохраняет и показывает. */
	final List<String> after = new java.util.ArrayList<>();
	String delay = "0";
	/** Кат-сцена сразу после задачи. */
	Identifier outro;
	/** Граница, локация-область: пусто — нет границы. */
	String area = "";

	TaskDraft(String id) {
		this.id = id;
	}

	static TaskDraft of(JsonObject json) {
		TaskDraft draft = new TaskDraft(LevelDraft.text(json, "id", "t0"));
		draft.title = LevelDraft.text(json, "title", "");
		draft.type = LevelDraft.text(json, "type", "wait");
		draft.scope = "each".equals(LevelDraft.text(json, "scope", "team")) ? TaskScope.EACH : TaskScope.TEAM;
		if (json.has("character")) draft.role = Identifier.tryParse(LevelDraft.text(json, "character", ""));
		if (json.has("start_on")) draft.start = LevelEvent.CODEC.parse(JsonOps.INSTANCE, json.get("start_on")).result().orElse(null);
		if (json.has("after")) {
			List<String> deps = new java.util.ArrayList<>();
			JsonElement a = json.get("after");
			if (a.isJsonArray()) for (JsonElement e : a.getAsJsonArray()) deps.add(e.getAsString());
			else deps.add(a.getAsString());
			// одна задача без своего события — это и есть «начало после задачи», его окно умеет менять
			if (deps.size() == 1 && draft.start == null) draft.start = LevelEvent.taskCompleted(deps.getFirst());
			else draft.after.addAll(deps);
		}
		if (json.has("start_delay")) draft.delay = String.valueOf(json.get("start_delay").getAsInt() / 20);
		draft.area = LevelDraft.location(json, "area");
		draft.point = LevelDraft.location(json, "pos");
		draft.target = LevelDraft.location(json, "target");
		if (json.has("radius")) draft.radius = String.valueOf(json.get("radius").getAsDouble());
		if (json.has("block")) draft.block = LevelDraft.text(json, "block", draft.block);
		if (json.has("item")) draft.item = LevelDraft.text(json, "item", draft.item);
		draft.components = json.get("components");
		draft.conditions.addAll(ConditionDraft.list(json.get("conditions")));
		if (json.has("together")) draft.together = json.get("together").getAsBoolean();
		if (json.has("optional")) draft.optional = json.get("optional").getAsBoolean();
		if (json.has("count")) draft.count = String.valueOf(json.get("count").getAsInt());
		if (json.has("ticks")) draft.seconds = String.valueOf(json.get("ticks").getAsInt() / 20);
		if (json.has("with")) draft.with = Identifier.tryParse(LevelDraft.text(json, "with", ""));
		draft.text = LevelDraft.text(json, "text", "");
		return draft;
	}

	JsonObject toJson() throws Invalid {
		if (title.isBlank()) throw new Invalid(Component.translatable("gui.levelmod.task.no_title"));
		JsonObject json = new JsonObject();
		json.addProperty("id", id);
		json.addProperty("title", title.trim());
		json.addProperty("scope", scope.getSerializedName());
		if (role != null) json.addProperty("character", role.toString());
		if (start != null) json.addProperty("start_on", start.toString());
		if (!after.isEmpty()) {
			com.google.gson.JsonArray deps = new com.google.gson.JsonArray();
			after.forEach(deps::add);
			json.add("after", deps);
		}
		if (!area.isEmpty()) json.addProperty("area", "@" + area);
		if (optional) json.addProperty("optional", true);
		json.addProperty("type", type);
		try {
			int delayTicks = Math.max(0, Integer.parseInt(delay.trim())) * 20;
			if (delayTicks > 0) json.addProperty("start_delay", delayTicks);
			switch (type) {
				case "reach" -> {
					if (point.isEmpty()) throw new Invalid(Component.translatable("gui.levelmod.task.no_target", title));
					json.addProperty("pos", "@" + point);
					json.addProperty("radius", Math.max(0, Double.parseDouble(radius.trim())));
				}
				case "reach_area" -> {
					if (target.isEmpty()) throw new Invalid(Component.translatable("gui.levelmod.task.no_target", title));
					json.addProperty("target", "@" + target);
				}
				case "wait" -> json.addProperty("ticks", Math.max(1, Integer.parseInt(seconds.trim()) * 20));
				case "talk" -> {
					json.addProperty("with", with.toString());
					if (!text.isBlank()) json.addProperty("text", text.trim());
				}
				case "condition" -> {
					json.add("conditions", ConditionDraft.toJson(conditions, Component.literal(title)));
					if (!together) json.addProperty("together", false);
				}
				case "get_item" -> {
					// предмет или #тег
					if (com.smeshariki.gameplay.api.Mechanics.item(item.trim()) == null) throw new Invalid(Component.translatable("gui.levelmod.task.bad_item", title));
					json.addProperty("item", item.trim());
					if (components != null) json.add("components", components);
					json.addProperty("count", Math.max(1, Integer.parseInt(count.trim())));
				}
				default -> {
					Identifier id = Identifier.tryParse(block.trim());
					if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new Invalid(Component.translatable("gui.levelmod.task.bad_block", title));
					json.addProperty("block", id.toString());
					json.addProperty("count", Math.max(1, Integer.parseInt(count.trim())));
				}
			}
		} catch (NumberFormatException e) {
			throw new Invalid(Component.translatable("gui.levelmod.task.bad_number", title));
		}
		return json;
	}
}
