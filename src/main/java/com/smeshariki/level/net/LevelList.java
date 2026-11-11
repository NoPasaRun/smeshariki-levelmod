package com.smeshariki.level.net;

import java.util.List;
import java.util.Optional;

import com.smeshariki.level.LevelMod;
import com.smeshariki.level.definition.LevelKind;
import com.smeshariki.level.definition.LevelPreview;
import com.smeshariki.level.run.LevelRun;
import com.smeshariki.level.run.LevelStats;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Всё, что показывает окно уровней. Сервер рассылает список при входе и при любых изменениях, отдельно клиент его не запрашивает.
 *
 * @param cutscenes кат-сцены для редактора
 * @param canEdit этому игроку доступен редактор
 * @param testing у этого игрока идёт проверка задачи
 * @param testAreas её области, для показа
 * @param testTasks её задачи, для списка на экране
 * @param locations локации для того, кому доступен редактор
 * @param groups реестр групп объектов для того, кому доступен редактор
 * @param resetPercent сколько процентов отката мира после уровня сделано; {@code -1} — отката нет
 */
public record LevelList(List<LevelInfo> levels, List<Identifier> cutscenes, boolean canEdit, boolean testing, List<TaskArea> testAreas, List<TaskInfo> testTasks, Locations locations, int resetPercent, List<GroupInfo> groups) implements CustomPacketPayload {
	/**
	 * @param mission номер миссии
	 * @param mine касается ли задача этого игрока
	 * @param optional необязательная
	 * @param missed необязательная, закрытая невыполненной
	 */
	public record TaskInfo(Component title, String counter, boolean started, boolean completed, Optional<TaskArea> area, int mission, boolean mine, boolean optional, boolean missed) {
		static final StreamCodec<RegistryFriendlyByteBuf, TaskInfo> CODEC = StreamCodec.composite(
				ComponentSerialization.TRUSTED_STREAM_CODEC, TaskInfo::title,
				ByteBufCodecs.STRING_UTF8, TaskInfo::counter,
				ByteBufCodecs.BOOL, TaskInfo::started,
				ByteBufCodecs.BOOL, TaskInfo::completed,
				ByteBufCodecs.optional(ByteBufCodecs.fromCodec(TaskArea.CODEC)), TaskInfo::area,
				ByteBufCodecs.VAR_INT, TaskInfo::mission,
				ByteBufCodecs.BOOL, TaskInfo::mine,
				ByteBufCodecs.BOOL, TaskInfo::optional,
				ByteBufCodecs.BOOL, TaskInfo::missed,
				TaskInfo::new);
	}

	public record RunInfo(LevelRun.State state, List<LevelRun.Participant> participants, long elapsed) {
		private static final StreamCodec<RegistryFriendlyByteBuf, LevelRun.Participant> PARTICIPANT = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, LevelRun.Participant::id,
				ByteBufCodecs.STRING_UTF8, LevelRun.Participant::name,
				LevelRun.Participant::new);
		static final StreamCodec<RegistryFriendlyByteBuf, RunInfo> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT.map(i -> LevelRun.State.values()[i], LevelRun.State::ordinal), RunInfo::state,
				PARTICIPANT.apply(ByteBufCodecs.list()), RunInfo::participants,
				ByteBufCodecs.VAR_LONG, RunInfo::elapsed,
				RunInfo::new);
	}

	public record LocationInfo(String name, Optional<Vec3> point, Optional<TaskArea> area) {
		static final StreamCodec<RegistryFriendlyByteBuf, LocationInfo> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, LocationInfo::name,
				ByteBufCodecs.optional(Vec3.STREAM_CODEC), LocationInfo::point,
				ByteBufCodecs.optional(ByteBufCodecs.fromCodec(TaskArea.CODEC)), LocationInfo::area,
				LocationInfo::new);
	}

	/**
	 * @param marked отмеченные локации
	 * @param missing какие просят файлы уровней, а их нет
	 * @param problems какие уровни не загрузились и почему
	 */
	/** Группа в реестре: имя и сколько объектов в ней среди загруженных. */
	public record GroupInfo(String name, int count) {
		static final StreamCodec<RegistryFriendlyByteBuf, GroupInfo> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GroupInfo::name, ByteBufCodecs.VAR_INT, GroupInfo::count, GroupInfo::new);
	}

	public record Locations(List<LocationInfo> marked, List<String> missing, List<String> problems) {
		public static final Locations NONE = new Locations(List.of(), List.of(), List.of());
		static final StreamCodec<RegistryFriendlyByteBuf, Locations> CODEC = StreamCodec.composite(
				LocationInfo.CODEC.apply(ByteBufCodecs.list()), Locations::marked,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Locations::missing,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Locations::problems,
				Locations::new);
	}

	/** @param refusal почему этот игрок не может взять уровень: ключ перевода, пустая строка — может */
	/** @param overridesFile уровень из файла мода, а в мире лежит его правка из редактора: файл не действует, пока правку не убрать */
	public record Meta(LevelKind kind, int minPlayers, int maxPlayers, String refusal, Optional<Vec3> startPos, List<Identifier> characters, boolean overridesFile) {
		static final StreamCodec<RegistryFriendlyByteBuf, Meta> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT.map(i -> LevelKind.values()[i], LevelKind::ordinal), Meta::kind,
				ByteBufCodecs.VAR_INT, Meta::minPlayers,
				ByteBufCodecs.VAR_INT, Meta::maxPlayers,
				ByteBufCodecs.STRING_UTF8, Meta::refusal,
				ByteBufCodecs.optional(Vec3.STREAM_CODEC), Meta::startPos,
				Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Meta::characters,
				ByteBufCodecs.BOOL, Meta::overridesFile,
				Meta::new);
	}

	/**
	 * @param source JSON уровня, если он создан в редакторе и его можно менять
	 * @param missionDescriptions описания миссий (по номеру миссии), пустой компонент — не задано
	 */
	public record LevelInfo(Identifier id, Component title, Component description, LevelPreview preview, Meta meta, long expectedTicks, LevelStats stats, List<TaskInfo> tasks, Optional<RunInfo> run, Optional<String> source, List<Component> missions, List<Component> missionDescriptions) {
		static final StreamCodec<RegistryFriendlyByteBuf, LevelInfo> CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, LevelInfo::id,
				ComponentSerialization.TRUSTED_STREAM_CODEC, LevelInfo::title,
				ComponentSerialization.TRUSTED_STREAM_CODEC, LevelInfo::description,
				ByteBufCodecs.fromCodec(LevelPreview.CODEC), LevelInfo::preview,
				Meta.CODEC, LevelInfo::meta,
				ByteBufCodecs.VAR_LONG, LevelInfo::expectedTicks,
				LevelStats.STREAM_CODEC, LevelInfo::stats,
				TaskInfo.CODEC.apply(ByteBufCodecs.list()), LevelInfo::tasks,
				ByteBufCodecs.optional(RunInfo.CODEC), LevelInfo::run,
				ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_JSON)), LevelInfo::source,
				ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list()), LevelInfo::missions,
				ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list()), LevelInfo::missionDescriptions,
				LevelInfo::new);

		public int minPlayers() {
			return meta.minPlayers();
		}

		public int maxPlayers() {
			return meta.maxPlayers();
		}
	}

	/** Больше JSON уровня не принимаем: контуры в нём по имени, так что это с большим запасом. */
	public static final int MAX_JSON = 1 << 20;
	public static final Type<LevelList> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_list"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelList> CODEC = StreamCodec.composite(
			LevelInfo.CODEC.apply(ByteBufCodecs.list()), LevelList::levels,
			Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), LevelList::cutscenes,
			ByteBufCodecs.BOOL, LevelList::canEdit,
			ByteBufCodecs.BOOL, LevelList::testing,
			ByteBufCodecs.fromCodec(TaskArea.CODEC).apply(ByteBufCodecs.list()), LevelList::testAreas,
			TaskInfo.CODEC.apply(ByteBufCodecs.list()), LevelList::testTasks,
			Locations.CODEC, LevelList::locations,
			ByteBufCodecs.VAR_INT.map(i -> i - 1, i -> i + 1), LevelList::resetPercent,
			GroupInfo.CODEC.apply(ByteBufCodecs.list()), LevelList::groups,
			LevelList::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
