package com.smeshariki.level.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * Ячейка отката чекпоинта: что ещё запоминать в области миссии, когда игрок входит в чекпоинт, и возвращать при откате на него. Дополняет общий снимок
 * подвижного: {@code block} — все блоки этого вида в области (вернутся на свои места, лишние исчезнут), {@code entity} — все сущности этого вида в области
 * (в том числе те, что общий снимок не берёт: мобы и т. п.; игроков и смешариков не бывает). Ячеек у чекпоинта не больше {@link #MAX}.
 */
public record ResetCell(Kind kind, Identifier type) {
	public static final int MAX = 8;

	public enum Kind implements StringRepresentable {
		BLOCK("block"), ENTITY("entity");

		public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
		private final String key;

		Kind(String key) {
			this.key = key;
		}

		@Override
		public String getSerializedName() {
			return key;
		}
	}

	public static final Codec<ResetCell> CODEC = RecordCodecBuilder.create(i -> i.group(
			Kind.CODEC.fieldOf("kind").forGetter(ResetCell::kind),
			Identifier.CODEC.fieldOf("type").forGetter(ResetCell::type)
	).apply(i, ResetCell::new));
}
