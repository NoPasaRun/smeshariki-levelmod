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
import net.minecraft.client.Minecraft;
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
	private static final double DISTANCE = 2.6, DROP = 1.05;
	/** Размеры: хвост и древко назад от центра, наконечник вперёд, половина высоты древка и наконечника, блоков. */
	private static final float TAIL = 0.55f, NECK = 0.1f, HEAD = 0.7f, SHAFT_HALF = 0.1f, HEAD_HALF = 0.34f;
	/** Цвет стрелки (ARGB) без прозрачности: прозрачность даёт {@link #ALPHA}, помноженная на силу показа. */
	private static final int RGB = 0xFFE066;
	private static final int ALPHA = 0x78;
	/** Ближе этого к цели (по горизонтали) стрелка не нужна. */
	private static final double ARRIVED = 4.0;
	/** Доля пути к нужной силе показа за тик. */
	private static final float FADE = 0.12f;

	private static boolean on;
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
			float want = on && !WaitOverlay.active() && !mc.player.isSpectator() && distance(mc) > ARRIVED ? 1f : 0f;
			strength += (want - strength) * FADE;
			if (strength < 0.01f && want == 0f) strength = 0f;
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(GuideArrow::submit);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
	}

	static void clear() {
		on = false;
		strength = 0f;
	}

	private static double distance(Minecraft mc) {
		Vec3 at = mc.player.getRootVehicle().position();
		return Math.hypot(targetX - at.x, targetZ - at.z);
	}

	private static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (strength <= 0f || mc.player == null || mc.level == null) return;
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
		int color = ((int) (ALPHA * strength) << 24) | RGB;
		PoseStack pose = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(pose, onTop(), (p, c) -> {
			// стрела целиком в вертикальной плоскости вдоль направления: u — вперёд, v — вверх
			// древко
			quad(c, p, ox, oy, oz, fx, fz, -TAIL, -SHAFT_HALF, -TAIL, SHAFT_HALF, NECK, SHAFT_HALF, NECK, -SHAFT_HALF, color);
			// наконечник — треугольник: острие повторено, чтобы вышел четырёхугольник
			quad(c, p, ox, oy, oz, fx, fz, NECK, -HEAD_HALF, NECK, HEAD_HALF, HEAD, 0f, HEAD, 0f, color);
		});
	}

	/** Четырёхугольник стрелки по четырём точкам (u, v) в вертикальной плоскости вдоль (fx, fz); рисуется с обеих сторон, чтобы виден был при любом отсечении. */
	private static void quad(VertexConsumer c, PoseStack.Pose p, float ox, float oy, float oz, float fx, float fz,
			float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3, int color) {
		float[][] q = {{u0, v0}, {u1, v1}, {u2, v2}, {u3, v3}};
		for (int i = 0; i < 4; i++) c.addVertex(p, ox + fx * q[i][0], oy + q[i][1], oz + fz * q[i][0]).setColor(color);
		for (int i = 3; i >= 0; i--) c.addVertex(p, ox + fx * q[i][0], oy + q[i][1], oz + fz * q[i][0]).setColor(color);
	}
}
