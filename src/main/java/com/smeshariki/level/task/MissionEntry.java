package com.smeshariki.level.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Миссия: этап уровня. Миссии идут строго по порядку, следующая начинается, когда выполнены все задачи текущей. Внутри миссии задачи по умолчанию
 * идут одновременно, в любом порядке; порядок можно задать через начало задачи.
 *
 * @param id имя внутри уровня, на него ссылаются события
 * @param description описание миссии: показывается по кнопке «Подробнее» рядом со списком задач на экране
 * @param area поле {@code area} (раньше {@code restore}, тоже читается): область миссии. Всё в ней возвращается при сбросе задачи миссии и в конце
 *             уровня: подвижное (шары со стропами и топливом, плоты, маркеры волн, лава — всё, что механики умеют запоминать) — каким было в
 *             начале миссии, блоки — к текущей версии мира (BuildMod, V).
 * @param checkpoints поле {@code checkpoints}: чекпоинты миссии по приоритету ({@link Checkpoint}), не меньше двух: начало и конец. В начале миссии каждый участник
 *                    не на первом своём чекпоинте переносится туда (задачи и сброс считают его началом); сброс (граница, гибель) возвращает на самый дальний достигнутый
 * @param triggers поле {@code triggers}: события и триггеры миссии. Видны и работают только в её рамках (события её задач, начала, конца, сброса и чекпоинтов; события механик); идут, пока миссия началась и не закончилась (включая своё событие конца). События других миссий и уровня сюда не пишутся
 * @param orders поле {@code orders}: реплики напарнику ({@link com.smeshariki.level.definition.OrderEntry}), которые игрок может выбрать в окне реплик,
 *               пока идёт миссия. Без них окно в миссии не открывается
 * @param gear поле {@code gear}: что надето на персонажа в начале миссии (тип → предметы одежды). Надевается, если слот пуст и такого предмета у
 *             игрока нет: в проверке миссии Ёжик сразу в очках, в игре очки, взятые в первой миссии, не удваиваются
 * @param resetWait поле {@code reset_wait}: сколько секунд после сброса задачи (граница, гибель) игрок стоит на месте, пока на экране идёт отсчёт; камеру он
 *             крутит. 0 — без ожидания
 * @param guide поле {@code guide}: куда игроку бежать, область или точка ({@code "@имя"}). Пока миссия идёт, перед игроком висит полупрозрачная стрелка и показывает на цель;
 *              без поля стрелка показывает на область идущей задачи «добраться до области» (клиент рисует её сам, {@code GuideArrow})
 */
public record MissionEntry(String id, Component title, Component description, List<TaskEntry> tasks, Optional<TaskArea> area, List<Checkpoint> checkpoints, List<com.smeshariki.level.definition.OrderEntry> orders,
		Map<Identifier, List<Identifier>> gear, int resetWait, Optional<Either<Vec3, TaskArea>> guide, List<com.smeshariki.level.definition.Trigger> triggers) {
	/** Ближе этого к точке-чекпоинту игрок «на чекпоинте» (его не переносят, он засчитан), блоков по горизонтали. */
	public static final double START_RADIUS = 4;
	private static final Codec<Either<Vec3, TaskArea>> PLACE = Checkpoint.PLACE;

	public static final Codec<MissionEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(MissionEntry::id),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(MissionEntry::title),
			ComponentSerialization.CODEC.optionalFieldOf("description", Component.empty()).forGetter(MissionEntry::description),
			TaskEntry.CODEC.listOf().fieldOf("tasks").forGetter(MissionEntry::tasks),
			TaskArea.CODEC.optionalFieldOf("area").forGetter(MissionEntry::area),
			TaskArea.CODEC.optionalFieldOf("restore").forGetter(m -> Optional.empty()),
			Checkpoint.CODEC.listOf().optionalFieldOf("checkpoints", List.of()).forGetter(MissionEntry::checkpoints),
			com.smeshariki.level.definition.OrderEntry.CODEC.listOf().optionalFieldOf("orders", List.of()).forGetter(MissionEntry::orders),
			Codec.unboundedMap(Identifier.CODEC, net.minecraft.util.ExtraCodecs.compactListCodec(Identifier.CODEC)).optionalFieldOf("gear", Map.of()).forGetter(MissionEntry::gear),
			Codec.intRange(0, 60).optionalFieldOf("reset_wait", 0).forGetter(MissionEntry::resetWait),
			PLACE.optionalFieldOf("guide").forGetter(MissionEntry::guide),
			com.smeshariki.level.definition.Trigger.CODEC.listOf().optionalFieldOf("triggers", List.of()).forGetter(MissionEntry::triggers)
	).apply(i, (id, title, description, tasks, area, legacyRestore, checkpoints, orders, gear, resetWait, guide, triggers) ->
			new MissionEntry(id, title, description, tasks, area.or(() -> legacyRestore), checkpoints, orders, gear, resetWait, guide, triggers)));

	/** Чекпоинты этого персонажа по приоритету: общие и его собственные (кто неизвестен — только общие). Первый — начало, последний — конец. */
	public List<Checkpoint> checkpointsFor(@Nullable Identifier role) {
		return checkpoints.stream().filter(checkpoint -> checkpoint.appliesTo(role)).toList();
	}
}
