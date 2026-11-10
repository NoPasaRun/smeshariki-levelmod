package com.smeshariki.level.definition;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import net.minecraft.resources.Identifier;

/**
 * Приказ напарнику из файла уровня: {@code {"order":"behaviormod:take_rope","character":"smeshariki:krosh","kind":"lift"}}. Остальные поля объекта —
 * параметры приказа (их смысл знает BehaviorMod, {@code Orders}); {@code "@имя"} в них заменяется на место, как везде. {@code character} — какому
 * напарнику; без него — первому, какой есть.
 */
public record OrderCall(Optional<Identifier> character, Identifier order, JsonObject args) {
	/** Поля, которые не уходят в параметры. */
	private static final Set<String> OWN = Set.of("action", "text", "character", "order");

	public static final MapCodec<OrderCall> MAP_CODEC = new MapCodec<>() {
		@Override
		public <T> Stream<T> keys(DynamicOps<T> ops) {
			return Stream.of("character", "order").map(ops::createString);
		}

		@Override
		public <T> DataResult<OrderCall> decode(DynamicOps<T> ops, MapLike<T> input) {
			T orderValue = input.get("order");
			if (orderValue == null) return DataResult.error(() -> "у приказа нет order");
			DataResult<Identifier> order = Identifier.CODEC.parse(ops, orderValue);
			if (order.isError()) return DataResult.error(() -> "приказ: " + order.error().orElseThrow().message());
			Optional<Identifier> character = Optional.empty();
			T characterValue = input.get("character");
			if (characterValue != null) {
				DataResult<Identifier> parsed = Identifier.CODEC.parse(ops, characterValue);
				if (parsed.isError()) return DataResult.error(() -> "приказ: " + parsed.error().orElseThrow().message());
				character = parsed.result();
			}
			JsonObject args = new JsonObject();
			input.entries().forEach(pair -> ops.getStringValue(pair.getFirst()).result().ifPresent(key -> {
				if (!OWN.contains(key)) args.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
			}));
			Optional<Identifier> who = character;
			return order.map(id -> new OrderCall(who, id, args));
		}

		@Override
		public <T> RecordBuilder<T> encode(OrderCall input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
			prefix.add("order", ops.createString(input.order().toString()));
			input.character().ifPresent(c -> prefix.add("character", ops.createString(c.toString())));
			for (var entry : input.args().entrySet()) prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
			return prefix;
		}
	};

	public MechanicParams params() {
		return new MechanicParams(args);
	}
}
