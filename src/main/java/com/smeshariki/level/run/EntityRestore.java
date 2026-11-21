package com.smeshariki.level.run;

import java.util.List;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * Сущности чанка из текущей версии мира для отката после уровня ({@link WorldReset}): нынешние убираются, версионные создаются заново с теми же UUID.
 * Не трогаются и не создаются заново: игроки и всё, что несёт игрока, смешарики ({@link CharacterEntity}: в мире один, им владеет вселение и напарники),
 * сущности BuildMod (платформы и стены хранят состояние в памяти сервера, пока они не перечитываются из файла) и типы, которые в мир не сохраняются
 * (брёвна реки): их в версии нет, и убирать их нельзя.
 */
final class EntityRestore {
	private EntityRestore() {
	}

	private static boolean isProtected(Entity entity) {
		if (entity instanceof ServerPlayer || entity instanceof CharacterEntity || entity.isRemoved()) return true;
		if (!entity.getType().canSerialize()) return true;
		if ("buildmod".equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace())) return true;
		// несёт игрока или едет на том, что несёт
		for (Entity rider : entity.getIndirectPassengers()) if (rider instanceof ServerPlayer) return true;
		if (entity.getRootVehicle() != entity) for (Entity rider : entity.getRootVehicle().getIndirectPassengers()) if (rider instanceof ServerPlayer) return true;
		return false;
	}

	/** Заменяет сущности чанка версионными; возвращает, сколько ваниль не приняла (UUID ещё занят) — тогда звать позже ещё раз. */
	static int apply(ServerLevel level, ChunkPos pos, List<CompoundTag> tags, java.util.function.BiPredicate<Double, Double> inside) {
		AABB box = new AABB(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(), pos.getMaxBlockX() + 1, level.getMaxY(), pos.getMaxBlockZ() + 1);
		// только внутри контура отката: снаружи сущности чанка не трогаются, из версии не ставятся
		for (Entity entity : level.getEntities((Entity) null, box, e -> !isProtected(e) && e.chunkPosition().equals(pos) && inside.test(e.getX(), e.getZ()))) com.smeshariki.gameplay.raft.BalloonSnapshots.discardQuietly(entity);
		int failed = 0;
		EntitySpawnRequest request = new EntitySpawnRequest(EntitySpawnReason.LOAD, true);
		for (CompoundTag tag : tags) {
			net.minecraft.nbt.ListTag at = tag.getListOrEmpty("Pos");
			if (at.size() >= 3 && !inside.test(at.getDoubleOr(0, 0), at.getDoubleOr(2, 0))) continue;
			Entity entity;
			try {
				entity = EntityType.loadEntityRecursive(tag.copy(), level, request, EntityProcessor.NOP);
			} catch (RuntimeException e) {
				continue;
			}
			if (entity == null || isProtected(entity)) continue;
			if (!level.addFreshEntity(entity)) failed++;
		}
		return failed;
	}
}
