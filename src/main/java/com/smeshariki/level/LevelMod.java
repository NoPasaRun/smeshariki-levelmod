package com.smeshariki.level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.smeshariki.level.definition.Levels;
import com.smeshariki.level.run.LevelManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class LevelMod implements ModInitializer {
	public static final String NAMESPACE = "levelmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);

	@Override
	public void onInitialize() {
		com.smeshariki.level.run.LevelConditions.init();
		com.smeshariki.level.run.LevelCommands.init();
		Levels.init();
		LevelManager.init();
		// шкатулка из первого уровня: бумага с моделью (задача «Взять лопату и шкатулку» проверяет именно её)
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"))).register(output -> output.accept(casket()));
	}

	/** Шкатулка: как выдаёт команда {@code /give @s minecraft:paper[item_model="levelmod:shovel_box",item_name=...,max_stack_size=1]}. */
	public static ItemStack casket() {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(NAMESPACE, "shovel_box"));
		stack.set(DataComponents.ITEM_NAME, Component.translatable("item.levelmod.shovel_box"));
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		return stack;
	}
}
