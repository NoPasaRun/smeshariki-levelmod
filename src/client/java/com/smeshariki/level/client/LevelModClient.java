package com.smeshariki.level.client;

import org.lwjgl.glfw.GLFW;

import com.buildmod.client.OutlineDrawing;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.net.LevelSaveResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class LevelModClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "main"));
	static final KeyMapping OPEN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + LevelMod.NAMESPACE + ".levels", GLFW.GLFW_KEY_L, CATEGORY));

	/** Во время ожидания (отсчёт после сброса, конец миссии у других): уйти в режим наблюдателя и обратно. */
	static final KeyMapping SPECTATE = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + LevelMod.NAMESPACE + ".spectate", GLFW.GLFW_KEY_V, CATEGORY));

	/**
	 * «Подробнее» о миссии: окно с описанием; ту же кнопку рисует список задач в углу. Клавиша I (описание механики предмета — O, GameplayMod). Имя ключа новое
	 * ({@code mission_details}, раньше {@code details} на J): настройки игрока хранят клавиши по имени, и старое J перебило бы новую по умолчанию.
	 */
	static final KeyMapping DETAILS = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + LevelMod.NAMESPACE + ".mission_details", GLFW.GLFW_KEY_I, CATEGORY));

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(LevelList.TYPE, (msg, ctx) -> ClientLevels.set(msg));
		ClientPlayNetworking.registerGlobalReceiver(LevelSaveResult.TYPE, (msg, ctx) -> {
			if (ctx.client().gui.screen() instanceof LevelEditorScreen editor) editor.onSaveResult(msg.problems());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientLevels.clear();
			FadeOverlay.clear();
		});
		FadeOverlay.init();
		WaitOverlay.init();
		GuideArrow.init();
		LocationsHighlight.init();
		ClientTickEvents.END_CLIENT_TICK.register(AreaClient::tick);
		TaskHud.init();
		// начало тика, до обработки клавиш в игре: L по умолчанию занята ещё и ванильными достижениями, и их нажатие надо успеть убрать.
		// Если игрок переназначит одну из клавиш, каждая будет работать сама по себе
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			ClientLevels.age++;
			if (mc.player == null) return;
			while (DETAILS.consumeClick()) if (mc.gui.screen() == null && !TaskHud.running(mc).isEmpty()) mc.gui.setScreen(new MissionDetailsScreen());
			boolean pressed = false;
			while (OPEN.consumeClick()) pressed = true;
			if (!pressed) return;
			if (mc.options.keyAdvancements.same(OPEN)) while (mc.options.keyAdvancements.consumeClick()) ;
			OutlineDrawing.cancel();
			ClientLevels.locationsOpen = false;
			// в выживании окно одно — список уровней, начать уровень; черновик и локации остаются на потом, до творческого режима
			if (ClientLevels.draft != null && ClientLevels.editing()) LevelEditorScreen.reopen(mc);
			else if (ClientLevels.inLocations && ClientLevels.editing()) mc.gui.setScreen(new LocationsScreen());
			else mc.gui.setScreen(new LevelManagerScreen());
		});
	}
}
