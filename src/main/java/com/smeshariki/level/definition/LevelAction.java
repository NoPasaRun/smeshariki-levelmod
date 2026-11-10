package com.smeshariki.level.definition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

/**
 * Что делает сработавший {@link Trigger}. Своё действие уровня — запись здесь, ветка в {@code LevelRun.perform} и в {@link LevelAnalyzer}. Механики
 * других модов сюда не добавляются: они идут через {@link Mechanic} по своему идентификатору.
 */
public sealed interface LevelAction {
	Codec<LevelAction> CODEC = Codec.STRING.validate(type -> switch (type) {
		case "start_task", "play_cutscene", "emit", "fail_task", "say", "order" -> DataResult.success(type);
		default -> type.indexOf(':') > 0 && Identifier.tryParse(type) != null ? DataResult.success(type) : DataResult.error(() -> "Неизвестное действие: " + type);
	}).dispatch("action", LevelAction::type, type -> switch (type) {
		case "start_task" -> StartTask.MAP_CODEC;
		case "play_cutscene" -> PlayCutscene.MAP_CODEC;
		case "emit" -> Emit.MAP_CODEC;
		case "fail_task" -> FailTask.MAP_CODEC;
		case "say" -> Say.MAP_CODEC;
		case "order" -> Order.MAP_CODEC;
		default -> Mechanic.mapCodec(type);
	});

	String type();

	/** Начать задачу уровня. */
	record StartTask(String task) implements LevelAction {
		static final MapCodec<StartTask> MAP_CODEC = Codec.STRING.fieldOf("task").xmap(StartTask::new, StartTask::task);

		@Override
		public String type() {
			return "start_task";
		}
	}

	/** Запустить кат-сцену. */
	record PlayCutscene(Identifier cutscene) implements LevelAction {
		static final MapCodec<PlayCutscene> MAP_CODEC = Identifier.CODEC.fieldOf("cutscene").xmap(PlayCutscene::new, PlayCutscene::cutscene);

		@Override
		public String type() {
			return "play_cutscene";
		}
	}

	/** Начать задачу заново, как при ошибке: {@code task_failed}, сброс, возврат её игроков к началу и постройки миссии ({@code restore}) — из снимка. */
	record FailTask(String task) implements LevelAction {
		static final MapCodec<FailTask> MAP_CODEC = Codec.STRING.fieldOf("task").xmap(FailTask::new, FailTask::task);

		@Override
		public String type() {
			return "fail_task";
		}
	}

	/** Сообщение участникам: в чат или над панелью ({@code "overlay": true}); с {@code speaker} (тип персонажа) в чате это его реплика «Псевдоним: текст». */
	record Say(Component text, boolean overlay, java.util.Optional<Identifier> speaker) implements LevelAction {
		static final MapCodec<Say> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				ComponentSerialization.CODEC.fieldOf("text").forGetter(Say::text),
				Codec.BOOL.optionalFieldOf("overlay", false).forGetter(Say::overlay),
				Identifier.CODEC.optionalFieldOf("speaker").forGetter(Say::speaker)
		).apply(i, Say::new));

		@Override
		public String type() {
			return "say";
		}
	}

	/**
	 * Действие механики другого мода по её идентификатору: {@code {"action":"gameplaymod:lava_wave","group":"a"}}. Остальные поля объекта — её
	 * параметры, их смысл знает только механика (GameplayMod, {@code Mechanics}); {@code "@имя"} в них, как везде, заменяется на место.
	 */
	record Mechanic(Identifier id, JsonObject args) implements LevelAction {
		static MapCodec<Mechanic> mapCodec(String type) {
			Identifier id = Identifier.parse(type);
			return new MapCodec<>() {
				@Override
				public <T> Stream<T> keys(DynamicOps<T> ops) {
					return Stream.empty();
				}

				@Override
				public <T> DataResult<Mechanic> decode(DynamicOps<T> ops, MapLike<T> input) {
					JsonObject args = new JsonObject();
					input.entries().forEach(pair -> ops.getStringValue(pair.getFirst()).result().ifPresent(key -> {
						if (!key.equals("action")) args.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
					}));
					return DataResult.success(new Mechanic(id, args));
				}

				@Override
				public <T> RecordBuilder<T> encode(Mechanic input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
					for (var entry : input.args().entrySet()) prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
					return prefix;
				}
			};
		}

		@Override
		public String type() {
			return id.toString();
		}
	}

	/** Приказ напарнику (BehaviorMod): {@code {"action":"order","character":"smeshariki:krosh","order":"behaviormod:take_rope","kind":"lift"}}. Нет такого напарника — ничего. */
	record Order(OrderCall call) implements LevelAction {
		static final MapCodec<Order> MAP_CODEC = OrderCall.MAP_CODEC.xmap(Order::new, Order::call);

		@Override
		public String type() {
			return "order";
		}
	}

	/** Породить своё именованное событие {@code custom:имя}. */
	record Emit(String event) implements LevelAction {
		static final MapCodec<Emit> MAP_CODEC = Codec.STRING.fieldOf("event").xmap(Emit::new, Emit::event);

		@Override
		public String type() {
			return "emit";
		}
	}
}
