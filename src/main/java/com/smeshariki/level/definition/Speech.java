package com.smeshariki.level.definition;

import com.smeshariki.gameplay.character.Characters;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Реплика персонажа в чате: «Псевдоним: текст», псевдоним цветом персонажа (основной цвет его модели, см. {@link Characters#alias}). В чат идут только такие реплики. */
public final class Speech {
	private Speech() {
	}

	public static Component line(Identifier character, Component text) {
		return Component.empty().append(Characters.alias(character)).append(Component.literal(": ").withStyle(ChatFormatting.WHITE)).append(text.copy().withStyle(ChatFormatting.WHITE));
	}
}
