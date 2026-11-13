package com.smeshariki.level.client;

import java.lang.reflect.Method;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.net.GuideTarget;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Стрелка к цели миссии (поле {@code guide} или область задачи «добраться до области»): полупрозрачная, стоит прямо, перпендикулярно земле, в мире перед игроком
 * (на {@link #DISTANCE} блоков по взгляду, ниже глаз) и показывает на цель по горизонтали. Дойти до цели — стрелка гаснет; у самой цели она не нужна.
 * Рисуется поверх всего: без проверки глубины, ни блоки, ни существа её не загораживают.
 */
final class GuideArrow {
	/** Где висит стрелка: впереди по взгляду и ниже глаз, блоков. */
	private static final double DISTANCE = 2.2, DROP = 1.2;
	/** Пиксельный рисунок стрелки (остриё справа): столбцы — вперёд, строки — вбок. */
	private static final String[] SHAPE = {
		".......#.....",
		".......##....",
		"###....###...",
		"###########..",
		"############.",
		"###########..",
		"###....###...",
		".......##....",
		".......#.....",
	};
	private static final int COLS = 13, ROWS = 9;
	/** Размер пикселя, блоков. */
	private static final float PIXEL = 0.1f;
	/** Цвета без прозрачности: заливка шахматкой, обводка тёмная. */
	private static final int FILL = 0xFFE066, FILL_ALT = 0xFFD23F, OUTLINE = 0xC96A12, SIDE = 0x8F4A0C;
	/** Толщина стрелки, блоков. */
	private static final float THICK = 0.2f;
	private static final int ALPHA = 0x78, ALPHA_EDGE = 0xB4;
	/** Ближе этого к цели (по горизонтали) стрелка не нужна. */
	private static final double ARRIVED = 4.0;
	/** Доля пути к нужной силе показа за тик. */
	private static final float FADE = 0.12f;

	/** Игрок скрыл стрелку (кнопка в окне «О миссии» или клавиша). */
	static boolean hidden;
	private static boolean on;
	/** Включён шейдерпак Iris (проверяется раз в тик). */
	private static boolean shaders;
	/** Рисовать стрелку на экране и без шейдерпака (для проверки внешнего вида). */
	static boolean forceHud;
	private static double targetX, targetZ;
	private static float strength;
	private static RenderType onTop;

	/** Конвейер отладочных четырёхугольников, но без проверки глубины и записи в неё (глубина в этой версии игры обратная, поэтому «всегда проходит»). */
	private static final class OnTopPipeline extends RenderPipeline {
		OnTopPipeline(RenderPipeline base) {
			super(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "pipeline/guide_arrow"), base.getVertexShader(), base.getFragmentShader(), base.getShaderDefines(),
					base.getBindGroupLayouts(), base.getColorTargetStates(), new DepthStencilState(CompareOp.ALWAYS_PASS, false), base.getPolygonMode(), base.isCull(),
					base.getVertexFormatBindings(), base.getPrimitiveTopology(), base.getSortKey());
		}
	}

	/** Вид отрисовки стрелки: поверх всего. Если собрать его не вышло, остаётся обычный (стрелку тогда заслоняют стены, но она есть). */
	private static RenderType onTop() {
		if (onTop != null) return onTop;
		try {
			RenderSetup setup = RenderSetup.builder(new OnTopPipeline(RenderPipelines.DEBUG_QUADS)).sortOnUpload().createRenderSetup();
			// RenderType.create виден только пакету игры
			Method create = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
			create.setAccessible(true);
			onTop = (RenderType) create.invoke(null, "levelmod_guide_arrow", setup);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LevelMod.LOGGER.warn("Не собрался вид отрисовки стрелки поверх всего, стрелка рисуется как обычно", e);
			onTop = RenderTypes.debugQuads();
		}
		return onTop;
	}

	private GuideArrow() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(GuideTarget.TYPE, (msg, ctx) -> {
			on = msg.on();
			targetX = msg.x();
			targetZ = msg.z();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) {
				strength = 0f;
				return;
			}
			float want = on && !hidden && !WaitOverlay.active() && !com.smeshariki.behavior.client.CutsceneClient.hidesHud() && !mc.player.isSpectator() && distance(mc) > ARRIVED ? 1f : 0f;
			strength += (want - strength) * FADE;
			if (strength < 0.01f && want == 0f) strength = 0f;
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(GuideArrow::submit);
		// с шейдерпаком Iris своя геометрия мира не рисуется (конвейер стрелки ему неизвестен): стрелка идёт на экран, как компас
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "guide_arrow"), GuideArrow::extractHud);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> shaders = forceHud || shaderPackInUse());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
	}

	/** Есть ли цель у стрелки (уровень или проверка её задали): тогда в углу экрана подсказана клавиша, прячущая и возвращающая стрелку. */
	static boolean available() {
		return on;
	}

	static void clear() {
		on = false;
		strength = 0f;
	}

	private static double distance(Minecraft mc) {
		Vec3 at = mc.player.getRootVehicle().position();
		return Math.hypot(targetX - at.x, targetZ - at.z);
	}

	/** Идёт ли отрисовка шейдерпаком Iris; без Iris — нет. */
	private static boolean shaderPackInUse() {
		try {
			Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
			return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Стрелка на экране под прицелом: поворачивается к цели относительно взгляда. */
	private static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!shaders || strength <= 0f || mc.player == null || mc.level == null) return;
		Entity body = mc.player.getRootVehicle();
		double dx = targetX - body.getX(), dz = targetZ - body.getZ();
		if (Math.hypot(dx, dz) < 1e-3) return;
		float partial = delta.getGameTimeDeltaPartialTick(false);
		double relative = Math.atan2(-dx, dz) - Math.toRadians(mc.player.getViewYRot(partial));
		int pixel = 4;
		g.pose().pushMatrix();
		g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 2f + 46f);
		// рисунок смотрит вправо, а на экране «вперёд» — вверх
		g.pose().rotate((float) (relative - Math.PI / 2));
		for (int r = 0; r < ROWS; r++) {
			for (int col = 0; col < COLS; col++) {
				if (!filled(col, r)) continue;
				boolean edge = !filled(col - 1, r) || !filled(col + 1, r) || !filled(col, r - 1) || !filled(col, r + 1);
				int rgb = edge ? OUTLINE : (col + r) % 2 == 0 ? FILL : FILL_ALT;
				int alpha = (int) (Math.min(255, (edge ? ALPHA_EDGE : ALPHA + 40) * strength * 1.2f));
				int x = (int) ((col - COLS / 2f) * pixel), y = (int) ((r - ROWS / 2f) * pixel);
				g.fill(x, y, x + pixel, y + pixel, (alpha << 24) | rgb);
			}
		}
		g.pose().popMatrix();
	}

	private static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (shaders || strength <= 0f || mc.player == null || mc.level == null) return;
		Entity body = mc.player.getRootVehicle();
		double dx = targetX - body.getX(), dz = targetZ - body.getZ();
		double length = Math.hypot(dx, dz);
		if (length < 1e-3) return;
		float fx = (float) (dx / length), fz = (float) (dz / length);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double yaw = Math.toRadians(mc.player.getViewYRot(partial));
		// впереди по взгляду (в мире, а не на экране), ниже глаз; чуть покачивается
		double bob = Math.sin((mc.level.getGameTime() + partial) * 0.12) * 0.04;
		float ox = (float) (-Math.sin(yaw) * DISTANCE), oy = (float) (-DROP + bob), oz = (float) (Math.cos(yaw) * DISTANCE);
		float sx = -fz, sz = fx; // вбок от направления
		PoseStack pose = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(pose, onTop(), (p, c) -> {
			// пиксельная стрелка лежит плашмя (повёрнута на 90° вокруг своей оси) и имеет толщину: сверху заливка шахматкой, с боков тёмные грани
			float top = oy + THICK / 2f, bottom = oy - THICK / 2f;
			int edgeColor = ((int) (ALPHA_EDGE * strength) << 24) | OUTLINE, sideColor = ((int) (ALPHA_EDGE * strength) << 24) | SIDE;
			for (int r = 0; r < ROWS; r++) {
				for (int col = 0; col < COLS; col++) {
					if (!filled(col, r)) continue;
					boolean edge = !filled(col - 1, r) || !filled(col + 1, r) || !filled(col, r - 1) || !filled(col, r + 1);
					int rgb = edge ? OUTLINE : (col + r) % 2 == 0 ? FILL : FILL_ALT;
					int topColor = ((int) ((edge ? ALPHA_EDGE : ALPHA) * strength) << 24) | rgb;
					float u0 = (col - COLS / 2f) * PIXEL, u1 = u0 + PIXEL, w0 = (r - ROWS / 2f) * PIXEL, w1 = w0 + PIXEL;
					face(c, p, ox, oz, fx, fz, sx, sz, topColor, u0, w0, top, u1, w0, top, u1, w1, top, u0, w1, top);
					face(c, p, ox, oz, fx, fz, sx, sz, edgeColor, u0, w0, bottom, u1, w0, bottom, u1, w1, bottom, u0, w1, bottom);
					if (!filled(col - 1, r)) face(c, p, ox, oz, fx, fz, sx, sz, sideColor, u0, w0, bottom, u0, w1, bottom, u0, w1, top, u0, w0, top);
					if (!filled(col + 1, r)) face(c, p, ox, oz, fx, fz, sx, sz, sideColor, u1, w0, bottom, u1, w1, bottom, u1, w1, top, u1, w0, top);
					if (!filled(col, r - 1)) face(c, p, ox, oz, fx, fz, sx, sz, sideColor, u0, w0, bottom, u1, w0, bottom, u1, w0, top, u0, w0, top);
					if (!filled(col, r + 1)) face(c, p, ox, oz, fx, fz, sx, sz, sideColor, u0, w1, bottom, u1, w1, bottom, u1, w1, top, u0, w1, top);
				}
			}
		});
	}

	private static boolean filled(int col, int row) {
		return col >= 0 && col < COLS && row >= 0 && row < ROWS && SHAPE[row].charAt(col) == '#';
	}

	/** Грань по четырём точкам (вперёд, вбок, высота относительно точки показа); видна с обеих сторон. */
	private static void face(VertexConsumer c, PoseStack.Pose p, float ox, float oz, float fx, float fz, float sx, float sz, int color,
			float... q) {
		for (int i = 0; i < 4; i++) c.addVertex(p, ox + fx * q[i * 3] + sx * q[i * 3 + 1], q[i * 3 + 2], oz + fz * q[i * 3] + sz * q[i * 3 + 1]).setColor(color);
		for (int i = 3; i >= 0; i--) c.addVertex(p, ox + fx * q[i * 3] + sx * q[i * 3 + 1], q[i * 3 + 2], oz + fz * q[i * 3] + sz * q[i * 3 + 1]).setColor(color);
	}
}
