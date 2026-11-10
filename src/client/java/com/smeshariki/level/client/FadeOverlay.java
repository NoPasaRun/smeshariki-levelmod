package com.smeshariki.level.client;

import com.smeshariki.level.LevelMod;
import com.smeshariki.level.net.ScreenFade;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Затемнение экрана после гибели: скрывает возврат погибшего к началу задачи. Чёрный поверх всего интерфейса, затем светлеет. */
final class FadeOverlay {
	private static int hold, fade, age = -1;

	private FadeOverlay() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(ScreenFade.TYPE, (msg, ctx) -> {
			hold = msg.hold();
			fade = Math.max(1, msg.fade());
			age = 0;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (age < 0) return;
			if (++age > hold + fade) age = -1;
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "fade"), FadeOverlay::extract);
	}

	static void clear() {
		age = -1;
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (age < 0) return;
		float t = age + delta.getGameTimeDeltaPartialTick(false) - hold;
		float alpha = t <= 0 ? 1f : Math.max(0f, 1f - t / fade);
		if (alpha <= 0f) return;
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (alpha * 255) << 24));
	}
}
