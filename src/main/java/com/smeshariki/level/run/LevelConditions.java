package com.smeshariki.level.run;

import java.util.List;
import java.util.function.Predicate;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.level.LevelMod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Свои проверки уровней в общем реестре моста: место и предметы. Механики (вода, шар, стропы) регистрирует GameplayMod. */
public final class LevelConditions {
	private LevelConditions() {
	}

	public static void init() {
		// игрок (или его персонаж) в области; min_y и max_y — по высоте (область сама по высоте не ограничена: пещера под поверхностью)
		Mechanics.registerCondition(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "in_area"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				Mechanics.Area area = params.area("area");
				var pos = player.getRootVehicle().position();
				if (pos.y < params.number("min_y", Double.NEGATIVE_INFINITY) || pos.y > params.number("max_y", Double.POSITIVE_INFINITY)) return false;
				return area != null && area.dimension().equals(player.level().dimension()) && area.contains(pos);
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return params.area("area") == null ? List.of("не указана область (area)") : List.of();
			}

			@Override
			public String hint() {
				return "area=@место, min_y=…, max_y=…";
			}
		});
		// сейчас есть предметы (инвентарь и одежда персонажа): item — предмет или #тег, count — сколько, model — модель сюжетного предмета (компонент item_model)
		Mechanics.registerCondition(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "has_item"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				Predicate<ItemStack> match = Mechanics.item(params.string("item", ""));
				if (match == null) return false;
				Identifier model = Identifier.tryParse(params.string("model", ""));
				Predicate<ItemStack> full = model == null || params.string("model", "").isEmpty() ? match : match.and(stack -> model.equals(stack.get(DataComponents.ITEM_MODEL)));
				return count(player, full) >= Math.max(1, (int) params.number("count", 1));
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return Mechanics.item(params.string("item", "")) == null ? List.of("нет такого предмета: " + params.string("item", "")) : List.of();
			}

			@Override
			public String hint() {
				return "item=minecraft:apple|#тег, count=3, model=…";
			}
		});
	}

	/** Сколько подходящих предметов у игрока, вместе с одеждой персонажа: очки, надетые сразу, тоже считаются. */
	public static int count(ServerPlayer player, Predicate<ItemStack> match) {
		int count = count(player.getInventory(), match);
		return player.getVehicle() instanceof CharacterEntity character ? count + count(character.gear, match) : count;
	}

	private static int count(Container container, Predicate<ItemStack> match) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty() && match.test(stack)) count += stack.getCount();
		}
		return count;
	}

}
