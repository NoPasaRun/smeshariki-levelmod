package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.smeshariki.gameplay.api.Mechanics;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Проверка состояния в редакторе: какая ({@code check}, из реестра моста) и параметры строкой {@code ключ=значение, ...}. Параметры-объекты
 * (место, записанное не именем) окно не показывает, но сохраняет.
 */
final class ConditionDraft {
	Identifier check;
	String args = "";
	final JsonObject extra = new JsonObject();

	static ConditionDraft blank() {
		ConditionDraft draft = new ConditionDraft();
		if (!Mechanics.conditions().isEmpty()) draft.check = Mechanics.conditions().keySet().iterator().next();
		return draft;
	}

	static ConditionDraft of(JsonObject json) {
		ConditionDraft draft = new ConditionDraft();
		draft.check = Identifier.tryParse(LevelDraft.text(json, "check", ""));
		List<String> args = new ArrayList<>();
		for (var entry : json.entrySet()) {
			if (entry.getKey().equals("check")) continue;
			if (entry.getValue().isJsonPrimitive()) args.add(entry.getKey() + "=" + entry.getValue().getAsString());
			else draft.extra.add(entry.getKey(), entry.getValue());
		}
		draft.args = String.join(", ", args);
		return draft;
	}

	static List<ConditionDraft> list(JsonElement json) {
		List<ConditionDraft> out = new ArrayList<>();
		if (json != null && json.isJsonArray()) for (JsonElement e : json.getAsJsonArray()) if (e.isJsonObject()) out.add(of(e.getAsJsonObject()));
		return out;
	}

	JsonObject toJson() throws TaskDraft.Invalid {
		if (check == null) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.conditions.no_check"));
		JsonObject json = new JsonObject();
		json.addProperty("check", check.toString());
		extra.entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
		LevelDraft.parseArgs(args).entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
		return json;
	}

	static JsonArray toJson(List<ConditionDraft> list, Component owner) throws TaskDraft.Invalid {
		if (list.isEmpty()) throw new TaskDraft.Invalid(Component.translatable("gui.levelmod.conditions.empty_for", owner));
		JsonArray out = new JsonArray();
		for (ConditionDraft c : list) out.add(c.toJson());
		return out;
	}

	static Component name(Identifier check) {
		return check == null ? Component.translatable("gui.levelmod.conditions.no_check") : Component.translatableWithFallback("condition." + check.getNamespace() + "." + check.getPath(), check.getPath());
	}
}
