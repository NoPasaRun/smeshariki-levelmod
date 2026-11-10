package com.smeshariki.level.definition;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** Тип уровня. Сюжетный у игрока один, побочные берутся к нему, пока он идёт ({@code LevelManager.refusal}). */
public enum LevelKind implements StringRepresentable {
	STORY("story"),
	SIDE("side");

	public static final Codec<LevelKind> CODEC = StringRepresentable.fromEnum(LevelKind::values);
	private final String name;

	LevelKind(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}
