package com.smeshariki.level.definition;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.task.TaskArea;

/** Параметры действия механики из файла уровня. После сохранения в NBT логическое становится числом, поэтому чтение терпимое. */
public record MechanicParams(JsonObject args) implements Mechanics.Params {
	@Override
	public String string(String name, String fallback) {
		JsonElement e = args.get(name);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	@Override
	public boolean bool(String name, boolean fallback) {
		JsonElement e = args.get(name);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean()) return p.getAsBoolean();
		if (p.isNumber()) return p.getAsDouble() != 0;
		return Boolean.parseBoolean(p.getAsString());
	}

	@Override
	public double number(String name, double fallback) {
		JsonElement e = args.get(name);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		try {
			return e.getAsDouble();
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	@Override
	public @Nullable Mechanics.Area area(String name) {
		JsonElement e = args.get(name);
		return e == null ? null : TaskArea.CODEC.parse(JsonOps.INSTANCE, e).result().orElse(null);
	}
}
