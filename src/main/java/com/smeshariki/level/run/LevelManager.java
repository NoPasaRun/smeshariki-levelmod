package com.smeshariki.level.run;

import com.smeshariki.behavior.cutscene.Cutscenes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.UUID;

import com.smeshariki.behavior.companion.Companions;
import com.smeshariki.behavior.dialog.Dialogs;
import com.smeshariki.behavior.order.Orders;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.gameplay.character.Singletons;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.definition.LevelDefinition;
import com.smeshariki.level.definition.LevelFiles;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.definition.LevelKind;
import com.smeshariki.level.definition.LevelPreview;
import com.smeshariki.level.definition.Levels;
import com.smeshariki.level.definition.Trigger;
import com.smeshariki.level.net.LevelCommand;
import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.net.LevelLocationEdit;
import com.smeshariki.level.net.LevelSave;
import com.smeshariki.level.net.LevelSaveResult;
import com.smeshariki.level.net.LevelSwapCharacter;
import com.smeshariki.level.net.LevelTestTask;
import com.smeshariki.level.task.MissionEntry;
import com.smeshariki.level.task.TaskArea;
import com.smeshariki.level.task.TaskEntry;
import com.smeshariki.level.task.TaskSignal;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;

/** Связь прогонов с сервером: тик, игровые действия, кнопки игроков, редактор, рассылка списка уровней и сообщения участникам. */
public final class LevelManager {
	/** Как часто рассылать изменения: прогресс таймера меняется каждый тик. */
	private static final int SYNC_INTERVAL = 10;

	/** Сколько побочных уровней можно вести одновременно. */
	public static final int MAX_SIDE_LEVELS = 3;
	/** Причина отказа, при которой окно предлагает сменить персонажа. */
	public static final String REFUSE_CHARACTER_SWAP = "message.levelmod.refuse.character_swap";

	private static boolean syncNeeded;
	/** Кем был игрок в прошлый тик: только чтобы заметить смену. */
	private static final Map<UUID, Identifier> CHARACTERS = new HashMap<>();
	/**
	 * Проверки задач из редактора. Не сохраняются: это не игра, а инструмент, и после перезапуска её просто запускают снова. Статистики и
	 * отката у них нет. Идут как настоящая игра: выживание, выбор персонажа, только его задачи.
	 */
	private static final Map<UUID, LevelRun> TESTS = new HashMap<>();
	/** Какой режим был у игрока до проверки: она идёт в выживании, как настоящая игра, а потом режим возвращается. */
	private static final Map<UUID, GameType> TEST_MODES = new HashMap<>();
	private static final Identifier TEST_LEVEL = Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "test/task");
	/** Кого вернуть к началу задачи после гибели: погибший игрок ждёт возрождения. */
	private static final Map<UUID, LevelRun.StartPoint> RETURNS = new HashMap<>();
	/** Насколько далеко от центра области искать землю без ствола и воды. */
	private static final int GROUND_SEARCH = 12;
	/** Пол у подсказки высоты (старт-область под землёй): на столько выше и ниже неё ищем. */
	private static final int FLOOR_UP = 4, FLOOR_DOWN = 12;

	private LevelManager() {
	}

	public static void init() {
		initCompanions();
		initSingletons();
		initGear();
		// откат версии из окна V идёт тем же движком, что откат после уровня: без остановки мира
		com.smeshariki.mod.versions.WorldVersions.liveRollback(WorldReset::beginManual);
		PayloadTypeRegistry.serverboundPlay().register(LevelCommand.TYPE, LevelCommand.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LevelSave.TYPE, LevelSave.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LevelList.TYPE, LevelList.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LevelSaveResult.TYPE, LevelSaveResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.smeshariki.level.net.ScreenFade.TYPE, com.smeshariki.level.net.ScreenFade.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.smeshariki.level.net.ResetWait.TYPE, com.smeshariki.level.net.ResetWait.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.smeshariki.level.net.GuideTarget.TYPE, com.smeshariki.level.net.GuideTarget.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(com.smeshariki.level.net.HoldWait.TYPE, com.smeshariki.level.net.HoldWait.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(com.smeshariki.level.net.SpectateRequest.TYPE, com.smeshariki.level.net.SpectateRequest.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(com.smeshariki.level.net.SpectateRequest.TYPE, (msg, ctx) -> toggleSpectating(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(LevelCommand.TYPE, (msg, ctx) -> handle(ctx.player(), msg));
		ServerPlayNetworking.registerGlobalReceiver(LevelSave.TYPE, (msg, ctx) -> save(ctx.player(), msg));
		PayloadTypeRegistry.serverboundPlay().register(LevelLocationEdit.TYPE, LevelLocationEdit.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LevelLocationEdit.TYPE, (msg, ctx) -> editLocation(ctx.player(), msg));
		PayloadTypeRegistry.serverboundPlay().register(com.smeshariki.level.net.LevelGroupEdit.TYPE, com.smeshariki.level.net.LevelGroupEdit.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(com.smeshariki.level.net.LevelGroupEdit.TYPE, (msg, ctx) -> editGroup(ctx.player(), msg));
		PayloadTypeRegistry.serverboundPlay().register(LevelTestTask.TYPE, LevelTestTask.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LevelTestTask.TYPE, (msg, ctx) -> test(ctx.player(), msg));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) onDeath(player);
			else if (entity instanceof CharacterEntity character && character.getControllingPassenger() instanceof ServerPlayer rider) {
				rider.stopRiding();
				onDeath(rider);
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer) || !(entity instanceof CharacterEntity character)) return InteractionResult.PASS;
			return talkTo(serverPlayer, character) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		PayloadTypeRegistry.serverboundPlay().register(LevelSwapCharacter.TYPE, LevelSwapCharacter.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LevelSwapCharacter.TYPE, (msg, ctx) -> swapCharacter(ctx.player(), msg));
		// пока идёт уровень, вместо погибшего персонажа вернётся он же: от этого зависят задачи игрока
		Possession.setChoiceFilter((player, type) -> {
			LevelRun test = TESTS.get(player.getUUID());
			if (test != null && !testRoles(test).isEmpty() && !testRoles(test).contains(EntityType.getKey(type))) return false;
			for (LevelRun run : LevelStore.get(player.level().getServer()).runsWith(player.getUUID())) {
				Levels.Loaded loaded = Levels.get(run.level());
				if (run.state() != LevelRun.State.RUNNING || loaded == null) continue;
				Identifier role = run.roleOf(player.getUUID());
				if (role != null ? !role.equals(EntityType.getKey(type)) : !loaded.definition().allowsCharacter(EntityType.getKey(type))) return false;
			}
			return true;
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			recoverSpectator(server, handler.player);
			ServerPlayNetworking.send(handler.player, list(server, handler.player));
		});
		// вышедший из сети покидает собираемую группу, иначе лидером мог бы остаться тот, кого нет; из идущего прогона не выбывает
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			WAITS.remove(handler.player.getUUID());
			FROZEN.remove(handler.player.getUUID());
			HOLD_PENDING.remove(handler.player.getUUID());
			HOLDS.remove(handler.player.getUUID());
			// в режиме наблюдателя игрока сохранять нельзя: войдёт в мир наблюдателем и без прогона, который вернул бы его
			stopSpectating(server, handler.player.getUUID(), false);
			waitFinished(server, handler.player.getUUID());
			LevelRun leaving = TESTS.remove(handler.player.getUUID());
			// постройки миссии (лава, плоты, шар) — как были до проверки
			if (leaving != null) leaving.abandon();
			if (leaving != null) {
				restoreMode(handler.player, handler.player.getUUID());
				syncNeeded = true;
			}
			for (LevelRun run : LevelStore.get(server).runsWith(handler.player.getUUID())) {
				if (run.state() != LevelRun.State.GATHERING) continue;
				leave(run, handler.player.getUUID());
				syncNeeded = true;
			}
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> syncNeeded = true);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			AreaReset.clear();
			ResetCells.clear();
			resumeServer = null;
			AreaCleanup.clear();
			WorldReset.clear();
			for (UUID id : List.copyOf(SPECTATORS.keySet())) stopSpectating(server, id, false);
			HOLD_PENDING.clear();
			HOLDS.clear();
			for (UUID id : List.copyOf(TEST_MODES.keySet())) restoreMode(server.getPlayerList().getPlayer(id), id);
			TESTS.values().forEach(LevelRun::abandon);
			TESTS.clear();
			RETURNS.clear();
			FADE_RETURNS.clear();
			WAITS.clear();
			FROZEN.clear();
			GUIDES.clear();
			GUIDE_REACHED.clear();
			CHARACTERS.clear();
			SPECTATE_COOLDOWN.clear();
		});
		// событие механики другого мода: всем идущим прогонам, ловят его их триггеры
		Mechanics.EVENTS.register((server, event) -> {
			List<LevelRun> runs = new ArrayList<>(LevelStore.get(server).runs());
			runs.addAll(TESTS.values());
			for (LevelRun run : runs) run.external(LevelEvent.external(event));
		});
		ServerTickEvents.START_SERVER_TICK.register(LevelManager::attachRuns);
		ServerTickEvents.END_SERVER_TICK.register(LevelManager::tick);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer) signal(serverPlayer, new TaskSignal.BlockMined(player.getUUID(), state.getBlock()));
		});
	}

	/** Метка призраков, которые были раньше напарников: в старых мирах ещё могут стоять. */
	private static final String OLD_GHOST_TAG = "levelmod_ghost";
	/** Метка напарников-копий до того, как смешарик стал единственным: такие копии лишние. */
	private static final String OLD_COMPANION_TAG = "levelmod_companion";

	/** Тип смешарика по идентификатору; {@code null} — не смешарик. */
	static @org.jetbrains.annotations.Nullable EntityType<CharacterEntity> characterType(Identifier id) {
		for (EntityType<CharacterEntity> known : com.smeshariki.gameplay.character.Characters.all()) if (EntityType.getKey(known).equals(id)) return known;
		return null;
	}

	/** Дома смешариков для возрождения после гибели вне уровня — локации уровней с этими именами (точка или область). */
	private static final Map<String, String> HOMES = Map.of(
			"gameplaymod:krosh_tailed", "дом_кроша",
			"gameplaymod:yozhik", "дом_ежика",
			"gameplaymod:karych", "дом_карыча",
			"gameplaymod:losyash", "дом_лосяша",
			"gameplaymod:nyusha", "дом_нюши",
			"gameplaymod:barash", "дом_бараша",
			"gameplaymod:sovunya", "дом_совуньи");

	/** Смешарик участвует в идущем уровне: в нём участник или он напарник прогона. */
	private static boolean inRunningLevel(MinecraftServer server, CharacterEntity c) {
		Identifier type = EntityType.getKey(c.getType());
		UUID rider = c.getControllingPassenger() instanceof ServerPlayer p ? p.getUUID() : null;
		for (LevelRun run : allRuns(server)) {
			if (run.state() != LevelRun.State.RUNNING) continue;
			if (run.companions().contains(type) || rider != null && run.hasParticipant(rider)) return true;
		}
		return false;
	}

	/**
	 * Смешарик в мире один (GameplayMod): дома для возрождения — локации {@link #HOMES}; погибший в уровне дома не возрождается, им распоряжается
	 * уровень. В уровне без персонажа игрока смешарик его роли приходит к нему сам (после гибели — к началу задачи).
	 */
	private static void initSingletons() {
		Singletons.setHomes((server, type, dying) -> {
			if (dying != null && inRunningLevel(server, dying)) return Optional.empty();
			String name = HOMES.get(EntityType.getKey(type).toString());
			if (name == null) return Optional.empty();
			LevelStore store = LevelStore.get(server);
			Vec3 point = store.locationPoints().get(name);
			if (point != null) return Optional.of(new Singletons.Home(Level.OVERWORLD, point));
			TaskArea area = store.locationAreas().get(name);
			if (area == null) return Optional.empty();
			Host host = new Host(server, false);
			Vec3 drawn = area.drawnCenter();
			return Optional.of(new Singletons.Home(area.dimension(), drawn != null ? host.floorNear(area.dimension(), drawn) : host.groundAt(area.dimension(), area.center())));
		});
		// Нюша зовёт только тех, кто не занят уровнем или занят тем же, в котором она сама
		com.smeshariki.gameplay.character.Skills.setCallFilter((caller, type) -> {
			MinecraftServer server = caller.level().getServer();
			Identifier id = EntityType.getKey(type);
			List<LevelRun> mine = runsOf(caller);
			for (LevelRun run : allRuns(server)) {
				if (run.state() != LevelRun.State.RUNNING || mine.contains(run)) continue;
				if (run.companions().contains(id) || run.participants().stream().anyMatch(p -> id.equals(run.roleOf(p.id())))) return false;
				CharacterEntity body = Singletons.find(server, type);
				if (body != null && body.getControllingPassenger() instanceof ServerPlayer rider && run.hasParticipant(rider.getUUID())) return false;
			}
			return true;
		});
		Possession.setAutoChoice(player -> {
			for (LevelRun run : runsOf(player)) {
				if (run.state() != LevelRun.State.RUNNING) continue;
				Identifier role = run.roleOf(player.getUUID());
				if (role != null) return characterType(role);
			}
			return null;
		});
	}

	/** Сервер для крючков GameplayMod, у которых его нет (одежда погибшего персонажа). */
	private static MinecraftServer server;

	/**
	 * Одежда погибшего персонажа участника идущего уровня не падает на землю, а ждёт в прогоне: персонаж вернётся к началу задачи одетым.
	 * Сюжетный прогон важнее побочных.
	 */
	private static void initGear() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		// верёвки (тарзанки) из снимка ставятся после возврата блоков области: им нужны блоки, на которых они держатся
		com.smeshariki.gameplay.raft.BalloonSnapshots.setRestoreGate(() -> !AreaReset.idle() || !ResetCells.idle());
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		CharacterEntity.setGearKeeper((player, gear) -> {
			if (server == null) return false;
			LevelRun keeper = null;
			for (LevelRun run : allRuns(server)) {
				if (run.state() != LevelRun.State.RUNNING || !run.hasParticipant(player)) continue;
				if (keeper == null || run.definition().kind() == LevelKind.STORY) keeper = run;
			}
			if (keeper == null) return false;
			keeper.keepGear(player, gear);
			return true;
		});
	}

	/** Сбережённая одежда — на персонажа, когда игрок снова в нём и уже вернулся к началу задачи; что не надеть — в инвентарь. */
	private static void returnGear(MinecraftServer server) {
		for (LevelRun run : allRuns(server)) {
			if (!run.hasKeptGear()) continue;
			for (UUID id : run.keptGearOwners()) {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player == null || !player.isAlive() || RETURNS.containsKey(id) || !(player.getVehicle() instanceof CharacterEntity character)) continue;
				for (net.minecraft.world.item.ItemStack stack : run.takeGear(id)) {
					if (!character.wear(stack) && !player.getInventory().add(stack)) player.drop(stack, false);
				}
			}
		}
	}

	private static void initCompanions() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.entityTags().contains(OLD_GHOST_TAG)) {
				entity.discard();
				return;
			}
			if (entity.entityTags().contains(OLD_COMPANION_TAG)) {
				entity.discard();
				return;
			}
			// напарник, чей прогон кончился, пока он был выгружен: отпустить
			if (entity instanceof CharacterEntity character && Companions.isCompanion(character)) {
				Identifier type = EntityType.getKey(character.getType());
				if (allRuns(level.getServer()).stream().noneMatch(run -> run.state() == LevelRun.State.RUNNING && run.companions().contains(type))) Companions.release(character);
			}
		});
		// реплики напарнику — из текущей миссии прогонов игрока, если у него есть такой напарник
		Dialogs.register(player -> {
			List<Dialogs.Reply> out = new ArrayList<>();
			for (LevelRun run : runsOf(player)) {
				MissionEntry mission = run.currentMission();
				if (mission == null) continue;
				for (com.smeshariki.level.definition.OrderEntry entry : mission.orders()) {
					if (run.companionFor(entry.call().character()) == null) continue;
					out.add(new Dialogs.Reply(entry.text(), speaker -> run.order(entry.call(), speaker.getUUID())));
				}
			}
			return out;
		});
	}

	private static List<LevelRun> allRuns(MinecraftServer server) {
		List<LevelRun> runs = new ArrayList<>(LevelStore.get(server).runs());
		runs.addAll(TESTS.values());
		return runs;
	}

	public static void requestSync() {
		syncNeeded = true;
	}

	/** Сообщает о действии игрока его прогону. */
	public static void signal(ServerPlayer player, TaskSignal signal) {
		for (LevelRun run : runsOf(player)) run.signal(signal);
	}

	/**
	 * Подключает прогоны к текущим определениям: прогон с диска без них не работает, а определения могли перезагрузить. В начале тика,
	 * до обработки пакетов, чтобы игроки никогда не застали неподключённый прогон.
	 */
	private static void attachRuns(MinecraftServer server) {
		LevelStore store = LevelStore.get(server);
		Host host = new Host(server, false);
		boolean removed = store.runs().removeIf(run -> {
			Levels.Loaded loaded = Levels.get(run.level());
			if (loaded != null && run.matches(loaded.definition())) {
				run.attach(loaded.definition(), host);
				return false;
			}
			LevelMod.LOGGER.warn("Прогон уровня {} убран: уровень удалён или изменился состав задач", run.level());
			for (Identifier companion : run.companions()) host.releaseCompanion(companion);
			return true;
		});
		if (removed) {
			store.setDirty();
			syncNeeded = true;
		}
	}

	/** Прогоны игрока вместе с его проверкой задачи: на игровые действия они отзываются одинаково. */
	private static List<LevelRun> runsOf(ServerPlayer player) {
		List<LevelRun> runs = new ArrayList<>(LevelStore.get(player.level().getServer()).runsWith(player.getUUID()));
		LevelRun test = TESTS.get(player.getUUID());
		if (test != null) runs.add(test);
		return runs;
	}

	private static void onDeath(ServerPlayer player) {
		for (LevelRun run : runsOf(player)) run.onDeath(player.getUUID());
		// гибель вернула к началу задачи: возврат пройдёт за затемнением
		if (RETURNS.containsKey(player.getUUID())) FADE_RETURNS.add(player.getUUID());
	}

	/** Кого после гибели возвращают к началу задачи: возврат скрыт затемнением экрана. Только в памяти, как и сам возврат. */
	private static final Set<UUID> FADE_RETURNS = new HashSet<>();
	/** Затемнение возврата: чёрный, пока игрок переносится, затем светлеет, тиков. */
	private static final int FADE_HOLD = 10, FADE_OUT = 20;

	/** Кого после гибели возвращают к месту и затем держат до конца миссии (см. {@link LevelRun#checkpointPoint}): они ещё не возродились и не вернулись. Только в памяти, как и сам возврат. */
	private static final Set<UUID> HOLD_PENDING = new HashSet<>();
	/** Кто стоит на месте, пока миссию не закончат все (закончил раньше других и возродился). Только в памяти: переживает до конца миссии или выхода из сети. */
	private static final Set<UUID> HOLDS = new HashSet<>();

	/** Где стоял и кем был игрок, ушедший в режим наблюдателя во время ожидания; тело персонажа при этом замерло и неуязвимо. Только в памяти. */
	private record Spot(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, @org.jetbrains.annotations.Nullable Identifier character, boolean wasNoAi, boolean wasInvulnerable) {
		Spot at(ResourceKey<Level> dimension, Vec3 pos) {
			return new Spot(dimension, pos, yaw, pitch, character, wasNoAi, wasInvulnerable);
		}
	}

	private static final Map<UUID, Spot> SPECTATORS = new HashMap<>();
	/** Тик сервера, раньше которого игрок снова переключиться не может: затемнение должно успеть пройти. */
	private static final Map<UUID, Integer> SPECTATE_COOLDOWN = new HashMap<>();
	private static final int SPECTATE_PAUSE = 40;

	/** Можно ли сейчас наблюдать: идёт отсчёт после сброса или игрок стоит, пока другие заканчивают миссию. */
	private static boolean canSpectate(UUID player) {
		return HOLDS.contains(player) || FROZEN.containsKey(player);
	}

	/** Игрок в режиме наблюдателя ждёт: прогоны видят его там, где он стоял, а не там, куда он улетел. */
	private static boolean spectating(UUID player) {
		return SPECTATORS.containsKey(player);
	}

	/** Игрок просит наблюдать: если ждёт, уходит в режим наблюдателя, если уже наблюдает — возвращается. Всё за затемнением экрана, как возврат после гибели. */
	private static void toggleSpectating(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		UUID id = player.getUUID();
		if (server.getTickCount() < SPECTATE_COOLDOWN.getOrDefault(id, 0)) return;
		SPECTATE_COOLDOWN.put(id, server.getTickCount() + SPECTATE_PAUSE);
		if (spectating(id)) {
			stopSpectating(server, id, true);
			return;
		}
		if (!canSpectate(id) || !player.isAlive() || player.gameMode() != GameType.SURVIVAL) return;
		CharacterEntity body = player.getVehicle() instanceof CharacterEntity character ? character : null;
		Vec3 pos = player.getRootVehicle().position();
		SPECTATORS.put(id, new Spot(player.level().dimension(), pos, player.getYRot(), player.getXRot(), body == null ? null : EntityType.getKey(body.getType()),
				body != null && body.isNoAi(), body != null && body.isInvulnerable()));
		ServerPlayNetworking.send(player, new com.smeshariki.level.net.ScreenFade(FADE_HOLD, FADE_OUT));
		player.stopRiding();
		// пока игрок наблюдает, его персонаж стоит на месте и его не тронуть: по нему стреляют, и он гуляет, если оставить его без хозяина
		if (body != null) {
			body.setNoAi(true);
			body.setInvulnerable(true);
			body.setDeltaMovement(Vec3.ZERO);
		}
		player.setGameMode(GameType.SPECTATOR);
	}

	/** Наблюдатель возвращается в тело на то место, где ждал (оно могло смениться после сброса): режим выживания, персонаж на месте и снова вселён. */
	private static void stopSpectating(MinecraftServer server, UUID id, boolean fade) {
		Spot spot = SPECTATORS.remove(id);
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (spot == null || player == null) return;
		ServerLevel level = server.getLevel(spot.dimension());
		if (level == null) level = player.level();
		if (fade) ServerPlayNetworking.send(player, new com.smeshariki.level.net.ScreenFade(FADE_HOLD, FADE_OUT));
		player.setGameMode(GameType.SURVIVAL);
		player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, Set.of(), spot.yaw(), spot.pitch(), true);
		player.setDeltaMovement(Vec3.ZERO);
		EntityType<CharacterEntity> type = spot.character() == null ? null : characterType(spot.character());
		CharacterEntity body = type == null ? null : Singletons.find(server, type);
		if (body == null) return;
		body.setNoAi(spot.wasNoAi());
		body.setInvulnerable(spot.wasInvulnerable());
		if (body.isVehicle()) return;
		Singletons.move(body, level, spot.pos(), spot.yaw());
		// перенос в другое измерение мог дать другой экземпляр
		CharacterEntity moved = Singletons.find(server, type);
		if (moved != null && !moved.isVehicle()) {
			moved.setNoAi(spot.wasNoAi());
			moved.setInvulnerable(spot.wasInvulnerable());
			moved.possess(player);
		}
	}

	/** Вышел из игры наблюдателем (сбой): режим сохранился, а возвращавшего его ожидания уже нет — вернуть в выживание и к началу последней задачи. */
	private static void recoverSpectator(MinecraftServer server, ServerPlayer player) {
		if (player.gameMode() != GameType.SPECTATOR || SPECTATORS.containsKey(player.getUUID())) return;
		for (LevelRun run : allRuns(server)) {
			if (run.state() != LevelRun.State.RUNNING || !run.hasParticipant(player.getUUID())) continue;
			player.setGameMode(GameType.SURVIVAL);
			LevelRun.StartPoint point = run.checkpointPoint(player.getUUID());
			if (point != null) RETURNS.put(player.getUUID(), point);
			return;
		}
	}

	/** Ждущие напарников и наблюдатели, которым ждать больше нечего (миссия кончилась, отсчёт вышел, прогон исчез), возвращаются к игре. */
	private static void tickWaiting(MinecraftServer server) {
		for (UUID id : List.copyOf(HOLD_PENDING)) if (!playsLevel(server, id)) HOLD_PENDING.remove(id);
		for (UUID id : List.copyOf(HOLDS)) if (!playsLevel(server, id)) releaseHold(server, id);
		for (UUID id : List.copyOf(SPECTATORS.keySet())) if (!canSpectate(id)) stopSpectating(server, id, true);
		SPECTATE_COOLDOWN.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	private static boolean playsLevel(MinecraftServer server, UUID player) {
		for (LevelRun run : allRuns(server)) if (run.state() == LevelRun.State.RUNNING && run.hasParticipant(player)) return true;
		return false;
	}

	private static void releaseHold(MinecraftServer server, UUID id) {
		HOLD_PENDING.remove(id);
		if (!HOLDS.remove(id)) return;
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player != null) ServerPlayNetworking.send(player, new com.smeshariki.level.net.HoldWait(false));
	}

	/** Сколько тиков ждать вернувшемуся (поле миссии {@code reset_wait}); только в памяти, как и сам возврат. */
	private static final Map<UUID, Integer> WAITS = new HashMap<>();
	/** Расстояние между вернувшимися в одну точку, блоков: ближе них не ставят. */
	private static final double SPREAD_GAP = 1.4;

	/**
	 * Если в точке уже стоит другой персонаж или в неё же возвращают другого, ставит рядом на свободное место с опорой: двое в одной точке застревают друг в друге
	 * (жалоба: «персонажей спавнят в одной точке и происходит зависание»).
	 */
	private static Vec3 spread(ServerPlayer player, ServerLevel level, Vec3 target, List<Vec3> taken) {
		Entity root = player.getRootVehicle();
		List<Vec3> others = new ArrayList<>(taken);
		for (ServerPlayer other : level.players()) if (other != player && other.getRootVehicle() != root) others.add(other.getRootVehicle().position());
		if (others.stream().noneMatch(o -> crowded(o, target))) return target;
		for (int ring = 1; ring <= 3; ring++) {
			for (int i = 0; i < 8; i++) {
				double angle = Math.PI * 2 * i / 8;
				Vec3 candidate = target.add(Math.cos(angle) * SPREAD_GAP * ring, 0, Math.sin(angle) * SPREAD_GAP * ring);
				if (others.stream().anyMatch(o -> crowded(o, candidate))) continue;
				net.minecraft.world.phys.AABB box = root.getBoundingBox().move(candidate.subtract(root.position()));
				// место свободно и под ногами опора
				if (level.noCollision(root, box) && !level.noCollision(root, box.move(0, -0.6, 0))) return candidate;
			}
		}
		return target;
	}

	/** Возврат к началу задачи начинает её заново: здоровье, сытость и воздух полные, огонь погашен, падение не засчитывается. */
	private static void restoreVitals(ServerPlayer player) {
		Entity root = player.getRootVehicle();
		if (root instanceof net.minecraft.world.entity.LivingEntity body) {
			body.setHealth(body.getMaxHealth());
			body.clearFire();
			body.setAirSupply(body.getMaxAirSupply());
			body.resetFallDistance();
		}
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		player.resetFallDistance();
	}

	private static boolean crowded(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z) < SPREAD_GAP && Math.abs(a.y - b.y) < 2.5;
	}

	private static void returnTheFallen(MinecraftServer server) {
		List<Vec3> taken = new ArrayList<>();
		RETURNS.entrySet().removeIf(entry -> {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				FADE_RETURNS.remove(entry.getKey());
				WAITS.remove(entry.getKey());
				HOLD_PENDING.remove(entry.getKey());
				return true;
			}
			if (!player.isAlive()) return false;
			ServerLevel level = server.getLevel(entry.getValue().dimension());
			Spot watching = SPECTATORS.get(entry.getKey());
			if (watching != null) {
				// наблюдателя не трогаем: на новое место он вернётся, когда выйдет из наблюдения
				FADE_RETURNS.remove(entry.getKey());
				if (level != null) {
					Vec3 target = spread(player, level, entry.getValue().pos(), taken);
					taken.add(target);
					SPECTATORS.put(entry.getKey(), watching.at(entry.getValue().dimension(), target));
				}
			} else {
				if (FADE_RETURNS.remove(entry.getKey())) ServerPlayNetworking.send(player, new com.smeshariki.level.net.ScreenFade(FADE_HOLD, FADE_OUT));
				if (level != null) {
					Vec3 target = spread(player, level, entry.getValue().pos(), taken);
					taken.add(target);
					moveTo(player, level, target);
					restoreVitals(player);
				}
			}
			if (level != null) {
				Integer wait = WAITS.remove(entry.getKey());
				if (wait != null) {
					ServerPlayNetworking.send(player, new com.smeshariki.level.net.ResetWait(wait));
					FROZEN.put(entry.getKey(), wait);
				}
				// свою часть миссии он сделал раньше других: стоит на месте, пока не закончат все
				if (HOLD_PENDING.remove(entry.getKey())) {
					HOLDS.add(entry.getKey());
					ServerPlayNetworking.send(player, new com.smeshariki.level.net.HoldWait(true));
				}
			}
			return true;
		});
	}

	private static boolean talk(ServerPlayer player, Identifier character) {
		boolean wanted = false;
		for (LevelRun run : runsOf(player)) wanted |= run.talk(player.getUUID(), character);
		return wanted;
	}

	/** Клик по персонажу: разговор, и если в нём другой игрок, то разговор общий, и у него тоже. */
	private static boolean talkTo(ServerPlayer player, CharacterEntity target) {
		boolean wanted = talk(player, EntityType.getKey(target.getType()));
		if (target.getControllingPassenger() instanceof ServerPlayer other && player.getVehicle() instanceof CharacterEntity own) wanted |= talk(other, EntityType.getKey(own.getType()));
		return wanted;
	}

	/** Объекты с группой (механики GameplayMod) во всех загруженных мирах. */
	private static List<com.smeshariki.gameplay.api.Grouped> grouped(MinecraftServer server) {
		List<com.smeshariki.gameplay.api.Grouped> out = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) for (Entity entity : level.getAllEntities()) if (entity instanceof com.smeshariki.gameplay.api.Grouped g && !entity.isRemoved()) out.add(g);
		return out;
	}

	private static List<LevelList.GroupInfo> groupInfos(MinecraftServer server, LevelStore store) {
		Map<String, Integer> counts = new java.util.TreeMap<>();
		// группы, на которые ссылаются файлы уровней (параметр "group"): видны, даже если объектов нет или они в незагруженных чанках
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"group\"\\s*:\\s*\"([^\"]+)\"");
		for (Levels.Loaded loaded : Levels.all()) {
			String source = Levels.source(loaded.id());
			if (source == null) continue;
			java.util.regex.Matcher m = pattern.matcher(source);
			while (m.find()) counts.put(m.group(1), 0);
		}
		for (String name : store.groups()) counts.putIfAbsent(name, 0);
		for (com.smeshariki.gameplay.api.Grouped g : grouped(server)) {
			if (g.group().isEmpty()) continue;
			counts.merge(g.group(), 1, Integer::sum);
			// увиденная на объекте группа остаётся в реестре, когда объект выгрузится
			if (store.groups().add(g.group())) store.savePlaces();
		}
		List<LevelList.GroupInfo> out = new ArrayList<>();
		counts.forEach((name, count) -> out.add(new LevelList.GroupInfo(name, count)));
		return out;
	}

	/** Правка групп: реестр и состав. Объекты автор выбирает в мире инструментом «Выбор» BuildMod; группу получают только сущности, которым она задаётся ({@code Grouped}). */
	private static void editGroup(ServerPlayer player, com.smeshariki.level.net.LevelGroupEdit edit) {
		String name = edit.name().trim();
		if (!canEdit(player)) return;
		if (edit.action() == com.smeshariki.level.net.LevelGroupEdit.Action.REFRESH) {
			// окно групп открыто: числа членов зависят от загруженных чанков и меняются, когда игрок ходит
			ServerPlayNetworking.send(player, list(player.level().getServer(), player));
			return;
		}
		if (name.isEmpty() || name.length() > com.smeshariki.level.net.LevelGroupEdit.MAX_NAME) return;
		MinecraftServer server = player.level().getServer();
		LevelStore store = LevelStore.get(server);
		switch (edit.action()) {
			case REFRESH -> {
			}
			case CREATE -> store.groups().add(name);
			case DELETE -> {
				store.groups().remove(name);
				for (com.smeshariki.gameplay.api.Grouped g : grouped(server)) if (name.equals(g.group())) g.setGroup("");
			}
			case ASSIGN, REMOVE -> {
				boolean assign = edit.action() == com.smeshariki.level.net.LevelGroupEdit.Action.ASSIGN;
				if (assign) store.groups().add(name);
				int changed = 0;
				for (UUID id : edit.objects()) {
					for (ServerLevel level : server.getAllLevels()) {
						if (!(level.getEntity(id) instanceof com.smeshariki.gameplay.api.Grouped g)) continue;
						if (assign && !name.equals(g.group())) {
							g.setGroup(name);
							changed++;
						} else if (!assign && name.equals(g.group())) {
							g.setGroup("");
							changed++;
						}
						break;
					}
				}
				player.sendOverlayMessage(Component.translatable(assign ? "message.levelmod.group.assigned" : "message.levelmod.group.removed", changed, name));
			}
		}
		store.savePlaces();
		syncNeeded = true;
	}

	private static void editLocation(ServerPlayer player, LevelLocationEdit edit) {
		String name = edit.name().trim();
		if (!canEdit(player) || name.isEmpty() || name.length() > LevelLocationEdit.MAX_NAME) return;
		MinecraftServer server = player.level().getServer();
		LevelStore store = LevelStore.get(server);
		store.locationPoints().remove(name);
		store.locationAreas().remove(name);
		edit.point().ifPresent(point -> store.locationPoints().put(name, point));
		edit.area().ifPresent(area -> store.locationAreas().put(name, area));
		store.savePlaces();
		Levels.rebuild(server);
		syncNeeded = true;
	}

	private static LevelList.Locations locations(LevelStore store) {
		List<LevelList.LocationInfo> marked = new ArrayList<>();
		store.locationPoints().forEach((name, point) -> marked.add(new LevelList.LocationInfo(name, Optional.of(point), Optional.empty())));
		store.locationAreas().forEach((name, area) -> marked.add(new LevelList.LocationInfo(name, Optional.empty(), Optional.of(area))));
		marked.sort(Comparator.comparing(LevelList.LocationInfo::name));
		return new LevelList.Locations(marked, Levels.missingLocations(), Levels.problems());
	}

	private static void test(ServerPlayer player, LevelTestTask command) {
		// остановить свою проверку можно и в выживании (проверка переключает в него), начать — только тому, кто может править
		if (!canEdit(player) && !(command.missionJson().isEmpty() && TESTS.containsKey(player.getUUID()))) return;
		MinecraftServer server = player.level().getServer();
		// как у обычного уровня: пока мир откатывается после прошлого (блоки и сущности, в том числе маркеры волн лавы, создаются заново), начинать нельзя.
		// Идущая проверка откат не запускает (он ждёт её конца), так что отказ касается только отката после уже законченного
		if (command.missionJson().isPresent() && WorldReset.busy(server) && !testing()) {
			player.sendOverlayMessage(Component.translatable("message.levelmod.refuse.world_reset"));
			return;
		}
		LevelRun previous = TESTS.remove(player.getUUID());
		// остановленная проверка возвращает постройки миссии из снимка, как брошенный уровень: иначе лава осталась бы поднятой, маркеры волн сдвинутыми,
		// и следующая проверка или запись сняла бы уже испорченную пещеру
		if (previous != null) previous.abandon();
		syncNeeded = true;
		if (command.missionJson().isEmpty()) {
			restoreMode(player, player.getUUID());
			return;
		}
		Set<String> missing = new TreeSet<>();
		JsonElement resolved = Levels.resolve(JsonParser.parseString(command.missionJson().get()), LevelStore.get(server), missing);
		if (!missing.isEmpty()) {
			player.sendOverlayMessage(Component.translatable("message.levelmod.test_no_location", String.join(", ", missing)));
			return;
		}
		var decoded = MissionEntry.CODEC.parse(JsonOps.INSTANCE, resolved);
		if (decoded.isError()) {
			player.sendOverlayMessage(Component.literal(decoded.error().orElseThrow().message()));
			return;
		}
		MissionEntry mission = decoded.getOrThrow();
		// только сами задачи: откуда они начинаются и что идёт после, проверяется уже в уровне
		List<TaskEntry> alone = new ArrayList<>();
		for (TaskEntry entry : mission.tasks()) alone.add(new TaskEntry(entry.id(), entry.title(), entry.scope(), entry.character(), List.of(), List.of(), 0, entry.area(), entry.optional(), entry.task()));
		// персонажи уровня, из которого проверка: за тех, кем никто не играет, идут напарники
		Levels.Loaded source = command.level().map(Levels::get).orElse(null);
		List<Identifier> levelCharacters = source == null ? List.of() : source.definition().characters();
		// и триггеры с наблюдателями уровня: автобег, стрелы, волны лавы в проверке как в игре. Триггеры чужих миссий не сработают:
		// их событий в проверке нет; порядок задач миссии, заданный уровнем, сохраняется
		List<Trigger> levelTriggers = source == null ? List.of() : source.definition().triggers().stream().filter(t -> source.definition().triggerOwner(t).isEmpty()).toList();
		List<com.smeshariki.level.definition.Watch> levelWatches = source == null ? List.of() : source.definition().watches();
		LevelDefinition definition = new LevelDefinition(LevelKind.STORY, mission.title(), Component.empty(), LevelPreview.NONE, 1, 1, 0, Optional.empty(), List.of(new MissionEntry(mission.id(), mission.title(), mission.description(), alone, mission.areas(), mission.checkpoints(), mission.orders(), mission.gear(), mission.resetWait(), mission.guide(), mission.triggers())), levelTriggers, levelCharacters, Map.of(), levelWatches).expanded();
		LevelRun run = LevelRun.gather(TEST_LEVEL, definition, new Host(server, true));
		// проверка начинается с экрана ожидания, как после сброса (в полном прохождении уровень стартует без него)
		run.waitAtStart();
		run.addParticipant(player.getUUID(), player.getGameProfile().name());
		TESTS.put(player.getUUID(), run);
		TEST_MODES.putIfAbsent(player.getUUID(), player.gameMode());
		player.setGameMode(GameType.SURVIVAL);
		// вселённый в того, для кого задач нет, сменится сразу; невселённого спросит окно выбора
		Set<Identifier> roles = testRoles(run);
		if (player.getVehicle() instanceof CharacterEntity character && !roles.isEmpty() && !roles.contains(EntityType.getKey(character.getType()))) {
			for (var type : Possession.choices()) {
				if (!roles.contains(EntityType.getKey(type))) continue;
				Possession.swapTo(player, type);
				break;
			}
		}
	}

	/** Для кого задачи проверки; пусто — есть задача для любого, значит, подходит кто угодно. */
	private static Set<Identifier> testRoles(LevelRun run) {
		Set<Identifier> roles = new HashSet<>();
		for (TaskEntry entry : run.definition().tasks()) {
			if (entry.character().isEmpty()) return Set.of();
			roles.add(entry.character().get());
		}
		return roles;
	}

	private static void restoreMode(ServerPlayer player, UUID id) {
		GameType mode = TEST_MODES.remove(id);
		if (player == null || mode == null || mode == GameType.SURVIVAL) return;
		// в другом режиме персонаж не обязателен, а призванный без игрока сам исчезнет
		player.stopRiding();
		player.setGameMode(mode);
	}

	private static void tickTests(MinecraftServer server) {
		TESTS.entrySet().removeIf(entry -> {
			LevelRun run = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			// начинается, когда персонаж выбран: от него зависит, какие задачи показать
			if (run.state() == LevelRun.State.GATHERING) {
				if (player == null || !CharacterEntity.isPossessing(player)) return false;
				run.begin();
			}
			run.tick();
			if (run.takeChanged()) syncNeeded = true;
			if (run.state() == LevelRun.State.RUNNING) return false;
			syncNeeded = true;
			restoreMode(player, entry.getKey());
			if (player != null) player.sendOverlayMessage(Component.translatable("message.levelmod.test_done", LevelStats.format(run.elapsed()), LevelStats.format(Levels.analyze(run.definition()).expectedTicks())));
			return true;
		});
	}

	/** Причина отказа зависит от персонажа, а его могут сменить и мимо этого мода: тогда игроку нужен свежий список. */
	private static void watchCharacters(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Identifier type = player.getVehicle() instanceof CharacterEntity character ? EntityType.getKey(character.getType()) : null;
			if (!Objects.equals(CHARACTERS.put(player.getUUID(), type), type)) syncNeeded = true;
		}
	}

	/**
	 * Переключатели механик, которыми управляют триггеры: их состояние выводится из идущих прогонов, поэтому с концом прогона они выключаются сами.
	 * Каждый зарегистрированный получает свою картину каждый тик, выключенный — пустую.
	 */
	/** Сколько тиков отсчёта осталось тому, кто уже вернулся и ждёт (на сервере; клиент считает свой отсчёт сам). */
	private static final Map<UUID, Integer> FROZEN = new HashMap<>();

	/** Ждёт ли игрок после сброса: ещё не вернулся или стоит на отсчёте. */
	/** Сервер, где ожидание после сброса кончилось, а механики ещё не продолжили придержанное возвратом (волны лавы). */
	private static MinecraftServer resumeServer;

	/** Придержанное возвратом снимка продолжается, когда блоки области и ячейки чекпоинта вернулись: иначе волна пошла бы по лаве, которой ещё нет. */
	private static void tickResume(MinecraftServer server) {
		if (resumeServer != server || !AreaReset.idle() || !ResetCells.idle()) return;
		resumeServer = null;
		Mechanics.snapshots().values().forEach(snapshot -> snapshot.resume(server));
	}

	private static boolean waiting(UUID player) {
		return WAITS.containsKey(player) || FROZEN.containsKey(player) || HOLDS.contains(player);
	}

	private static void tickFrozen(MinecraftServer server) {
		FROZEN.replaceAll((id, left) -> left - 1);
		List<UUID> done = FROZEN.entrySet().stream().filter(e -> e.getValue() <= 0).map(Map.Entry::getKey).toList();
		for (UUID id : done) {
			FROZEN.remove(id);
			waitFinished(server, id);
		}
	}

	/** Отсчёт кончился или игрок ушёл: прогоны узнают, что ждать его больше не нужно. */
	private static void waitFinished(MinecraftServer server, UUID player) {
		List<LevelRun> runs = new ArrayList<>(LevelStore.get(server).runs());
		runs.addAll(TESTS.values());
		for (LevelRun run : runs) run.waitFinished(player);
	}

	private static void syncMechanics(MinecraftServer server, LevelStore store) {
		Map<String, Map<UUID, Set<String>>> wanted = new HashMap<>();
		List<LevelRun> runs = new ArrayList<>(store.runs());
		runs.addAll(TESTS.values());
		for (LevelRun run : runs) {
			run.activeToggles().forEach((id, keys) -> {
				if (keys.isEmpty()) return;
				Map<UUID, Set<String>> byPlayer = wanted.computeIfAbsent(id, k -> new HashMap<>());
				// пока игрок ждёт после сброса, стрелы и автобег для него выключены: включатся, когда отсчёт кончится
				for (LevelRun.Participant participant : run.participants()) if (!waiting(participant.id())) byPlayer.computeIfAbsent(participant.id(), k -> new HashSet<>()).addAll(keys);
			});
		}
		Mechanics.toggles().forEach((id, toggle) -> toggle.sync(server, wanted.getOrDefault(id.toString(), Map.of())));
	}

	/** Что игроку уже отправлено как цель стрелки (x, z); нет записи — цели нет. */
	private static final Map<UUID, double[]> GUIDES = new HashMap<>();

	/** Кто уже дошёл до явной цели стрелки (вошёл в её область): дальше в этой миссии стрелки нет, даже если выйти обратно. Ключ — игрок, значение — цель (x, z). */
	private static final Map<UUID, double[]> GUIDE_REACHED = new HashMap<>();

	private static Vec3 centerOf(TaskArea area) {
		return area.drawnCenter() != null ? area.drawnCenter() : area.center();
	}

	/**
	 * Цель стрелки игрока: явное поле миссии {@code guide} (область или точка), а без него область идущей задачи «добраться до области» его персонажа;
	 * середина области. {@code null} — стрелки нет (цели нет, миссия не идёт или игрок уже дошёл до явной цели).
	 */
	private static double @org.jetbrains.annotations.Nullable [] guideOf(ServerPlayer player) {
		for (LevelRun run : runsOf(player)) {
			if (run.state() != LevelRun.State.RUNNING) continue;
			MissionEntry mission = run.currentMission();
			if (mission != null && mission.guide().isPresent()) {
				var place = mission.guide().get();
				Vec3 target = place.map(point -> point, LevelManager::centerOf);
				double[] key = {target.x, target.z};
				Vec3 at = player.getRootVehicle().position();
				// дошёл до начала цели (область «бездна» и т. п.): стрелка гаснет до конца миссии
				boolean inside = place.map(point -> Math.hypot(at.x - point.x, at.z - point.z) < 6, area -> area.contains(at));
				if (inside) GUIDE_REACHED.put(player.getUUID(), key);
				if (java.util.Arrays.equals(GUIDE_REACHED.get(player.getUUID()), key)) return null;
				return key;
			}
			TaskArea reach = run.reachTarget(player.getUUID());
			if (reach != null) {
				Vec3 target = centerOf(reach);
				return new double[] {target.x, target.z};
			}
		}
		// миссия со стрелкой кончилась: следующая начнётся заново
		GUIDE_REACHED.remove(player.getUUID());
		return null;
	}

	/** Стрелку клиент рисует сам по своему положению, ему нужна только цель: шлём при смене. */
	private static void syncGuides(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			double[] now = guideOf(player), sent = GUIDES.get(player.getUUID());
			if (java.util.Arrays.equals(now, sent)) continue;
			if (now == null) GUIDES.remove(player.getUUID());
			else GUIDES.put(player.getUUID(), now);
			ServerPlayNetworking.send(player, now == null ? new com.smeshariki.level.net.GuideTarget(false, 0, 0) : new com.smeshariki.level.net.GuideTarget(true, now[0], now[1]));
		}
		GUIDES.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/** Идёт ли проверка задачи или миссии из редактора. */
	static boolean testing() {
		return !TESTS.isEmpty();
	}

	private static void tick(MinecraftServer server) {
		watchCharacters(server);
		returnTheFallen(server);
		returnGear(server);
		tickTests(server);
		LevelStore store = LevelStore.get(server);
		for (LevelRun run : List.copyOf(store.runs())) {
			removeNonSurvival(server, run);
			run.tick();
			if (run.takeChanged()) syncNeeded = true;
		}
		if (!store.runs().isEmpty()) {
			store.sweep();
			store.setDirty();
		}
		trackChunks(server, store);
		tickFrozen(server);
		tickWaiting(server);
		syncMechanics(server, store);
		AreaReset.tick(server);
		ResetCells.tick(server);
		AreaCleanup.tick(server);
		tickResume(server);
		WorldReset.tick(server);
		syncGuides(server);
		if (syncNeeded && server.getTickCount() % SYNC_INTERVAL == 0) {
			syncNeeded = false;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(player, list(server, player));
		}
	}

	/** Радиус (в чанках) вокруг игрока, который считается тронутым: дальность руки и обстрелов, края вида не нужны. */
	private static final int TOUCH_RADIUS = 2;
	/** Предел чанков одной области миссии, чтобы огромный контур не раздул отметки. */
	private static final int TOUCH_AREA_CAP = 4096;

	/** Запоминает чанки, где идёт уровень или проверка: откат мира возвращает только их (оптимизация, {@link WorldReset}). */
	private static void trackChunks(MinecraftServer server, LevelStore store) {
		if (server.getTickCount() % 10 != 0) return;
		List<LevelRun> running = new ArrayList<>(store.runs());
		// проверка миссии или задачи тоже меняет мир (лава, шары, блоки), и после неё мир откатывается
		running.addAll(TESTS.values());
		for (LevelRun run : running) {
			if (run.state() != LevelRun.State.RUNNING) continue;
			for (LevelRun.Participant participant : run.participants()) {
				ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
				if (player == null) continue;
				Entity root = player.getRootVehicle();
				String dimension = root.level().dimension().identifier().toString();
				int cx = root.chunkPosition().x(), cz = root.chunkPosition().z();
				for (int dx = -TOUCH_RADIUS; dx <= TOUCH_RADIUS; dx++) for (int dz = -TOUCH_RADIUS; dz <= TOUCH_RADIUS; dz++) store.touch(dimension, net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz));
			}
			// область миссии: лава, шары и обстрелы работают и там, где никого нет
			MissionEntry mission = run.currentMission();
			if (mission == null) continue;
			for (TaskArea area : mission.areas()) {
				double[] b = area.boundsXZ();
				int x0 = (int) Math.floor(b[0]) >> 4, z0 = (int) Math.floor(b[1]) >> 4, x1 = (int) Math.floor(b[2]) >> 4, z1 = (int) Math.floor(b[3]) >> 4;
				if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > TOUCH_AREA_CAP) continue;
				String dimension = area.dimension().identifier().toString();
				for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) store.touch(dimension, net.minecraft.world.level.ChunkPos.pack(x, z));
			}
		}
	}

	/** Уровни проходят только в режиме выживания: сменивший режим покидает прогон. */
	private static void removeNonSurvival(MinecraftServer server, LevelRun run) {
		for (LevelRun.Participant participant : List.copyOf(run.participants())) {
			ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
			if (player == null || player.gameMode() == GameType.SURVIVAL || spectating(participant.id())) continue;
			player.sendOverlayMessage(Component.translatable("message.levelmod.survival_only"));
			leave(run, participant.id());
		}
	}

	private static void leave(LevelRun run, UUID player) {
		// последний: сначала бросаем уровень, чтобы сообщение о конце дошло и до него
		if (run.participants().size() == 1) run.abandon();
		run.removeParticipant(player);
	}

	/** Дальше этого переноса (блоков) ездового переносят полным телепортом с пассажирами. */
	private static final double FAR_MOVE = 32;

	/** Переносит игрока вместе с персонажем. Позицию персонажа ведёт клиент вселённого, поэтому ему новую нужно сообщить отдельно. */

	static void moveTo(ServerPlayer player, ServerLevel level, Vec3 target) {
		Entity root = player.getRootVehicle();
		// далёкий перенос ездового идёт через полный телепорт с пассажирами (снять, перенести, посадить): иначе клиент получает посадку на сущность, которой ещё не знает
		// («Received passengers for unknown entity»), игрок не управляет, камера дёргается
		boolean near = root.position().distanceToSqr(target) < FAR_MOVE * FAR_MOVE;
		if (root != player && root.level() == level && near) {
			root.teleportTo(target.x, target.y, target.z);
			root.setDeltaMovement(Vec3.ZERO);
			player.connection.send(ClientboundMoveVehiclePacket.fromEntity(root));
			// клиент гостя (не хозяина мира) мог не принять перенос управляемого носителя и слал старую позицию, пока сервер её отбрасывал
			// («moved too quickly» каждый тик); обычный пакет перемещения сущности принимается и тогда
			player.connection.send(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(
					root.getId(), net.minecraft.world.entity.PositionMoveRotation.of(root), Set.of(), root.onGround()));
		} else {
			root.teleportTo(level, target.x, target.y, target.z, Set.of(), root.getYRot(), root.getXRot(), true);
			root.setDeltaMovement(Vec3.ZERO);
		}
	}

	private static boolean eligible(ServerPlayer player) {
		return player.gameMode() == GameType.SURVIVAL && CharacterEntity.isPossessing(player);
	}

	private static boolean canEdit(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		// редактор — инструмент творческого режима: в выживании в окне уровней остаётся только начать уровень
		if (!player.isCreative()) return false;
		// хост, а на выделенном сервере операторы
		if (server.isDedicatedServer()) return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
		return server.isSingleplayerOwner(player.nameAndId());
	}

	private static void handle(ServerPlayer player, LevelCommand command) {
		MinecraftServer server = player.level().getServer();
		LevelStore store = LevelStore.get(server);
		Levels.Loaded loaded = Levels.get(command.level());
		if (loaded == null) return;
		LevelDefinition definition = loaded.definition();
		UUID id = player.getUUID();
		LevelRun run = store.runOf(command.level());
		boolean mine = run != null && run.hasParticipant(id);
		String name = player.getGameProfile().name();
		switch (command.action()) {
			case LevelCommand.START -> {
				if (run != null || refuseTaking(player, command.level()) || refuseFar(player, definition)) return;
				run = LevelRun.gather(command.level(), definition, new Host(server, false));
				run.addParticipant(id, name);
				store.runs().add(run);
				if (definition.maxPlayers() == 1) run.begin();
			}
			case LevelCommand.JOIN -> {
				if (run == null || mine) return;
				if (run.state() == LevelRun.State.RUNNING) {
					// вернуться в идущий прогон может тот, кто вышел из него (сменил режим): роль за ним осталась
					if (run.hasRole(id) && eligible(player)) run.addParticipant(id, name);
					return;
				}
				if (run.state() != LevelRun.State.GATHERING || run.participants().size() >= definition.maxPlayers() || refuseTaking(player, command.level()) || refuseFar(player, definition)) return;
				run.addParticipant(id, name);
			}
			case LevelCommand.BEGIN -> {
				if (!mine) return;
				List<UUID> online = run.online();
				if (online.size() < definition.minPlayers() || !online.getFirst().equals(id)) return;
				for (UUID member : online) {
					// с момента присоединения могло измениться всё: персонаж, сюжетный уровень
					if (refuseTaking(server.getPlayerList().getPlayer(member), command.level())) {
						if (!member.equals(id)) player.sendOverlayMessage(Component.translatable("message.levelmod.group_not_ready"));
						return;
					}
				}
				run.begin();
			}
			case LevelCommand.LEAVE -> {
				if (!mine) return;
				leave(run, id);
			}
			case LevelCommand.RESET -> {
				// вернуть уровню исходный вид: файл заменяется заготовкой мода
				if (!canEdit(player) || run != null || !Levels.hasTemplate(command.level())) return;
				try {
					LevelFiles.write(server, command.level(), Levels.template(command.level()).toString());
				} catch (java.io.IOException e) {
					LevelMod.LOGGER.error("Не записан файл уровня {}", command.level(), e);
					return;
				}
				store.resetStats(command.level());
				store.setDirty();
				Levels.rebuild(server);
			}
			case LevelCommand.DELETE -> {
				if (!canEdit(player) || run != null) return;
				try {
					LevelFiles.delete(server, command.level());
				} catch (java.io.IOException e) {
					LevelMod.LOGGER.error("Не удалён файл уровня {}", command.level(), e);
					return;
				}
				store.resetStats(command.level());
				store.setDirty();
				Levels.rebuild(server);
			}
			default -> {
				return;
			}
		}
		store.sweep();
		store.setDirty();
		syncNeeded = true;
	}

	/** Сообщает игроку, если взять этот уровень он сейчас не может. */
	private static boolean refuseTaking(ServerPlayer player, Identifier level) {
		String refusal = eligible(player) ? refusal(player.level().getServer(), player.getUUID(), level) : "message.levelmod.survival_only";
		if (refusal.isEmpty()) return false;
		player.sendOverlayMessage(Component.translatable(refusal));
		return true;
	}

	/** Побочный уровень берут, подойдя к его началу. Поэтому из коридора сюжетной задачи за ним не сходить без провала этой задачи. */
	private static boolean refuseFar(ServerPlayer player, LevelDefinition definition) {
		if (definition.kind() != LevelKind.SIDE) return false;
		Vec3 start = definition.startPos().orElseThrow();
		if (LevelDefinition.nearStart(start, player.position())) return false;
		player.sendOverlayMessage(Component.translatable("message.levelmod.refuse.side_far", (int) start.x, (int) start.y, (int) start.z));
		return true;
	}

	/**
	 * Почему игрок не может взять уровень: ключ перевода, пустая строка — может. Сюжетный уровень один на игрока. Побочные (до
	 * {@link #MAX_SIDE_LEVELS}) берутся только при идущем сюжетном. Расстояние до начала побочного здесь не считается: оно меняется
	 * постоянно, его проверяют {@link #refuseFar} и окно уровней.
	 */
	public static String refusal(MinecraftServer server, UUID player, Identifier level) {
		String reason = levelRefusal(server, player, level);
		if (!reason.isEmpty()) return reason;
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online == null || !(online.getVehicle() instanceof CharacterEntity character) || Levels.get(level).definition().allowsCharacter(EntityType.getKey(character.getType()))) return "";
		// вне уровней персонажа можно сменить прямо в окне, а посреди прогона он закреплён
		return LevelStore.get(server).runsWith(player).stream().allMatch(run -> run.level().equals(level)) ? REFUSE_CHARACTER_SWAP : "message.levelmod.refuse.character";
	}

	private static String levelRefusal(MinecraftServer server, UUID player, Identifier level) {
		LevelStore store = LevelStore.get(server);
		LevelDefinition definition = Levels.get(level).definition();
		LevelRun story = null;
		int sides = 0;
		for (LevelRun run : store.runsWith(player)) {
			Levels.Loaded other = Levels.get(run.level());
			if (run.level().equals(level) || other == null) continue;
			if (other.definition().kind() == LevelKind.STORY) story = run;
			else sides++;
		}
		if (definition.kind() == LevelKind.STORY) {
			if (WorldReset.busy(server)) return "message.levelmod.refuse.world_reset";
			return story != null ? "message.levelmod.refuse.one_story" : "";
		}
		if (story == null || story.state() != LevelRun.State.RUNNING) return "message.levelmod.refuse.need_story";
		return sides >= MAX_SIDE_LEVELS ? "message.levelmod.refuse.side_limit" : "";
	}

	/** Сменить персонажа можно только вне уровней: посреди прохождения персонаж закреплён. */
	private static void swapCharacter(ServerPlayer player, LevelSwapCharacter swap) {
		Levels.Loaded loaded = Levels.get(swap.level());
		if (loaded == null || !loaded.definition().allowsCharacter(swap.character()) || !LevelStore.get(player.level().getServer()).runsWith(player.getUUID()).isEmpty()) return;
		for (var type : Possession.choices()) if (EntityType.getKey(type).equals(swap.character())) Possession.swapTo(player, type);
	}

	private static void save(ServerPlayer player, LevelSave save) {
		if (!canEdit(player)) return;
		MinecraftServer server = player.level().getServer();
		LevelStore store = LevelStore.get(server);
		List<String> problems = new ArrayList<>();
		if (save.id().isPresent() && Levels.source(save.id().get()) == null) problems.add("уровня больше нет");
		if (save.id().isPresent() && store.runOf(save.id().get()) != null) problems.add("уровень сейчас проходят");
		JsonElement json = JsonParser.parseString(save.json());
		if (problems.isEmpty()) {
			Levels.Parsed parsed = Levels.parse(json, store, new TreeSet<>());
			problems.addAll(parsed.problems());
			if (parsed.level() != null) problems.addAll(Levels.analyze(parsed.level()).problems());
		}
		if (problems.isEmpty()) {
			Identifier id = save.id().orElseGet(() -> Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "world/level_" + System.currentTimeMillis()));
			String previous = Levels.source(id);
			try {
				// файл и память вместе: сначала файл, потому что по нему уровень и загрузится в следующий раз
				LevelFiles.write(server, id, save.json());
				// длительности прежнего состава задач к новому не относятся
				if (previous != null && !Objects.equals(JsonParser.parseString(previous).getAsJsonObject().get("missions"), json.getAsJsonObject().get("missions"))) store.resetStats(id);
				store.setDirty();
				Levels.rebuild(server);
				syncNeeded = true;
			} catch (java.io.IOException e) {
				LevelMod.LOGGER.error("Не записан файл уровня {}", id, e);
				problems.add("файл уровня не записался: " + e.getMessage());
			}
		}
		ServerPlayNetworking.send(player, new LevelSaveResult(problems));
	}

	private static LevelList list(MinecraftServer server, ServerPlayer player) {
		LevelStore store = LevelStore.get(server);
		List<LevelList.LevelInfo> levels = new ArrayList<>();
		for (Levels.Loaded loaded : Levels.all()) {
			LevelDefinition definition = loaded.definition();
			LevelRun run = store.runOf(loaded.id());
			// после перезагрузки датапака прогон может ещё не соответствовать определению, до начала следующего тика
			List<LevelList.TaskInfo> tasks = taskInfos(run != null && run.matches(definition) ? run : null, definition, player.getUUID());
			Optional<LevelList.RunInfo> info = run == null ? Optional.empty() : Optional.of(new LevelList.RunInfo(run.state(), List.copyOf(run.participants()), run.elapsed()));
			LevelList.Meta meta = new LevelList.Meta(definition.kind(), definition.minPlayers(), definition.maxPlayers(), refusal(server, player.getUUID(), loaded.id()), definition.startPos(), definition.characters(),
					Levels.overridesTemplate(loaded.id()));
			levels.add(new LevelList.LevelInfo(loaded.id(), definition.title(), definition.description(), definition.preview(), meta, loaded.expectedTicks(), store.statsOf(loaded.id()), tasks, info, canEdit(player) ? Optional.ofNullable(Levels.source(loaded.id())) : Optional.empty(), definition.missions().stream().map(MissionEntry::title).toList(), definition.missions().stream().map(MissionEntry::description).toList()));
		}
		LevelRun test = TESTS.get(player.getUUID());
		return new LevelList(levels, Levels.cutsceneIds(), canEdit(player), test != null, test != null && test.state() == LevelRun.State.RUNNING ? test.activeAreas() : List.of(), test != null ? taskInfos(test, test.definition(), player.getUUID()) : List.of(), canEdit(player) ? locations(store) : LevelList.Locations.NONE, WorldReset.percent(server), canEdit(player) ? groupInfos(server, store) : List.of());
	}

	private static List<LevelList.TaskInfo> taskInfos(LevelRun run, LevelDefinition definition, UUID player) {
		List<LevelList.TaskInfo> tasks = new ArrayList<>();
		List<MissionEntry> missions = definition.missions();
		for (int m = 0; m < missions.size(); m++) {
			for (TaskEntry entry : missions.get(m).tasks()) {
				boolean started = run != null && run.isStarted(entry);
				boolean mine = run == null || run.hasRole(player, entry);
				tasks.add(new LevelList.TaskInfo(entry.title(), started ? entry.task().counter(run.progressOf(entry)) : "", started, run != null && run.isCompleted(entry), entry.area(), m, mine, entry.optional(), run != null && run.isMissed(entry)));
			}
		}
		return tasks;
	}

	/** @param test это проверка задачи: о ней не знают слушатели, и ни уровня, ни его конца для игрока нет */
	private record Host(MinecraftServer server, boolean test) implements RunHost {
		@Override
		public void runMechanic(LevelRun run, Identifier id, Mechanics.Params params) {
			Mechanics.Action action = Mechanics.action(id);
			if (action == null) return;
			List<ServerPlayer> players = new ArrayList<>();
			for (UUID player : run.online()) players.add(server.getPlayerList().getPlayer(player));
			action.run(server, players, params);
		}

		@Override
		public boolean check(UUID player, Identifier id, Mechanics.Params params) {
			ServerPlayer online = server.getPlayerList().getPlayer(player);
			Mechanics.Condition condition = Mechanics.condition(id);
			return online != null && condition != null && !spectating(player) && condition.test(online, params);
		}

		@Override
		public void tell(UUID player, Component message, boolean overlay) {
			ServerPlayer online = server.getPlayerList().getPlayer(player);
			if (overlay) online.sendOverlayMessage(message);
			else online.sendSystemMessage(message);
		}

		@Override
		public CompoundTag saveSnapshot(TaskArea area) {
			CompoundTag tag = new CompoundTag();
			ServerLevel level = server.getLevel(area.dimension());
			if (level == null) return tag;
			double[] b = area.boundsXZ();
			for (int cx = (int) Math.floor(b[0]) >> 4; cx <= (int) Math.floor(b[2]) >> 4; cx++)
				for (int cz = (int) Math.floor(b[1]) >> 4; cz <= (int) Math.floor(b[3]) >> 4; cz++)
					if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(cx, cz))) return null;
			Mechanics.snapshots().forEach((id, snapshot) -> tag.put(id.toString(), snapshot.take(level, area)));
			return tag;
		}

		@Override
		public CompoundTag saveCells(TaskArea area, java.util.List<com.smeshariki.level.task.ResetCell> cells) {
			ServerLevel level = server.getLevel(area.dimension());
			return level == null ? new CompoundTag() : ResetCells.take(level, area, cells);
		}

		@Override
		public void restoreCells(TaskArea area, CompoundTag cells) {
			ServerLevel level = server.getLevel(area.dimension());
			if (level != null) ResetCells.restore(level, area, cells);
		}

		@Override
		public void resumeSnapshots() {
			// не сразу: пока блоки области и ячейки чекпоинта не вернулись (лава на месте), волна идти не должна — см. tickResume
			resumeServer = server;
		}

		@Override
		public void restoreSnapshot(ResourceKey<Level> dimension, CompoundTag tag) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) return;
			Mechanics.snapshots().forEach((id, snapshot) -> tag.getCompound(id.toString()).ifPresent(part -> snapshot.restore(level, part)));
		}


		@Override
		public void prepareBlocks(TaskArea area) {
			AreaReset.prepare(server, area);
		}

		@Override
		public void resetBlocks(TaskArea area) {
			AreaReset.reset(server, area);
			AreaCleanup.request(server, area);
		}

		@Override
		public void recruitCompanion(Identifier character, UUID leader) {
			ServerPlayer player = server.getPlayerList().getPlayer(leader);
			EntityType<CharacterEntity> type = characterType(character);
			if (player == null || type == null || Singletons.busy(server, type, null)) return;
			Singletons.fetch(server, type, found -> {
				ServerPlayer now = server.getPlayerList().getPlayer(leader);
				if (now == null) return;
				CharacterEntity c = found != null ? found : Singletons.create(now.level(), type, CharacterEntity.body(now).position(), now.getYRot());
				if (c.isVehicle() || c.isScripted()) return;
				Companions.recruit(c, now);
			});
		}

		@Override
		public void releaseCompanion(Identifier character) {
			EntityType<CharacterEntity> type = characterType(character);
			CharacterEntity c = type == null ? null : Singletons.find(server, type);
			if (c != null && Companions.isCompanion(c)) Companions.release(c);
		}

		@Override
		public void orderCompanion(Identifier character, UUID leader, Identifier order, Mechanics.Params params) {
			ServerPlayer player = server.getPlayerList().getPlayer(leader);
			EntityType<CharacterEntity> type = characterType(character);
			CharacterEntity c = type == null ? null : Singletons.find(server, type);
			if (player == null || c == null || !Companions.isCompanion(c)) return;
			Component refusal = Orders.run(order, c, player, params);
			if (refusal != null) player.sendOverlayMessage(refusal);
		}

		@Override
		public Vec3 position(UUID player) {
			ServerPlayer online = server.getPlayerList().getPlayer(player);
			if (online == null) return null;
			// наблюдатель для уровня стоит там, где ждёт, а не там, куда улетел
			Spot watching = SPECTATORS.get(player);
			return watching != null ? watching.pos() : online.getRootVehicle().position();
		}

		@Override
		public void wear(UUID player, Identifier item) {
			ServerPlayer p = server.getPlayerList().getPlayer(player);
			var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(item);
			if (p == null || it.isEmpty() || !(p.getVehicle() instanceof CharacterEntity character)) return;
			if (LevelConditions.count(p, stack -> stack.is(it.get())) == 0) character.wear(new net.minecraft.world.item.ItemStack(it.get()));
		}

		@Override
		public int countItem(UUID player, java.util.function.Predicate<net.minecraft.world.item.ItemStack> match) {
			return LevelConditions.count(server.getPlayerList().getPlayer(player), match);
		}

		@Override
		public Identifier character(UUID player) {
			return server.getPlayerList().getPlayer(player).getVehicle() instanceof CharacterEntity character ? EntityType.getKey(character.getType()) : null;
		}

		/** Если в разных прогонах точки разные, главная у сюжетного уровня. */
		@Override
		public void returnTo(LevelRun run, UUID player, LevelRun.StartPoint point) {
			// после сброса игрок снова идёт к цели: стрелка, погасшая у достигнутой цели, должна вернуться
			GUIDE_REACHED.remove(player);
			if (run.definition().kind() == LevelKind.STORY) RETURNS.put(player, point);
			else RETURNS.putIfAbsent(player, point);
		}

		@Override
		public void freeze(UUID player, int ticks) {
			WAITS.put(player, ticks);
		}

		@Override
		public void hold(UUID player, boolean on) {
			if (on) HOLD_PENDING.add(player);
			else releaseHold(server, player);
		}

		@Override
		public void startWait(UUID player, int ticks) {
			ServerPlayer online = server.getPlayerList().getPlayer(player);
			if (online == null) return;
			ServerPlayNetworking.send(online, new com.smeshariki.level.net.ResetWait(ticks));
			FROZEN.put(player, ticks);
		}

		@Override
		public Vec3 floorNear(ResourceKey<Level> dimension, Vec3 near) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) return near;
			int cx = Mth.floor(near.x), cy = Mth.floor(near.y), cz = Mth.floor(near.z);
			for (int r = 0; r <= GROUND_SEARCH; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						int x = cx + dx, z = cz + dz;
						level.getChunk(x >> 4, z >> 4);
						// сверху вниз от чуть выше подсказки: опора, над ней две свободные клетки без жидкости
						for (int y = cy + FLOOR_UP; y >= cy - FLOOR_DOWN; y--) {
							BlockPos feet = new BlockPos(x, y, z);
							BlockState floor = level.getBlockState(feet.below());
							// на кронах и стволах не ставим: сквозь листву падают, как и у поверхности
							if (free(level, feet) && free(level, feet.above()) && !floor.getCollisionShape(level, feet.below()).isEmpty()
									&& level.getFluidState(feet.below()).isEmpty() && !floor.is(BlockTags.LEAVES) && !floor.is(BlockTags.LOGS) && !floor.is(Blocks.BAMBOO)) {
								double top = level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).max(net.minecraft.core.Direction.Axis.Y);
								return new Vec3(x + 0.5, y - 1 + top, z + 0.5);
							}
						}
					}
				}
			}
			return groundAt(dimension, near);
		}

		private static boolean free(ServerLevel level, BlockPos pos) {
			return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
		}

		@Override
		public Vec3 groundAt(ResourceKey<Level> dimension, Vec3 near) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) return near;
			int cx = Mth.floor(near.x), cz = Mth.floor(near.z);
			for (int r = 0; r <= GROUND_SEARCH; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						int x = cx + dx, z = cz + dz;
						level.getChunk(x >> 4, z >> 4);
						int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
						BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
						if (!below.is(BlockTags.LOGS) && !below.is(Blocks.BAMBOO) && below.getFluidState().isEmpty()) return new Vec3(x + 0.5, y, z + 0.5);
					}
				}
			}
			return near;
		}

		@Override
		public void say(UUID player, Component message) {
			server.getPlayerList().getPlayer(player).sendSystemMessage(message);
		}

		@Override
		public ResourceKey<Level> dimension(UUID player) {
			Spot watching = SPECTATORS.get(player);
			return watching != null ? watching.dimension() : server.getPlayerList().getPlayer(player).level().dimension();
		}

		@Override
		public void outsideArea(LevelRun run, UUID player, int secondsLeft) {
			server.getPlayerList().getPlayer(player).sendOverlayMessage(Component.translatable("message.levelmod.area_warning", secondsLeft).withStyle(ChatFormatting.RED));
		}

		@Override
		public void backInArea(LevelRun run, UUID player) {
			server.getPlayerList().getPlayer(player).sendOverlayMessage(Component.translatable("message.levelmod.area_back").withStyle(ChatFormatting.GREEN));
		}

		@Override
		public int cutsceneDuration(Identifier cutscene) {
			return Levels.cutsceneDuration(cutscene);
		}

		@Override
		public void playCutscene(LevelRun run, Identifier cutscene) {
			List<ServerPlayer> viewers = new ArrayList<>();
			for (UUID id : run.online()) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) viewers.add(p);
			}
			// без точки: сцена, записанная в мире, играет там, где записана
			if (!viewers.isEmpty()) Cutscenes.play(cutscene, viewers, null, completed -> run.cutsceneEnded(cutscene));
		}

		@Override
		public void publish(LevelRun run, LevelEvent event) {
			if (!test) LevelEventCallback.EVENT.invoker().onEvent(run, event);
			// конец любого уровня и проверки: блоки и сущности мира вернутся к сохранённой версии, когда закончатся все идущие прогоны
			if ((event.kind() == LevelEvent.Kind.LEVEL_COMPLETED || event.kind() == LevelEvent.Kind.LEVEL_ABANDONED)) WorldReset.request(server);
		}
	}
}
