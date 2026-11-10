package com.smeshariki.level.client;

import com.smeshariki.gameplay.character.CharacterFreeze;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.net.HoldWait;
import com.smeshariki.level.net.ResetWait;
import com.smeshariki.level.net.SpectateRequest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Ожидание: персонаж стоит ({@link CharacterFreeze}), камеру игрок крутит. Два вида: отсчёт после сброса задачи (по центру экрана крупные цифры секунд до 1)
 * и ожидание без отсчёта, когда игрок закончил миссию раньше других (надпись, пока не закончат все). Во время ожидания клавиша наблюдения уводит игрока в режим
 * наблюдателя и обратно; когда ожидание кончается, сервер сам возвращает его.
 */
final class WaitOverlay {
	private static int left;
	/** Игрок стоит до конца миссии у остальных. */
	private static boolean hold;

	private WaitOverlay() {
	}

	/** Игрок сейчас ждёт: отсчёт идёт или он стоит до конца миссии. */
	static boolean active() {
		return left > 0 || hold;
	}

	private static void apply() {
		CharacterFreeze.set(active());
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(ResetWait.TYPE, (msg, ctx) -> {
			left = msg.ticks();
			apply();
		});
		ClientPlayNetworking.registerGlobalReceiver(HoldWait.TYPE, (msg, ctx) -> {
			hold = msg.on();
			apply();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null) while (LevelModClient.SPECTATE.consumeClick()) if (active() || mc.player.isSpectator()) ClientPlayNetworking.send(new SpectateRequest());
			if (left <= 0) return;
			if (--left <= 0 || mc.player == null) {
				left = 0;
				apply();
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "reset_wait"), WaitOverlay::extract);
	}

	static void clear() {
		left = 0;
		hold = false;
		apply();
	}

	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!active()) return;
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		if (left > 0) {
			String digits = Integer.toString((left + 19) / 20);
			float scale = 6f;
			g.pose().pushMatrix();
			g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 3f);
			g.pose().scale(scale, scale);
			g.text(font, Component.literal(digits), -font.width(digits) / 2, -font.lineHeight / 2, 0xFFFFFFFF);
			g.pose().popMatrix();
		}
		int y = left > 0 ? g.guiHeight() / 3 + 40 : g.guiHeight() / 3;
		if (hold && left <= 0) {
			Component text = Component.translatable("gui.levelmod.wait.hold");
			g.text(font, text, (g.guiWidth() - font.width(text)) / 2, y, 0xFFFFFFFF);
			y += font.lineHeight + 4;
		}
		// кнопка наблюдателя: клавиша в рамке и подпись, как «Подробнее» у списка задач; режим включается только нажатием
		boolean back = mc.player != null && mc.player.isSpectator();
		Component key = LevelModClient.SPECTATE.getTranslatedKeyMessage();
		Component label = Component.translatable(back ? "gui.levelmod.wait.back" : "gui.levelmod.wait.spectate");
		int keyW = font.width(key) + 8, labelW = font.width(label), h = 15, w = keyW + labelW + 14, x = (g.guiWidth() - w) / 2;
		rounded(g, x, y, x + w, y + h, 0xB0141922);
		rounded(g, x + 2, y + 2, x + 2 + keyW, y + h - 2, 0xFF3B4A5F);
		g.text(font, key, x + 6, y + 4, 0xFFFFFFFF);
		g.text(font, label, x + keyW + 8, y + 4, 0xFFD0D6DE);
	}
}
