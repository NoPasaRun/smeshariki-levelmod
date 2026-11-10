package com.smeshariki.level.definition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * Реплика миссии (поле {@code orders}): что игрок может сказать напарнику в окне реплик, пока идёт миссия, и какой приказ тот выполнит.
 * {@code {"text":"Крош, выше!","character":"smeshariki:krosh","order":"behaviormod:balloon_lift","steps":2}}. Видна, только если у игрока есть
 * такой напарник: играют двое — напарников нет, и реплик тоже.
 */
public record OrderEntry(Component text, OrderCall call) {
	public static final Codec<OrderEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.fieldOf("text").forGetter(OrderEntry::text),
			OrderCall.MAP_CODEC.forGetter(OrderEntry::call)
	).apply(i, OrderEntry::new));
}
