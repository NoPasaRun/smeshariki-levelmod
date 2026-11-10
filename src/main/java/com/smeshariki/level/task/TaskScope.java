package com.smeshariki.level.task;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** Как задача считается в команде. */
public enum TaskScope implements StringRepresentable {
	/** Вклады всех складываются, хватает одного игрока. */
	TEAM("team"),
	/** Каждый игрок в сети должен выполнить её сам. */
	EACH("each");

	public static final Codec<TaskScope> CODEC = StringRepresentable.fromEnum(TaskScope::values);
	private final String name;

	TaskScope(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}
