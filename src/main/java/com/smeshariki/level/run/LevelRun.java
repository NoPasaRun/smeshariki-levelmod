package com.smeshariki.level.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.definition.LevelAction;
import com.smeshariki.level.definition.MechanicParams;
import com.smeshariki.level.definition.Watch;
import com.smeshariki.level.definition.LevelDefinition;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.definition.Trigger;
import com.smeshariki.level.task.Checkpoint;
import com.smeshariki.level.task.MissionEntry;
import com.smeshariki.level.task.ReachAreaTask;
import com.smeshariki.level.task.ResetCell;
import com.smeshariki.level.task.TaskArea;
import com.smeshariki.level.task.TaskEntry;
import com.smeshariki.level.task.TaskScope;
import com.smeshariki.level.task.TaskSignal;
import com.smeshariki.level.task.TalkTask;
import com.smeshariki.level.task.TaskTracker;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Один проход уровня группой игроков: сбор группы, затем игра до завершения всех миссий или до выхода всех участников. Всё состояние
 * сохраняется в мире ({@link LevelStore}), поэтому прогон переживает перезапуск и откатывается вместе с версией мира. Время считается
 * тиками самого прогона: пока никого из участников нет в сети, оно стоит.
 */
public final class LevelRun {
	public enum State implements StringRepresentable {
		GATHERING, RUNNING, COMPLETED, ABANDONED;

		static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public record Participant(UUID id, String name) {
		static final Codec<Participant> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Participant::id),
				Codec.STRING.fieldOf("name").forGetter(Participant::name)
		).apply(i, Participant::new));
	}

	/** Куда возвращают игрока после гибели или сброса: место его чекпоинта. */
	public record StartPoint(ResourceKey<Level> dimension, Vec3 pos) {
		static final Codec<StartPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(StartPoint::dimension),
				Vec3.CODEC.fieldOf("pos").forGetter(StartPoint::pos)
		).apply(i, StartPoint::new));
	}

	/** Отложенные действия триггера с {@code delay}; любое из событий {@code unless} до срока их отменяет. */
	private record Delayed(long due, List<LevelAction> actions, List<LevelEvent> unless) {
		static final Codec<Delayed> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("due").forGetter(Delayed::due),
				LevelAction.CODEC.listOf().fieldOf("actions").forGetter(Delayed::actions),
				LevelEvent.CODEC.listOf().optionalFieldOf("unless", List.of()).forGetter(Delayed::unless)
		).apply(i, Delayed::new));
	}

	/** Состояние задачи: {@code shared} идёт всем сразу, {@code credits} — вклады отдельных игроков. */
	static final class Progress {
		static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.BOOL.fieldOf("started").forGetter(p -> p.started),
				Codec.BOOL.fieldOf("completed").forGetter(p -> p.completed),
				Codec.INT.fieldOf("shared").forGetter(p -> p.shared),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).fieldOf("credits").forGetter(p -> p.credits),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("baselines", Map.of()).forGetter(p -> p.baselines),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("marks", Map.of()).forGetter(p -> p.marks),
				Codec.BOOL.optionalFieldOf("missed", false).forGetter(p -> p.missed)
		).apply(i, Progress::new));

		boolean started, completed;
		/** Необязательная задача не выполнена к концу своей миссии: закрыта, больше не идёт и не начнётся. */
		boolean missed;
		int shared;
		final Map<UUID, Integer> credits;
		/** Сколько предметов у игрока было в начале задачи. Сброс задачи это не трогает: собранное остаётся в инвентаре и считается. */
		final Map<UUID, Integer> baselines;
		/** Память задачи об игроке (какие проверки уже были верны): сброс задачи её стирает, как и вклад. */
		final Map<UUID, Integer> marks;

		Progress(boolean started, boolean completed, int shared, Map<UUID, Integer> credits, Map<UUID, Integer> baselines, Map<UUID, Integer> marks, boolean missed) {
			this.started = started;
			this.missed = missed;
			this.completed = completed;
			this.shared = shared;
			this.credits = new HashMap<>(credits);
			this.baselines = new HashMap<>(baselines);
			this.marks = new HashMap<>(marks);
		}

		Progress() {
			this(false, false, 0, Map.of(), Map.of(), Map.of(), false);
		}

		boolean active() {
			return started && !completed && !missed;
		}
	}

	public static final Codec<LevelRun> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("level").forGetter(r -> r.level),
			State.CODEC.fieldOf("state").forGetter(r -> r.state),
			Participant.CODEC.listOf().fieldOf("participants").forGetter(r -> r.participants),
			Codec.LONG.fieldOf("elapsed").forGetter(r -> r.elapsed),
			Codec.unboundedMap(Codec.STRING, Progress.CODEC).fieldOf("tasks").forGetter(r -> r.tasks),
			LevelEvent.CODEC.listOf().fieldOf("happened").forGetter(r -> List.copyOf(r.happened)),
			Codec.INT.listOf().fieldOf("fired").forGetter(r -> List.copyOf(r.fired)),
			Codec.unboundedMap(Identifier.CODEC, Codec.INT).fieldOf("cutscenes").forGetter(r -> r.cutscenes),
			Delayed.CODEC.listOf().fieldOf("delayed").forGetter(r -> r.delayed),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("outside", Map.of()).forGetter(r -> r.outside),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("checkpoints", Map.of()).forGetter(r -> r.checkpoints),
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Identifier.CODEC).optionalFieldOf("roles", Map.of()).forGetter(r -> r.roles),
			Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("toggles", Map.of()).forGetter(r -> {
				Map<String, List<String>> out = new HashMap<>();
				r.toggles.forEach((id, keys) -> out.put(id, List.copyOf(keys)));
				return out;
			}),
			Codec.unboundedMap(Codec.STRING, CompoundTag.CODEC).optionalFieldOf("snapshots", Map.of()).forGetter(r -> r.snapshots),
			Extras.CODEC.forGetter(r -> new Extras(List.copyOf(r.companions), r.keptGear, r.forced, r.touchedForSave())),
			Codec.STRING.listOf().optionalFieldOf("armed_watches", List.of()).forGetter(r -> List.copyOf(r.armedWatches))
	).apply(i, LevelRun::new));

	/** Поля сверх шестнадцати, которые умеет {@code RecordCodecBuilder}: лежат в файле на верхнем уровне, как остальные. */
	private record Extras(List<Identifier> companions, Map<UUID, List<ItemStack>> keptGear, boolean forced, Map<UUID, List<Integer>> touched) {
		static final com.mojang.serialization.MapCodec<Extras> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Identifier.CODEC.listOf().optionalFieldOf("companion_characters", List.of()).forGetter(Extras::companions),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, ItemStack.CODEC.listOf()).optionalFieldOf("kept_gear", Map.of()).forGetter(Extras::keptGear),
				Codec.BOOL.optionalFieldOf("forced", false).forGetter(Extras::forced),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT.listOf()).optionalFieldOf("touched_checkpoints", Map.of()).forGetter(Extras::touched)
		).apply(i, Extras::new));
	}

	/** Приказ «за мной» (BehaviorMod) в начале каждой миссии. */
	private static final Identifier FOLLOW = Identifier.fromNamespaceAndPath("behaviormod", "follow");

	/** Сколько тиков можно провести вне области задачи. */
	public static final int AREA_GRACE_TICKS = 200;

	private final Identifier level;
	private State state;
	private final List<Participant> participants;
	private long elapsed;
	private final Map<String, Progress> tasks;
	private final Set<LevelEvent> happened;
	/** Номера сработавших триггеров. */
	private final Set<Integer> fired;
	/** Идущие кат-сцены: сколько тиков осталось. */
	private final Map<Identifier, Integer> cutscenes;
	private final List<Delayed> delayed;
	/** Сколько тиков подряд игрок вне области. */
	private final Map<UUID, Integer> outside;
	/** Самый дальний достигнутый чекпоинт текущей миссии у каждого игрока: номер в списке его чекпоинтов (0 — первый, начало). Сброс и гибель возвращают туда. */
	private final Map<UUID, Integer> checkpoints;
	/** Кем играет участник: закрепляется в начале уровня. */
	private final Map<UUID, Identifier> roles;
	/**
	 * Включённые триггерами переключатели механик (идентификатор → ключи, например группы стрел): держатся до конца прогона, поэтому переживают и
	 * сброс задачи, и перезапуск. Что они значат, знает только механика.
	 */
	private final Map<String, Set<String>> toggles;
	/** Снимки построек миссий с полем {@code restore}, по имени миссии: снимаются в её начале, возвращаются при сбросе и в конце уровня. */
	private final Map<String, CompoundTag> snapshots;
	/**
	 * Напарники (BehaviorMod) за персонажей уровня, которыми никто не играет: тип персонажа → сущность. Появляются в начале уровня рядом с первым
	 * игроком, идут за ним и слушаются реплик миссии ({@code orders}); с концом прогона убираются. Их задачи пропускаются, как и раньше.
	 */
	private final Set<Identifier> companions;
	/** Одежда погибшего персонажа участника: ждёт, пока его вернут к началу задачи, и надевается на нового. Сохраняется. */
	private final Map<UUID, List<ItemStack>> keptGear;
	/** Наблюдатели с задержкой, чьи проверки уже сошлись: их событие ждёт в {@code delayed}. */
	private final Set<String> armedWatches;
	/** Повторяемые триггеры, которые сейчас выполняются. */
	private final Set<Integer> repeating = new HashSet<>();

	private LevelDefinition definition;
	private RunHost host;
	private boolean changed = true;
	/** Проверка из редактора: миссия начинается с ожидания (отсчёт), как после сброса. Только в памяти: прогоны проверки не сохраняются. */
	private boolean waitAtStart;
	/** Уровень пропускали или завершали принудительно (команда оператора): время такого прогона в статистику не идёт. Сохраняется с прогоном. */
	private boolean forced;
	/** Чекпоинты миссии (номера в её списке), в которых игрок побывал: общий чекпоинт засчитывается, когда в нём побывали все. Сбрасывается в начале миссии. */
	private final Map<UUID, java.util.Set<Integer>> touched;

	private Map<UUID, List<Integer>> touchedForSave() {
		Map<UUID, List<Integer>> out = new HashMap<>();
		touched.forEach((id, set) -> out.put(id, List.copyOf(set)));
		return out;
	}

	private LevelRun(Identifier level, State state, List<Participant> participants, long elapsed, Map<String, Progress> tasks, List<LevelEvent> happened, List<Integer> fired, Map<Identifier, Integer> cutscenes, List<Delayed> delayed, Map<UUID, Integer> outside, Map<UUID, Integer> checkpoints, Map<UUID, Identifier> roles, Map<String, List<String>> toggles, Map<String, CompoundTag> snapshots, Extras extras, List<String> armedWatches) {
		this.level = level;
		this.state = state;
		this.participants = new ArrayList<>(participants);
		this.elapsed = elapsed;
		this.tasks = new HashMap<>(tasks);
		this.happened = new HashSet<>(happened);
		this.fired = new HashSet<>(fired);
		this.cutscenes = new HashMap<>(cutscenes);
		this.delayed = new ArrayList<>(delayed);
		this.outside = new HashMap<>(outside);
		this.checkpoints = new HashMap<>(checkpoints);
		this.roles = new HashMap<>(roles);
		this.toggles = new HashMap<>();
		toggles.forEach((id, keys) -> this.toggles.put(id, new HashSet<>(keys)));
		this.snapshots = new HashMap<>(snapshots);
		this.companions = new java.util.LinkedHashSet<>(extras.companions());
		this.forced = extras.forced();
		this.touched = new HashMap<>();
		extras.touched().forEach((id, list) -> this.touched.put(id, new HashSet<>(list)));
		this.keptGear = new HashMap<>();
		extras.keptGear().forEach((id, list) -> this.keptGear.put(id, new ArrayList<>(list)));
		this.armedWatches = new HashSet<>(armedWatches);
	}

	/** Новый прогон в состоянии сбора группы. */
	public static LevelRun gather(Identifier level, LevelDefinition definition, RunHost host) {
		Map<String, Progress> tasks = new HashMap<>();
		for (TaskEntry entry : definition.tasks()) tasks.put(entry.id(), new Progress());
		LevelRun run = new LevelRun(level, State.GATHERING, List.of(), 0, tasks, List.of(), List.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new Extras(List.of(), Map.of(), false, Map.of()), List.of());
		run.attach(definition, host);
		return run;
	}

	/** Подключает несохраняемое окружение: после загрузки мира и перезагрузки датапаков. */
	public void attach(LevelDefinition definition, RunHost host) {
		this.definition = definition;
		this.host = host;
	}

	/** Прогон подходит к определению: состав задач не менялся (датапак могли перезагрузить посреди прогона). */
	public boolean matches(LevelDefinition definition) {
		if (definition.tasks().size() != tasks.size()) return false;
		for (TaskEntry entry : definition.tasks()) if (!tasks.containsKey(entry.id())) return false;
		return true;
	}

	/** Переключатели механик, включённые для участников идущего прогона: идентификатор → ключи. */
	public Map<String, Set<String>> activeToggles() {
		return state == State.RUNNING ? toggles : Map.of();
	}

	public LevelDefinition definition() {
		return definition;
	}

	public Identifier level() {
		return level;
	}

	public State state() {
		return state;
	}

	public long elapsed() {
		return elapsed;
	}

	public List<Participant> participants() {
		return participants;
	}

	public boolean hasParticipant(UUID player) {
		return participants.stream().anyMatch(p -> p.id().equals(player));
	}

	public void addParticipant(UUID player, String name) {
		participants.add(new Participant(player, name));
		changed = true;
	}

	/** Была ли у игрока в этом прогоне роль (она остаётся и после выхода). */
	public boolean hasRole(UUID player) {
		return roles.containsKey(player);
	}

	public void removeParticipant(UUID player) {
		participants.removeIf(p -> p.id().equals(player));
		outside.remove(player);
		if (held.remove(player)) host.hold(player, false);
		changed = true;
	}

	/** Участники в сети в порядке присоединения; первый из них — лидер группы. */
	public List<UUID> online() {
		return participants.stream().map(Participant::id).filter(id -> host.position(id) != null).toList();
	}

	/** Было ли изменение, которое надо показать игрокам, с прошлого вопроса. */
	public boolean takeChanged() {
		boolean was = changed;
		changed = false;
		return was;
	}

	/** Начинать миссии с экрана ожидания (поле миссии {@code reset_wait}): так начинается проверка, полный прогон уровня стартует без него. */
	public void waitAtStart() {
		waitAtStart = true;
	}

	/** Начало уровня: персонажи закрепляются, игроки отправляются на свои стартовые места. */
	public void begin() {
		if (state != State.GATHERING) return;
		for (UUID id : online()) {
			Identifier character = host.character(id);
			if (character == null) continue;
			roles.put(id, character);
			Vec3 start = definition.characterStarts().get(character);
			if (start == null) continue;
			host.returnTo(this, id, new StartPoint(host.dimension(id), start));
		}
		state = State.RUNNING;
		spawnCompanions();
		fire(LevelEvent.LEVEL_STARTED);
		settle();
	}

	/** Миссия, которая сейчас идёт: начата и не завершена; {@code null} — уровень не идёт. */
	public @Nullable MissionEntry currentMission() {
		if (state != State.RUNNING) return null;
		for (MissionEntry mission : definition.missions()) {
			if (!happened.contains(LevelEvent.missionStarted(mission.id()))) return null;
			if (!happened.contains(LevelEvent.missionCompleted(mission.id()))) return mission;
		}
		return null;
	}

	/** Персонаж-напарник ({@code character} пусто — любой, какой есть); {@code null} — нет такого. */
	public @Nullable Identifier companionFor(java.util.Optional<Identifier> character) {
		if (character.isPresent()) return companions.contains(character.get()) ? character.get() : null;
		return companions.stream().findFirst().orElse(null);
	}

	/** Забрать одежду погибшего персонажа участника, чтобы надеть её снова, когда его вернут. */
	public void keepGear(UUID player, List<ItemStack> gear) {
		keptGear.computeIfAbsent(player, k -> new ArrayList<>()).addAll(gear);
	}

	/** Отдать сбережённую одежду участника (и забыть); пусто — нечего. */
	public List<ItemStack> takeGear(UUID player) {
		List<ItemStack> gear = keptGear.remove(player);
		return gear == null ? List.of() : gear;
	}

	public boolean hasKeptGear() {
		return !keptGear.isEmpty();
	}

	public java.util.Set<UUID> keptGearOwners() {
		return java.util.Set.copyOf(keptGear.keySet());
	}

	/** Персонажи-напарники прогона (смешарик в мире один, поэтому напарник — сам смешарик этого типа). */
	public Set<Identifier> companions() {
		return java.util.Collections.unmodifiableSet(companions);
	}

	/** За персонажей уровня, которыми никто не играет, — напарники первому игроку. Уровень без списка персонажей напарников не даёт. */
	private void spawnCompanions() {
		List<UUID> online = online();
		if (roles.isEmpty() || online.isEmpty() || definition.characters().isEmpty()) return;
		for (Identifier character : definition.characters()) {
			if (roles.containsValue(character) || companions.contains(character)) continue;
			host.recruitCompanion(character, online.getFirst());
			companions.add(character);
		}
	}

	/** Конец прогона: напарников отпускают, они остаются, где стоят. */
	private void dismissCompanions() {
		for (Identifier character : companions) host.releaseCompanion(character);
		companions.clear();
	}

	/** Приказ напарнику от ведущего: из реплики игрока или из триггера (тогда от первого игрока в сети). */
	public void order(com.smeshariki.level.definition.OrderCall call, @Nullable UUID leader) {
		Identifier companion = companionFor(call.character());
		List<UUID> online = online();
		UUID from = leader != null ? leader : online.isEmpty() ? null : online.getFirst();
		if (companion == null || from == null) return;
		host.orderCompanion(companion, from, call.order(), call.params());
	}

	/**
	 * Начало миссии: чекпоинты всех сброшены на первый, и кто не на нём, переносится туда. Если первый чекпоинт совпадает с последним чекпоинтом прошлой миссии,
	 * игрок уже на месте и его не трогают.
	 */
	private void gatherAtStart(MissionEntry mission) {
		checkpoints.clear();
		touched.clear();
		snapshots.keySet().removeIf(k -> k.startsWith(mission.id() + "#"));
		changed = true;
		for (UUID id : online()) {
			Identifier role = roles.get(id);
			if (role != null) for (Identifier item : mission.gear().getOrDefault(role, List.of())) host.wear(id, item);
			List<Checkpoint> own = mission.checkpointsFor(role);
			if (own.isEmpty() || atCheckpoint(id, own.getFirst())) continue;
			host.returnTo(this, id, placeOf(id, own.getFirst()));
		}
	}

	/** Игрок на чекпоинте: в области или не дальше {@link MissionEntry#START_RADIUS} от точки по горизонтали, на подходящей высоте. */
	private boolean atCheckpoint(UUID id, Checkpoint checkpoint) {
		Vec3 pos = host.position(id);
		if (pos == null || !checkpoint.heightFits(pos.y)) return false;
		return checkpoint.place().map(point -> Math.hypot(pos.x - point.x, pos.z - point.z) <= MissionEntry.START_RADIUS, area -> isInside(id, area));
	}

	/** Куда ставить игрока на чекпоинт: в саму точку или на пол у центра области. */
	private StartPoint placeOf(UUID id, Checkpoint checkpoint) {
		return checkpoint.place().map(point -> new StartPoint(host.dimension(id), point), area -> new StartPoint(area.dimension(), placeIn(area)));
	}

	/** Куда вернуть игрока: самый дальний достигнутый им чекпоинт идущей миссии; {@code null} — миссия не идёт или у неё нет чекпоинтов. */
	public @Nullable StartPoint checkpointPoint(UUID player) {
		MissionEntry mission = currentMission();
		if (mission == null) return null;
		List<Checkpoint> own = mission.checkpointsFor(roles.get(player));
		if (own.isEmpty()) return null;
		return placeOf(player, own.get(Math.min(checkpoints.getOrDefault(player, 0), own.size() - 1)));
	}

	/**
	 * Игрок входит в чекпоинты миссии: достигнутый дальше прежнего запоминается (приоритет — порядок в списке), нижние уже не вернут. Чекпоинт «для всех»
	 * (без {@code character}) засчитывается всем разом, когда в нём побывал каждый игрок в сети: пока не дошли все, он никого не двигает; свой чекпоинт персонажа —
	 * как только в нём побывал сам игрок.
	 */
	private void checkCheckpoints() {
		MissionEntry mission = currentMission();
		if (mission == null) return;
		List<UUID> here = online();
		for (UUID id : here) {
			List<Checkpoint> own = mission.checkpointsFor(roles.get(id));
			for (int i = 1; i < own.size(); i++) {
				if (atCheckpoint(id, own.get(i)) && touched.computeIfAbsent(id, k -> new HashSet<>()).add(mission.checkpoints().indexOf(own.get(i)))) changed = true;
			}
		}
		java.util.Set<Integer> announced = new HashSet<>();
		for (UUID id : here) {
			List<Checkpoint> own = mission.checkpointsFor(roles.get(id));
			int reached = checkpoints.getOrDefault(id, 0);
			for (int i = own.size() - 1; i > reached; i--) {
				Checkpoint reachedCheckpoint = own.get(i);
				int global = mission.checkpoints().indexOf(reachedCheckpoint);
				boolean passed = reachedCheckpoint.character().isPresent() ? touchedBy(id, global) : here.stream().allMatch(other -> touchedBy(other, global));
				if (!passed) continue;
				checkpoints.put(id, i);
				changed = true;
				takeCheckpointSnapshot(mission, id, reachedCheckpoint);
				host.tell(id, Component.translatable("message.levelmod.checkpoint"), true);
				// общий чекпоинт засчитан всем сразу, но случился один раз
				if (announced.add(global)) {
					fire(LevelEvent.checkpointReached(mission.id(), global + 1));
					reachedCheckpoint.onEnter().ifPresent(this::perform);
				}
				break;
			}
		}
	}

	private boolean touchedBy(UUID player, int checkpoint) {
		return touched.getOrDefault(player, java.util.Set.of()).contains(checkpoint);
	}

	/** Где встать в области: на полу у её центра на высоте, где её рисовали (так работает и под землёй); без нарисованных точек — на поверхности. */
	private Vec3 placeIn(TaskArea area) {
		Vec3 drawn = area.drawnCenter();
		return drawn != null ? host.floorNear(area.dimension(), drawn) : host.groundAt(area.dimension(), area.center());
	}

	/** Персонаж, закреплённый за игроком на этот уровень. */
	public @Nullable Identifier roleOf(UUID player) {
		return roles.get(player);
	}

	/** Касается ли задача игрока. */
	public boolean hasRole(UUID player, TaskEntry entry) {
		return entry.character().isEmpty() || roles.isEmpty() || entry.character().get().equals(roles.get(player));
	}

	/** Играет ли кто-нибудь персонажем задачи; если нет, она пропускается: одному игроку не сделать дела за двоих. */
	private boolean anyoneFor(TaskEntry entry) {
		return entry.character().isEmpty() || roles.isEmpty() || roles.containsValue(entry.character().get());
	}

	private List<UUID> onlineFor(TaskEntry entry) {
		return online().stream().filter(id -> hasRole(id, entry)).toList();
	}

	/** Прогон шёл с принудительными пропусками: статистика его не берёт. */
	public boolean forced() {
		return forced;
	}

	/**
	 * Принудительно завершает уровень (команда оператора): все задачи считаются выполненными, события завершения идут как обычно ({@code level_completed}:
	 * итоговая кат-сцена, откат областей начатых миссий), но события начала и конца пропущенных миссий не рассылаются, чтобы не запускать их волны и обстрелы.
	 * Время прогона в статистику не идёт.
	 */
	public boolean forceComplete() {
		if (state != State.RUNNING) return false;
		forced = true;
		for (MissionEntry mission : definition.missions()) {
			for (TaskEntry entry : mission.tasks()) {
				Progress progress = tasks.get(entry.id());
				progress.started = true;
				progress.completed = true;
				progress.missed = false;
			}
		}
		changed = true;
		releaseHolds();
		state = State.COMPLETED;
		restoreAll();
		dismissCompanions();
		fire(LevelEvent.LEVEL_COMPLETED);
		return true;
	}

	/**
	 * Пропускает текущую миссию (команда оператора): её задачи считаются выполненными обычным путём (события, триггеры), дальше всё идёт как при прохождении:
	 * следующая миссия начинается. Время прогона в статистику не идёт.
	 *
	 * @return {@code false}, если уровень не идёт или миссии нет
	 */
	public boolean skipMission() {
		MissionEntry mission = currentMission();
		if (mission == null) return false;
		forced = true;
		for (TaskEntry entry : mission.tasks()) {
			Progress progress = tasks.get(entry.id());
			if (progress.completed) continue;
			progress.started = true;
			progress.missed = false;
			complete(entry, progress);
		}
		changed = true;
		settle();
		return true;
	}

	public void abandon() {
		if (state != State.GATHERING && state != State.RUNNING) return;
		boolean started = state == State.RUNNING;
		state = State.ABANDONED;
		releaseHolds();
		restoreAll();
		dismissCompanions();
		if (started) fire(LevelEvent.LEVEL_ABANDONED);
	}

	public void tick() {
		if (state != State.RUNNING || online().isEmpty()) return;
		elapsed++;
		runDelayed();
		tickCutscenes();
		for (TaskEntry entry : definition.tasks()) {
			Progress progress = tasks.get(entry.id());
			if (progress.active()) entry.task().tick(new Tracker(entry, progress));
		}
		settle();
		// снимок начатой миссии, если в её начале чанки области ещё не загрузились (игрока только переносят к ней)
		MissionEntry current = currentMission();
		if (state == State.RUNNING && current != null && !snapshots.containsKey(current.id())) takeSnapshot(current);
		if (state == State.RUNNING) checkWatches();
		if (state == State.RUNNING) checkAreas();
		if (state == State.RUNNING) checkCheckpoints();
	}

	/** Наблюдатели: первый участник, у которого разом верны все проверки, порождает их событие. */
	private void checkWatches() {
		for (Watch watch : definition.watches()) {
			if (happened.contains(watch.event()) || armedWatches.contains(watch.id()) || watch.after().isPresent() && !happened.contains(watch.after().get())) continue;
			for (UUID id : online()) {
				if (!watch.conditions().stream().allMatch(c -> host.check(id, c.check(), c.params()))) continue;
				if (watch.delay() > 0) {
					armedWatches.add(watch.id());
					delayed.add(new Delayed(elapsed + watch.delay(), List.of(new LevelAction.Emit(watch.id())), List.of()));
				} else {
					fire(watch.event());
				}
				break;
			}
			if (state != State.RUNNING) return;
		}
	}

	/** Задача начинается заново по приказу триггера: как при ошибке, только касается всех её игроков. */
	private void failTask(String task) {
		TaskEntry entry = definition.task(task);
		Progress progress = entry == null ? null : tasks.get(task);
		if (progress == null || !progress.active()) return;
		fire(LevelEvent.taskFailed(task));
		progress.shared = 0;
		progress.credits.clear();
		progress.marks.clear();
		fire(LevelEvent.taskReset(task));
		List<UUID> back = inSharedMission(entry) ? online() : onlineFor(entry);
		for (MissionEntry mission : definition.missions()) if (mission.tasks().contains(entry)) restore(mission, back);
		back.forEach(this::sendBack);
	}

	/** Снимок подвижного в области миссии; не загружены чанки — останется без снимка, {@code tick} попробует снова. */
	private void takeSnapshot(MissionEntry mission) {
		if (mission.areas().isEmpty()) return;
		CompoundTag tag = snapshotAreas(mission, startCells(mission));
		if (tag != null) snapshots.put(mission.id(), tag);
	}

	/**
	 * Снимок всех областей миссии: у первой — плоский, как всегда, у остальных — вложенный {@code area<N>} в нём же (снимки прежних миссий читаются как есть).
	 * Ячейки отката берутся в каждой области. Чанки одной из областей не загружены — {@code null}.
	 */
	private @Nullable CompoundTag snapshotAreas(MissionEntry mission, List<ResetCell> cells) {
		CompoundTag result = null;
		for (int i = 0; i < mission.areas().size(); i++) {
			TaskArea area = mission.areas().get(i);
			CompoundTag part = host.saveSnapshot(area);
			if (part == null) return null;
			if (!cells.isEmpty()) part.put(CELLS, host.saveCells(area, cells));
			if (i == 0) result = part;
			else result.put(AREA_PART + i, part);
		}
		return result;
	}

	/** Ключ снимка второй и следующих областей миссии. */
	private static final String AREA_PART = "area";

	/** Ключ ячеек отката в снимке. */
	private static final String CELLS = "cells";

	/** Ячейки первых чекпоинтов ролей (начало миссии — это первый чекпоинт каждого игрока): без повторов. */
	private List<ResetCell> startCells(MissionEntry mission) {
		java.util.LinkedHashSet<ResetCell> cells = new java.util.LinkedHashSet<>();
		java.util.Set<Identifier> roleSet = new java.util.LinkedHashSet<>(roles.values());
		if (roleSet.isEmpty()) roleSet.add(null);
		for (Identifier role : roleSet) {
			List<Checkpoint> own = mission.checkpointsFor(role);
			if (!own.isEmpty()) cells.addAll(own.getFirst().resets());
		}
		return List.copyOf(cells);
	}

	/**
	 * Подвижное в области миссии на момент входа игрока в очередной чекпоинт (не первый: первый — начало миссии, его снимает {@link #takeSnapshot}).
	 * Ключ {@code <миссия>#<номер чекпоинта в списке миссии>@<игрок>}; у игрока хранится один, последний. Нет снимка (чанки области не загружены) — сброс
	 * вернёт снимок начала миссии.
	 */
	private void takeCheckpointSnapshot(MissionEntry mission, UUID player, com.smeshariki.level.task.Checkpoint checkpoint) {
		if (mission.areas().isEmpty()) return;
		CompoundTag tag = snapshotAreas(mission, checkpoint.resets());
		if (tag == null) return;
		String prefix = mission.id() + "#", suffix = "@" + player;
		snapshots.keySet().removeIf(k -> k.startsWith(prefix) && k.endsWith(suffix));
		snapshots.put(prefix + mission.checkpoints().indexOf(checkpoint) + suffix, tag);
	}

	/**
	 * Снимок, к которому вернуть область при сбросе: у вернувшихся игроков самый ранний из снимков их последних чекпоинтов (никого не перепрыгиваем вперёд);
	 * ни у кого нет — снимок начала миссии.
	 */
	private @Nullable CompoundTag snapshotFor(MissionEntry mission, java.util.Collection<UUID> players) {
		String best = null;
		int bestIndex = Integer.MAX_VALUE;
		String prefix = mission.id() + "#";
		for (UUID player : players) {
			for (String key : snapshots.keySet()) {
				if (!key.startsWith(prefix) || !key.endsWith("@" + player)) continue;
				int index = Integer.parseInt(key.substring(prefix.length(), key.indexOf('@')));
				if (index < bestIndex) {
					bestIndex = index;
					best = key;
				}
			}
		}
		return best != null ? snapshots.get(best) : snapshots.get(mission.id());
	}

	/** Когда (тик прогона) постройки миссии последний раз возвращались: сброс задач обоих игроков за один тик возвращает их и сообщает об этом один раз. */
	private final Map<String, Long> restoredAt = new HashMap<>();

	/** Области миссии — как в её начале (или на чекпоинте): подвижное из снимка, блоки к версии мира. */
	private void restore(MissionEntry mission, java.util.Collection<UUID> players) {
		if (mission.areas().isEmpty() || Long.valueOf(elapsed).equals(restoredAt.put(mission.id(), elapsed))) return;
		CompoundTag snapshot = snapshotFor(mission, players);
		for (int i = 0; i < mission.areas().size(); i++) {
			TaskArea area = mission.areas().get(i);
			CompoundTag part = snapshot == null ? null : i == 0 ? snapshot : snapshot.getCompound(AREA_PART + i).orElse(null);
			if (part != null) host.restoreSnapshot(area.dimension(), part);
			host.resetBlocks(area);
			// ячейки — после блоков по версии мира (их возврат ждёт конца версии)
			if (part != null) part.getCompound(CELLS).ifPresent(cells -> host.restoreCells(area, cells));
		}
		if (state == State.RUNNING) fire(LevelEvent.missionReset(mission.id()));
	}

	/** Конец уровня: области начатых миссий возвращаются, чтобы уровень можно было пройти снова. */
	private void restoreAll() {
		for (MissionEntry mission : definition.missions()) if (happened.contains(LevelEvent.missionStarted(mission.id()))) restore(mission, List.of());
		snapshots.clear();
	}

	/**
	 * Вышедшему за границу идёт отсчёт, вернувшемуся он сбрасывается, а не успевшему сбрасываются задачи с границей: провал, новая попытка с начала
	 * задачи. Уровень от этого не проваливается. Несколько задач идут сразу — хватает границы любой из них.
	 */
	private void checkAreas() {
		for (UUID id : online()) {
			List<TaskEntry> bounded = new ArrayList<>();
			for (TaskEntry entry : definition.tasks()) if (tasks.get(entry.id()).active() && entry.area().isPresent() && hasRole(id, entry)) bounded.add(entry);
			List<TaskArea> areas = bounded.stream().map(entry -> entry.area().orElseThrow()).toList();
			if (areas.isEmpty()) {
				outside.remove(id);
				continue;
			}
			if (areas.stream().anyMatch(area -> isInside(id, area))) {
				if (outside.remove(id) != null) host.backInArea(this, id);
				continue;
			}
			int ticks = outside.merge(id, 1, Integer::sum);
			if (ticks >= AREA_GRACE_TICKS) {
				for (TaskEntry entry : bounded) {
					fire(LevelEvent.taskFailed(entry.id()));
					reset(entry, id);
				}
				sendBackAfter(bounded, id);
				continue;
			}
			if ((ticks - 1) % 20 == 0) host.outsideArea(this, id, (AREA_GRACE_TICKS - ticks + 19) / 20);
		}
	}

	private boolean isInside(UUID player, TaskArea area) {
		Vec3 pos = host.position(player);
		return pos != null && area.dimension().equals(host.dimension(player)) && area.contains(pos);
	}

	/**
	 * Игрок погиб: его идущие задачи начинаются заново, а сам он возвращается на самый дальний достигнутый чекпоинт миссии, чтобы новый персонаж не продолжил с места
	 * падения. Если свою часть миссии он уже сделал, а другие ещё нет, он возвращается туда же и стоит, пока миссию не закончат все.
	 */
	public void onDeath(UUID player) {
		if (state != State.RUNNING) return;
		List<TaskEntry> affected = new ArrayList<>();
		for (TaskEntry entry : definition.tasks()) {
			if (!tasks.get(entry.id()).active() || !hasRole(player, entry)) continue;
			affected.add(entry);
			reset(entry, player);
		}
		if (!affected.isEmpty()) {
			sendBackAfter(affected, player);
			return;
		}
		MissionEntry mission = currentMission();
		StartPoint point = mission != null && finishedMission(player, mission) ? checkpointPoint(player) : null;
		if (point == null) {
			sendBack(player);
			return;
		}
		outside.remove(player);
		changed = true;
		host.returnTo(this, player, point);
		held.add(player);
		host.hold(player, true);
	}

	/** Все обязательные задачи миссии, которые касаются игрока, у него выполнены. */
	private boolean finishedMission(UUID player, MissionEntry mission) {
		for (TaskEntry entry : mission.tasks()) {
			if (entry.optional() || !hasRole(player, entry)) continue;
			Progress progress = tasks.get(entry.id());
			if (progress.completed) continue;
			if (entry.scope() == TaskScope.EACH && progress.shared + progress.credits.getOrDefault(player, 0) >= entry.task().goal()) continue;
			return false;
		}
		return true;
	}

	/** Кто закончил миссию раньше других, погиб и вернулся на место: стоит до конца миссии (только в памяти, как и сам возврат). */
	private final Set<UUID> held = new HashSet<>();

	/** Миссия закончена (или уровень брошен): стоявшие в ожидании напарников снова свободны. */
	private void releaseHolds() {
		for (UUID id : List.copyOf(held)) host.hold(id, false);
		held.clear();
	}

	/** Область, до которой игроку сейчас идти: первая идущая задача «добраться до области» его персонажа, которую он ещё не засчитал; {@code null} — такой задачи нет. */
	public @Nullable TaskArea reachTarget(UUID player) {
		if (state != State.RUNNING) return null;
		for (TaskEntry entry : definition.tasks()) {
			Progress progress = tasks.get(entry.id());
			if (!progress.active() || !hasRole(player, entry) || !(entry.task() instanceof ReachAreaTask reach)) continue;
			if (progress.shared + progress.credits.getOrDefault(player, 0) >= entry.task().goal()) continue;
			return reach.target();
		}
		return null;
	}

	/** Общая миссия: в ней нет задач для отдельных персонажей, всё делается вместе, поэтому и ошибка одного возвращает всех. */
	private boolean inSharedMission(TaskEntry entry) {
		for (MissionEntry mission : definition.missions()) {
			if (mission.tasks().contains(entry)) return mission.tasks().stream().allMatch(task -> task.character().isEmpty());
		}
		return false;
	}

	private void sendBackAfter(List<TaskEntry> affected, UUID player) {
		List<UUID> back = affected.stream().anyMatch(this::inSharedMission) ? online() : List.of(player);
		for (MissionEntry mission : definition.missions()) if (affected.stream().anyMatch(mission.tasks()::contains)) restore(mission, back);
		if (affected.stream().anyMatch(this::inSharedMission)) online().forEach(this::sendBack);
		else sendBack(player);
	}

	/** В своей задаче «каждый сам» сбрасывается только этот игрок, напарник своего не теряет. Иначе обнуляется целиком. */
	private void reset(TaskEntry entry, UUID player) {
		Progress progress = tasks.get(entry.id());
		if (entry.scope() == TaskScope.EACH && !inSharedMission(entry)) {
			progress.credits.remove(player);
			progress.marks.remove(player);
		} else {
			progress.shared = 0;
			progress.credits.clear();
			progress.marks.clear();
		}
		fire(LevelEvent.taskReset(entry.id()));
	}

	private void sendBack(UUID player) {
		outside.remove(player);
		changed = true;
		StartPoint point = checkpointPoint(player);
		if (point == null) return;
		host.returnTo(this, player, point);
		MissionEntry mission = currentMission();
		if (mission == null) return;
		List<Checkpoint> own = mission.checkpointsFor(roles.get(player));
		Checkpoint checkpoint = own.get(Math.min(checkpoints.getOrDefault(player, 0), own.size() - 1));
		int number = mission.checkpoints().indexOf(checkpoint) + 1;
		// возврат нескольких игроков на один чекпоинт за тик — одно событие
		if (!Long.valueOf(elapsed).equals(checkpointResetAt.put(mission.id() + ":" + number, elapsed))) fire(LevelEvent.checkpointReset(mission.id(), number));
		resumeNumbers.add(number);
		// у чекпоинта своё ожидание, нет его — ожидание миссии
		int wait = checkpoint.resetWait().orElse(mission.resetWait());
		if (wait > 0) {
			host.freeze(player, wait * 20);
			waiting.add(player);
		} else if (waiting.isEmpty()) {
			// без ожидания: можно сразу продолжать
			resume(mission);
		}
	}

	/** Номера чекпоинтов, на которые вернули игроков, пока шло ожидание: когда оно кончится, для каждого придёт своё событие. */
	private final java.util.Set<Integer> resumeNumbers = new java.util.TreeSet<>();
	/** Когда (тик прогона) событие возобновления чекпоинта последний раз приходило, по {@code миссия:номер}. */
	private final Map<String, Long> checkpointResumedAt = new HashMap<>();

	/** Ожидание кончилось (или его не было): миссия возобновлена и каждый чекпоинт, на который вернули игроков, тоже. */
	private void resume(MissionEntry mission) {
		// придержанное возвратом (волны лавы) идёт дальше до событий: триггеры на них видят уже живую волну
		host.resumeSnapshots();
		if (!Long.valueOf(elapsed).equals(resumedAt.put(mission.id(), elapsed))) fire(LevelEvent.missionResumed(mission.id()));
		for (int number : List.copyOf(resumeNumbers)) {
			if (!Long.valueOf(elapsed).equals(checkpointResumedAt.put(mission.id() + ":" + number, elapsed))) fire(LevelEvent.checkpointResumed(mission.id(), number));
		}
		resumeNumbers.clear();
	}

	/** Когда (тик прогона) событие отката на чекпоинт последний раз приходило. */
	private final Map<String, Long> checkpointResetAt = new HashMap<>();

	/** Кто из вернувшихся ещё стоит на отсчёте (только в памяти: это секунды). */
	private final java.util.Set<UUID> waiting = new java.util.HashSet<>();
	/** Когда (тик прогона) событие возобновления миссии последний раз приходило: возврат нескольких игроков за тик сообщает об этом один раз. */
	private final Map<String, Long> resumedAt = new HashMap<>();

	/** Отсчёт ожидания игрока кончился (или он вышел): когда ждать больше некому, миссия возобновлена. */
	public void waitFinished(UUID player) {
		if (!waiting.remove(player) || !waiting.isEmpty() || state != State.RUNNING) return;
		MissionEntry mission = currentMission();
		if (mission != null) resume(mission);
	}

	/** Клик по свободному персонажу: true, если этого разговора ждёт идущая задача, и тогда клик не должен делать ничего другого. */
	public boolean talk(UUID player, Identifier character) {
		if (state != State.RUNNING) return false;
		boolean wanted = false;
		for (TaskEntry entry : definition.tasks()) {
			wanted |= tasks.get(entry.id()).active() && hasRole(player, entry) && entry.task() instanceof TalkTask talk && talk.character().equals(character);
		}
		if (wanted) signal(new TaskSignal.TalkedTo(player, character));
		return wanted;
	}

	public void signal(TaskSignal signal) {
		if (state != State.RUNNING) return;
		for (TaskEntry entry : definition.tasks()) {
			Progress progress = tasks.get(entry.id());
			if (progress.active() && hasRole(signal.player(), entry)) entry.task().onSignal(signal, new Tracker(entry, progress));
		}
		settle();
	}

	private void runDelayed() {
		List<Delayed> due = delayed.stream().filter(d -> d.due() <= elapsed).toList();
		delayed.removeAll(due);
		for (Delayed d : due) for (LevelAction action : d.actions()) perform(action);
	}

	/** Сцену показали до конца или пропустили: событие конца приходит сразу, не дожидаясь таймера. */
	public void cutsceneEnded(Identifier cutscene) {
		if (cutscenes.remove(cutscene) != null) fire(LevelEvent.cutsceneFinished(cutscene));
	}

	private void tickCutscenes() {
		for (Identifier cutscene : List.copyOf(cutscenes.keySet())) {
			int left = cutscenes.get(cutscene) - 1;
			if (left > 0) {
				cutscenes.put(cutscene, left);
			} else {
				cutscenes.remove(cutscene);
				fire(LevelEvent.cutsceneFinished(cutscene));
			}
		}
	}

	/** Завершает выполненные задачи и ведёт миссии по порядку: начатую можно закончить, только когда в ней готово всё. */
	private void settle() {
		if (state != State.RUNNING) return;
		for (TaskEntry entry : definition.tasks()) {
			Progress progress = tasks.get(entry.id());
			if (progress.active() && isDone(entry, progress)) complete(entry, progress);
		}
		for (MissionEntry mission : definition.missions()) {
			if (happened.add(LevelEvent.missionStarted(mission.id()))) {
				takeSnapshot(mission);
				mission.areas().forEach(host::prepareBlocks);
				gatherAtStart(mission);
				// напарник, оставленный ждать или со стропой в руках в прошлой миссии, снова идёт за игроком
				if (!companions.isEmpty() && !online().isEmpty()) for (Identifier id : companions) host.orderCompanion(id, online().getFirst(), FOLLOW, new MechanicParams(new com.google.gson.JsonObject()));
				fire(LevelEvent.missionStarted(mission.id()));
				if (waitAtStart && mission.resetWait() > 0 && !online().isEmpty()) {
					resumeNumbers.add(1);
					// как после сброса: отсчёт, потом возобновление; начало миссии для правил — уже «сброс», поэтому ожидающее «через N с после начала» отменяется
					for (UUID id : online()) {
						host.startWait(id, mission.resetWait() * 20);
						waiting.add(id);
					}
					fire(LevelEvent.missionReset(mission.id()));
				}
				for (TaskEntry entry : mission.tasks()) {
					Progress progress = tasks.get(entry.id());
					if (anyoneFor(entry) || progress.completed) continue;
					progress.started = true;
					complete(entry, progress);
				}
			}
			// необязательные миссию не держат
			for (TaskEntry entry : mission.tasks()) if (!entry.optional() && !tasks.get(entry.id()).completed) return;
			if (happened.add(LevelEvent.missionCompleted(mission.id()))) {
				for (TaskEntry entry : mission.tasks()) {
					Progress progress = tasks.get(entry.id());
					if (progress.completed) continue;
					progress.missed = true;
					changed = true;
				}
				releaseHolds();
				fire(LevelEvent.missionCompleted(mission.id()));
			}
		}
		state = State.COMPLETED;
		restoreAll();
		dismissCompanions();
		fire(LevelEvent.LEVEL_COMPLETED);
	}

	private void complete(TaskEntry entry, Progress progress) {
		progress.completed = true;
		fire(LevelEvent.taskCompleted(entry.id()));
	}

	private boolean isDone(TaskEntry entry, Progress progress) {
		int goal = entry.task().goal();
		if (entry.scope() == TaskScope.TEAM) return progress.shared + credited(progress) >= goal;
		// вышедший из сети не держит остальных
		List<UUID> online = onlineFor(entry);
		return !online.isEmpty() && online.stream().allMatch(id -> progress.shared + progress.credits.getOrDefault(id, 0) >= goal);
	}

	private static int credited(Progress progress) {
		return progress.credits.values().stream().mapToInt(Integer::intValue).sum();
	}

	private void fire(LevelEvent event) {
		happened.add(event);
		changed = true;
		delayed.removeIf(d -> d.unless().contains(event));
		host.publish(this, event);
		List<Trigger> triggers = definition.triggers();
		for (int i = 0; i < triggers.size(); i++) {
			Trigger trigger = triggers.get(i);
			if (!happened.containsAll(trigger.when()) || !inScope(trigger, event)) continue;
			if (trigger.repeat()) {
				// повторяемый ловит каждое новое случение своего события; своё же событие изнутри его не перезапускает, иначе цикл без конца
				if (!trigger.when().contains(event) || !repeating.add(i)) continue;
			} else {
				if (fired.contains(i)) continue;
				fired.add(i);
			}
			try {
				if (trigger.delay() > 0) delayed.add(new Delayed(elapsed + trigger.delay(), trigger.then(), trigger.unless()));
				else for (LevelAction action : trigger.then()) perform(action);
			} finally {
				if (trigger.repeat()) repeating.remove(i);
			}
		}
	}

	/**
	 * Триггер миссии идёт, только пока миссия началась и не закончилась (своё событие конца ещё доходит): события и действия миссии не просачиваются в
	 * другие. Триггеры уровня идут всегда.
	 */
	private boolean inScope(Trigger trigger, LevelEvent event) {
		String owner = definition.triggerOwner(trigger);
		if (owner.isEmpty()) return true;
		if (!happened.contains(LevelEvent.missionStarted(owner))) return false;
		return !happened.contains(LevelEvent.missionCompleted(owner)) || event.equals(LevelEvent.missionCompleted(owner));
	}

	/** Событие пришло извне прогона (механики GameplayMod): учитывается, только пока уровень идёт. */
	public void external(LevelEvent event) {
		if (state == State.RUNNING) fire(event);
	}

	private void perform(LevelAction action) {
		switch (action) {
			case LevelAction.StartTask(String task) -> {
				Progress progress = tasks.get(task);
				if (progress == null || progress.started || progress.missed) return;
				progress.started = true;
				fire(LevelEvent.taskStarted(task));
			}
			case LevelAction.PlayCutscene(Identifier cutscene) -> {
				cutscenes.put(cutscene, host.cutsceneDuration(cutscene));
				fire(LevelEvent.cutsceneStarted(cutscene));
				host.playCutscene(this, cutscene);
			}
			case LevelAction.Emit(String event) -> fire(LevelEvent.custom(event));
			case LevelAction.FailTask(String task) -> failTask(task);
			case LevelAction.Order(var call) -> order(call, null);
			case LevelAction.Say(Component text, boolean overlay, var speaker) -> {
				// реплика персонажа в чате подписана его псевдонимом; без персонажа — просто текст
				Component line = speaker.isPresent() && !overlay ? com.smeshariki.level.definition.Speech.line(speaker.get(), text) : text;
				for (UUID id : online()) host.tell(id, line, overlay);
			}
			case LevelAction.Mechanic(Identifier id, var args) -> {
				MechanicParams params = new MechanicParams(args);
				Mechanics.Toggle toggle = Mechanics.toggle(id);
				if (toggle == null) {
					host.runMechanic(this, id, params);
					return;
				}
				String key = toggle.keyParam() == null ? "" : params.string(toggle.keyParam(), "");
				Set<String> keys = toggles.computeIfAbsent(id.toString(), k -> new HashSet<>());
				if (params.bool("on", true)) keys.add(key);
				else keys.remove(key);
				if (keys.isEmpty()) toggles.remove(id.toString());
				changed = true;
			}
		}
	}

	/** Сколько сделано по задаче; {@link TaskScope#EACH} показывает отстающего. */
	public int progressOf(TaskEntry entry) {
		Progress progress = tasks.get(entry.id());
		int done = progress.shared;
		if (entry.scope() == TaskScope.TEAM) done += credited(progress);
		else done += onlineFor(entry).stream().mapToInt(id -> progress.credits.getOrDefault(id, 0)).min().orElse(0);
		return Math.min(done, entry.task().goal());
	}

	/** Области идущих задач: игрок должен быть в одной из них. */
	public List<TaskArea> activeAreas() {
		List<TaskArea> areas = new ArrayList<>();
		for (TaskEntry entry : definition.tasks()) if (tasks.get(entry.id()).active()) entry.area().ifPresent(areas::add);
		return areas;
	}

	public boolean isStarted(TaskEntry entry) {
		return tasks.get(entry.id()).started;
	}

	/** Необязательная задача закрыта невыполненной: миссия кончилась без неё. */
	public boolean isMissed(TaskEntry entry) {
		return tasks.get(entry.id()).missed;
	}

	public boolean isCompleted(TaskEntry entry) {
		return tasks.get(entry.id()).completed;
	}

	private final class Tracker implements TaskTracker {
		private final TaskEntry entry;
		private final Progress progress;

		Tracker(TaskEntry entry, Progress progress) {
			this.entry = entry;
			this.progress = progress;
		}

		@Override
		public List<UUID> players() {
			return onlineFor(entry);
		}

		@Override
		public @Nullable Vec3 position(UUID player) {
			return host.position(player);
		}

		@Override
		public boolean isInside(UUID player, TaskArea area) {
			return LevelRun.this.isInside(player, area);
		}

		@Override
		public int gained(UUID player, java.util.function.Predicate<net.minecraft.world.item.ItemStack> match) {
			int count = host.countItem(player, match);
			return Math.max(0, count - progress.baselines.computeIfAbsent(player, id -> count));
		}

		@Override
		public boolean check(UUID player, com.smeshariki.level.task.ConditionEntry condition) {
			return host.check(player, condition.check(), condition.params());
		}

		@Override
		public int marks(UUID player) {
			return progress.marks.getOrDefault(player, 0);
		}

		@Override
		public void setMarks(UUID player, int marks) {
			if (marks == 0) progress.marks.remove(player);
			else if (!Integer.valueOf(marks).equals(progress.marks.put(player, marks))) changed = true;
		}

		@Override
		public void say(UUID player, Component message) {
			host.say(player, message);
		}

		@Override
		public void setCredit(UUID player, int amount) {
			int value = Math.min(entry.task().goal(), amount);
			if (progress.credits.getOrDefault(player, 0) == value) return;
			progress.credits.put(player, value);
			changed = true;
		}

		@Override
		public void credit(UUID player, int amount) {
			int goal = entry.task().goal();
			int before = progress.credits.getOrDefault(player, 0);
			if (before >= goal) return;
			progress.credits.put(player, Math.min(goal, before + amount));
			changed = true;
		}

		@Override
		public void advance(int amount) {
			progress.shared += amount;
			changed = true;
		}
	}
}
