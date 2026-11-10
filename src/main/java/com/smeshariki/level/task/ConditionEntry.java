package com.smeshariki.level.task;

import java.util.List;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.definition.MechanicParams;

import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * Проверка состояния из файла уровня: {@code {"check":"gameplaymod:holding_rope","kind":"lift"}}. Что она значит, знает тот, кто её
 * зарегистрировал в {@link Mechanics} (GameplayMod или сам LevelMod); остальные поля — её параметры, {@code "@имя"} в них заменяется на место.
 */
public record ConditionEntry(Identifier check, JsonObject args) {
	public static final Codec<ConditionEntry> CODEC = ExtraCodecs.JSON.comapFlatMap(json -> {
		if (!json.isJsonObject()) return DataResult.error(() -> "Условие должно быть объектом: " + json);
		JsonObject object = json.getAsJsonObject().deepCopy();
		Identifier check = object.has("check") && object.get("check").isJsonPrimitive() ? Identifier.tryParse(object.get("check").getAsString()) : null;
		if (check == null) return DataResult.error(() -> "У условия нет проверки (check): " + json);
		object.remove("check");
		return DataResult.success(new ConditionEntry(check, object));
	}, entry -> {
		JsonObject out = new JsonObject();
		out.addProperty("check", entry.check().toString());
		entry.args().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
		return out;
	});

	public MechanicParams params() {
		return new MechanicParams(args);
	}

	/** Что не так: неизвестная проверка или её параметры. */
	public List<String> problems() {
		Mechanics.Condition condition = Mechanics.condition(check);
		if (condition == null) return List.of("неизвестная проверка " + check);
		return condition.problems(params()).stream().map(p -> check + ": " + p).toList();
	}
}
