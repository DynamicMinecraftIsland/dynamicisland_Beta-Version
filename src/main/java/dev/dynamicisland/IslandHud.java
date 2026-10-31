package dev.dynamicisland;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Dynamic Island v2.
 *
 *  COMPACT : голова скина + ник, ниже HP (+ часы / FPS по желанию).
 *  CHAT    : голова отправителя (или иконка сервера) + ник + текст.
 *  FULL    : "Jade" — блок / жидкость / моб / игрок, чем добывать, HP цели.
 *
 *  J            — обычный <-> полный.
 *  T (чат)      — режим правки: двигать остров, тянуть ручки (ширина, размер), кнопка "Настройки".
 *  Mod Menu     — настройки (если установлен Mod Menu).
 */
public final class IslandHud {
	private static final long MIN_SHOW_MS = 2_500L;
	private static final long[] CHAT_MS = {3_000L, 5_000L, 10_000L, 15_000L, 20_000L};
	private static final float[] OPACITY = {1.0f, 0.85f, 0.70f, 0.55f, 0.40f};
	private static final int[] TINTS = {-1, 0x3A7BFF, 0xFF5CA8, 0x2ED573, 0xA55EEA, 0xFFB020};
	private static final int[] ACCENTS = {0x5AC8FA, 0xFF6FB5, 0x30D158, 0xFF9F0A, 0xFFFFFF};
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
	private static final Class<?>[] NO = new Class<?>[0];

	private enum Mode { COMPACT, FULL, CHAT }

	private record Msg(String nick, String text, boolean player) {}

	private static final class Target {
		String name = "";
		String ns = "";
		String tool;
		int toolColor = 0xB0B0B8;
		ItemStack stack = ItemStack.EMPTY;
		LivingEntity living;
		AbstractClientPlayer player;
	}

	// <Nick> text
	private static final Pattern VANILLA = Pattern.compile("^<([^>]{1,48})>\\s*(.+)$", Pattern.DOTALL);
	// [Rank] Nick: text   |   Nick » text   |   Nick > text
	private static final Pattern GENERIC = Pattern.compile(
			"^(?:\\[[^\\]]{1,24}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*[:»›>]+\\s*(.+)$", Pattern.DOTALL);
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final List<String> SYSTEM_WORDS = List.of(
			"server", "console", "system", "info", "warning", "warn", "broadcast",
			"announcement", "alert", "event", "tip", "note", "error");

	private static final Deque<Msg> queue = new ArrayDeque<>();
	private static Msg current;
	private static long curStart;
	private static boolean full;

	// анимация (пружина)
	private static float w = 48, h = 14, vw, vh;
	private static long lastNanos;
	private static Object stateKey;
	private static long stateSince;

	// режим правки
	private static int dragMode;      // 0 нет, 1 двигать, 2 левая ручка, 3 правая ручка, 4 размер
	private static int hoverCode;
	private static boolean wasDown;
	private static double startMx, startMy;
	private static float sOffX, sOffY, sExtra, sScale;
	private static boolean pendingSettings;

	// иконка сервера (кэш)
	private static int iconKey;
	private static int[] iconPx;
	private static boolean iconTried;

	private IslandHud() {}

	// ------------------------------------------------------------ события

	public static synchronized void toggleFull() {
		full = !full;
	}

	/** Вызывается каждый тик клиента. */
	public static synchronized void tick(Minecraft mc) {
		if (pendingSettings) {
			pendingSettings = false;
			mc.setScreen(new SettingsScreen(mc.screen));
		}
	}

	public static synchronized void onMessage(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (!Cfg.on("chat")) return;
		String raw = component.getString().replaceAll("§.", "").strip();
		if (raw.isEmpty()) return;

		String nick = null;
		String text = raw;

		Matcher m = VANILLA.matcher(raw);
		if (m.matches()) {
			nick = m.group(1).replaceAll("^(\\[[^\\]]*\\]\\s*)+", "").strip();
			int sp = nick.lastIndexOf(' ');
			if (sp >= 0) nick = nick.substring(sp + 1);
			text = m.group(2).strip();
		} else {
			m = GENERIC.matcher(raw);
			if (m.matches()) {
				nick = m.group(1);
				text = m.group(2).strip();
			}
		}

		// ник из формата должен быть реальным игроком, иначе ищем игрока в начале сообщения
		String real = (nick != null && isPlayer(mc, nick)) ? nick : null;
		if (real == null) {
			String found = findSender(mc, raw);
			if (found != null) {
				real = found;
				if (nick == null) text = raw;
			}
		}

		boolean player;
		if (real != null) {
			nick = real;
			player = true;
		} else if (nick != null && !SYSTEM_WORDS.contains(nick.toLowerCase(Locale.ROOT))) {
			player = true; // похоже на ник, но в списке игроков его нет
		} else {
			nick = serverName(mc);
			text = raw;
			player = false;
		}
		if (nick.length() > 16) nick = nick.substring(0, 16);

		if (mc.player != null && player && !Cfg.on("chatown")
				&& nick.equalsIgnoreCase(mc.player.getName().getString())) return;
		if (!player && !Cfg.on("chatserver")) return;

		if (queue.size() >= 6) queue.pollFirst();
		queue.addLast(new Msg(nick, text, player));
	}

	private static boolean isPlayer(Minecraft mc, String name) {
		var conn = mc.getConnection();
		if (conn != null && conn.getPlayerInfo(name) != null) return true;
		if (mc.level != null) {
			for (Object o : mc.level.players()) {
				if (o instanceof AbstractClientPlayer p && p.getName().getString().equalsIgnoreCase(name)) return true;
			}
		}
		return false;
	}

	private static String findSender(Minecraft mc, String raw) {
		String head = raw.length() > 70 ? raw.substring(0, 70) : raw;
		Matcher m = TOKEN.matcher(head);
		while (m.find()) {
			String tok = m.group();
			if (isPlayer(mc, tok)) return tok;
		}
		return null;
	}

	private static long popupMs() {
		return CHAT_MS[Math.min(Cfg.get("chattime"), CHAT_MS.length - 1)];
	}

	private static void advance(long now) {
		if (current != null) {
			boolean expired = now - curStart >= popupMs();
			boolean skip = !queue.isEmpty() && now - curStart >= MIN_SHOW_MS;
			if (expired || skip) current = null;
		}
		if (current == null && !queue.isEmpty()) {
			current = queue.pollFirst();
			curStart = now;
		}
	}

	// ------------------------------------------------------------ рендер

	public static synchronized void render(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		Font font = mc.font;

		long nowMs = System.nanoTime() / 1_000_000L;
		advance(nowMs);

		Mode mode = current != null ? Mode.CHAT : (full ? Mode.FULL : Mode.COMPACT);
		Object key;
		if (mode == Mode.CHAT) key = current; else key = mode;
		if (key != stateKey) {
			stateKey = key;
			stateSince = nowMs;
		}

		// ---- целевой размер
		int tw, th;
		List<String> lines = null;
		Target target = null;
		String ownHp = hpText(mc.player);

		if (mode == Mode.CHAT) {
			lines = wrap(font, current.text(), 190, 2);
			int tx = font.width(current.nick());
			for (String l : lines) tx = Math.max(tx, font.width(l));
			tw = clamp(tx + 36 + 14, 136, 262);
			th = Math.max(34, 6 + 10 + 10 * lines.size() + 9);
		} else if (mode == Mode.FULL) {
			target = findTarget(mc);
			String l1 = target != null ? target.name : "Нет цели";
			boolean hasNs = target != null && !target.ns.isEmpty();
			boolean hasTool = target != null && target.tool != null;
			boolean hasHp = target != null && target.living != null && Cfg.on("jhp");
			int textW = font.width(l1);
			if (hasNs) textW = Math.max(textW, font.width(target.ns));
			if (hasTool) textW = Math.max(textW, font.width(target.tool));
			if (hasHp) textW = Math.max(textW, 12 + font.width(hpText(target.living)));
			String foot = footText(mc);
			int footW = 22 + font.width(ownHp) + (foot.isEmpty() ? 0 : 10 + font.width(foot)) + 12;
			tw = clamp(Math.max(32 + textW + 14, footW), 170, 340);
			th = headerHeight(hasNs, hasTool, hasHp) + 19;
		} else {
			String name = mc.player.getName().getString();
			boolean head = Cfg.on("head");
			int tx = Math.max(font.width(name), 12 + font.width(ownHp));
			int right = Math.max(clockText() == null ? 0 : font.width(clockText()),
					fpsText(mc) == null ? 0 : font.width(fpsText(mc)));
			tw = Math.max(104, (head ? 33 : 12) + tx + (right > 0 ? right + 14 : 0) + 14);
			th = 32;
		}

		// ---- пружина
		long nowNs = System.nanoTime();
		float dtSec = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nowNs - lastNanos) / 1_000_000_000f);
		lastNanos = nowNs;
		int bounce = Cfg.get("bounce");
		final float k = bounce == 2 ? 380f : 300f;
		final float c = bounce == 0 ? 34.6f : (bounce == 2 ? 14f : 24f);
		float rem = dtSec;
		while (rem > 0f) {
			float s = Math.min(rem, 0.004f);
			vw += ((tw - w) * k - vw * c) * s;
			w += vw * s;
			vh += ((th - h) * k - vh * c) * s;
			h += vh * s;
			rem -= s;
		}
		w = Math.max(w, 36f);
		h = Math.max(h, 14f);

		float br = Cfg.on("breath") ? (float) Math.sin(nowMs / 600.0) : 0f;
		int iw = Math.max(44, Math.round(w + Cfg.extraW + br * 1.5f));
		int ih = Math.max(14, Math.round(h + br * 0.8f));
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();

		boolean editing = mc.screen instanceof ChatScreen;
		if (editing) {
			handleEdit(mc, sw, sh, iw, ih);
		} else if (dragMode != 0) {
			dragMode = 0;
			wasDown = false;
			Cfg.save();
		}

		float ratio = mc.player.getMaxHealth() <= 0 ? 1f : mc.player.getHealth() / mc.player.getMaxHealth();
		boolean low = Cfg.on("pulse") && ratio < 0.3f;

		var pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate((float) Math.round(sw / 2f + Cfg.offX), (float) Math.round(5 + Cfg.offY));
			pose.scale(Cfg.scale, Cfg.scale);
			int x = -iw / 2;
			int y = 0;

			drawBody(g, x, y, iw, ih, nowMs, low);

			// появление контента после начала морфа
			float p = clamp01((nowMs - stateSince - 60) / 200f);
			int a = Math.round(255f * p * p * (3f - 2f * p));
			if (a > 4) {
				g.enableScissor(x + 2, y + 1, x + iw - 2, y + ih - 1);
				if (mode == Mode.CHAT) {
					drawChat(g, mc, font, x, y, iw, ih, a, lines, nowMs);
				} else if (mode == Mode.FULL) {
					drawFull(g, mc, font, x, y, iw, ih, a, target, ownHp);
				} else {
					drawCompact(g, mc, font, x, y, iw, ih, a, ownHp);
				}
				g.disableScissor();
			}
			if (editing) drawEditOverlay(g, font, x, y, iw, ih);
		} finally {
			pose.popMatrix();
		}
	}

	// ------------------------------------------------------------ корпус (чёрный / стекло)

	private static void drawBody(GuiGraphicsExtractor g, int x, int y, int w, int h, long now, boolean low) {
		int style = Cfg.get("style");
		int rimMode = Cfg.get("rim");
		float pulse = 0.55f + 0.45f * (float) Math.sin(now / 160.0);

		if (style == 0) {
			if (low) {
				pillA(g, x - 1, y - 1, w + 2, h + 2, 0xFF3B30, pulse);
			} else if (rimMode == 3) {
				final long t = now;
				final int hh = h;
				pillRows(g, x - 1, y - 1, w + 2, h + 2,
						r -> 0xFF000000 | hsv(t / 3000f + r / (float) (hh + 2) * 0.35f, 0.75f, 1f));
			} else if (rimMode == 2) {
				pill(g, x - 1, y - 1, w + 2, h + 2, 0xFF6A6A72);
			} else if (rimMode == 1) {
				pill(g, x - 1, y - 1, w + 2, h + 2, 0xFF2B2B30);
			}
			pill(g, x, y, w, h, 0xFF000000);
			return;
		}

		float op = OPACITY[Math.min(Cfg.get("opacity"), OPACITY.length - 1)];
		int tint = TINTS[Math.min(Cfg.get("tint"), TINTS.length - 1)];

		// мягкая тень
		pillA(g, x - 2, y, w + 4, h + 4, 0x000000, 0.14f);
		pillA(g, x - 1, y + 1, w + 2, h + 2, 0x000000, 0.14f);

		if (style == 3) {
			// светлое матовое стекло на тёмной подложке, чтобы текст читался
			pillA(g, x, y, w, h, 0x000000, 0.34f * op);
			pillA(g, x, y, w, h, 0xDCE8FF, 0.20f * op);
		} else if (style == 2) {
			pillA(g, x, y, w, h, 0x000000, 0.74f * op);
		} else {
			pillA(g, x, y, w, h, 0x0E1218, 0.58f * op);
		}

		// цветной оттенок снизу вверх
		if (tint >= 0) {
			final int tc = tint;
			final int hh = h;
			final float o2 = op;
			pillRows(g, x, y, w, h, r -> argb(tc, 0.10f * o2 + 0.26f * o2 * (r / (float) hh)));
		}

		// мягкий блик сверху (как на жидком стекле iOS)
		{
			final int hh = h;
			final float o2 = op;
			pillRows(g, x, y, w, h, r -> {
				float f = 1f - r / (float) hh;
				return argb(0xFFFFFF, 0.16f * f * f * (0.5f + 0.5f * o2));
			});
		}

		// блик-«линза» у верхнего края
		int rr = Math.min(h / 2, 15);
		if (w > rr * 2 + 8) {
			g.fill(x + rr, y + 1, x + w - rr, y + 2, argb(0xFFFFFF, 0.38f));
			g.fill(x + rr + 3, y + 2, x + w - rr - 3, y + 3, argb(0xFFFFFF, 0.14f));
			g.fill(x + rr + 3, y + h - 2, x + w - rr - 3, y + h - 1, argb(0xFFFFFF, 0.07f));
		}

		// обводка
		if (low) {
			ring(g, x, y, w, h, r -> argb(0xFF3B30, pulse));
		} else if (rimMode == 3) {
			final long t = now;
			final int hh = h;
			ring(g, x, y, w, h, r -> 0xFF000000 | hsv(t / 3000f + r / (float) hh * 0.35f, 0.7f, 1f));
		} else if (rimMode >= 1) {
			final int hh = h;
			final float top = rimMode == 2 ? 0.95f : 0.60f;
			final float bot = rimMode == 2 ? 0.35f : 0.16f;
			ring(g, x, y, w, h, r -> argb(0xFFFFFF, bot + (top - bot) * (1f - r / (float) hh)));
		}
	}

	// ------------------------------------------------------------ режимы

	private static void drawCompact(GuiGraphicsExtractor g, Minecraft mc, Font font,
									int x, int y, int w, int h, int a, String hp) {
		var p = mc.player;
		int tx = x + 12;
		if (Cfg.on("head")) {
			if (a > 120) PlayerFaceExtractor.extractRenderState(g, p.getSkin(), x + 7, y + (h - 20) / 2, 20);
			tx = x + 33;
		}
		text(g, font, p.getName().getString(), tx, y + 6, 0xFFFFFF, a);
		heart(g, tx, y + 18, a);
		text(g, font, hp, tx + 12, y + 18, hpColor(p), a);

		String clock = clockText();
		String fps = fpsText(mc);
		int rx = x + w - 10;
		if (clock != null) text(g, font, clock, rx - font.width(clock), y + 6, 0xB0B0B8, a);
		if (fps != null) text(g, font, fps, rx - font.width(fps), y + 18, 0x8E8E93, a);
	}

	private static void drawChat(GuiGraphicsExtractor g, Minecraft mc, Font font,
								 int x, int y, int w, int h, int a, List<String> lines, long now) {
		Msg m = current;
		if (m == null || lines == null) return;
		if (m.player()) {
			drawHeadOf(g, mc, m.nick(), x + 7, y + (h - 22) / 2, 22, a);
		} else {
			drawServerIcon(g, mc, x + 7, y + (h - 22) / 2, 22, a);
		}
		int accent = ACCENTS[Math.min(Cfg.get("accent"), ACCENTS.length - 1)];
		text(g, font, m.nick(), x + 36, y + 6, accent, a);
		for (int i = 0; i < lines.size(); i++) {
			text(g, font, lines.get(i), x + 36, y + 17 + 10 * i, 0xEDEDED, a);
		}
		if (Cfg.on("chatbar")) {
			float frac = clamp01(1f - (now - curStart) / (float) popupMs());
			int bx = x + 36, bw = Math.max(0, w - 36 - 12);
			g.fill(bx, y + h - 5, bx + bw, y + h - 3, col(0xFFFFFF, a / 5));
			g.fill(bx, y + h - 5, bx + Math.round(bw * frac), y + h - 3, col(accent, a));
		}
	}

	private static void drawFull(GuiGraphicsExtractor g, Minecraft mc, Font font,
								 int x, int y, int w, int h, int a, Target t, String ownHp) {
		boolean hasNs = t != null && !t.ns.isEmpty();
		boolean hasTool = t != null && t.tool != null;
		boolean hasHp = t != null && t.living != null && Cfg.on("jhp");
		int hh = headerHeight(hasNs, hasTool, hasHp);

		if (t == null) {
			text(g, font, "Нет цели", x + 12, y + 7, 0x8E8E93, a);
		} else {
			if (t.player != null) {
				if (a > 120) PlayerFaceExtractor.extractRenderState(g, t.player.getSkin(), x + 8, y + 7, 18);
			} else if (!t.stack.isEmpty() && a > 120) {
				g.item(t.stack, x + 8, y + 7);
			}
			text(g, font, t.name, x + 32, y + 7, 0xFFFFFF, a);
			int ly = y + 17;
			if (hasNs) {
				text(g, font, t.ns, x + 32, ly, 0x7B8CFF, a);
				ly += 10;
			}
			if (hasTool) {
				text(g, font, t.tool, x + 32, ly, t.toolColor, a);
				ly += 10;
			}
			if (hasHp) {
				heart(g, x + 32, ly, a);
				text(g, font, hpText(t.living), x + 44, ly, hpColor(t.living), a);
			}
		}

		// разделитель + своя строка
		g.fill(x + 10, y + hh, x + w - 10, y + hh + 1, col(0xFFFFFF, a / 6));
		int fy = y + hh + 6;
		heart(g, x + 10, fy, a);
		text(g, font, ownHp, x + 22, fy, hpColor(mc.player), a);
		String foot = footText(mc);
		if (!foot.isEmpty()) {
			text(g, font, foot, x + 22 + font.width(ownHp) + 10, fy, 0xB0B0B8, a);
		}
	}

	private static int headerHeight(boolean hasNs, boolean hasTool, boolean hasHp) {
		return Math.max(30, 7 + 10 + (hasNs ? 10 : 0) + (hasTool ? 10 : 0) + (hasHp ? 10 : 0) + 5);
	}

	private static String footText(Minecraft mc) {
		StringBuilder sb = new StringBuilder();
		if (Cfg.on("jfood")) sb.append("Еда ").append(mc.player.getFoodData().getFoodLevel());
		if (Cfg.on("jcoords")) {
			BlockPos bp = mc.player.blockPosition();
			if (sb.length() > 0) sb.append("  ·  ");
			sb.append(bp.getX()).append(' ').append(bp.getY()).append(' ').append(bp.getZ());
		}
		return sb.toString();
	}

	private static String clockText() {
		return Cfg.on("clock") ? LocalTime.now().format(HHMM) : null;
	}

	private static String fpsText(Minecraft mc) {
		if (!Cfg.on("fps")) return null;
		Object v = call(mc, "getFps", NO);
		return v instanceof Number n ? n.intValue() + " FPS" : null;
	}

	// ------------------------------------------------------------ "Jade"

	private static HitResult pickWithFluids(Minecraft mc) {
		Object r = call(mc.player, "pick", new Class<?>[] {double.class, float.class, boolean.class}, 5.0, 1.0f, true);
		return r instanceof HitResult hr ? hr : null;
	}

	private static Target findTarget(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (!(hit instanceof EntityHitResult) && Cfg.on("jfluid")) {
			HitResult f = pickWithFluids(mc);
			if (f != null) hit = f;
		}
		if (hit == null) return null;

		double dist = mc.player.getEyePosition().distanceTo(hit.getLocation());
		String distText = Cfg.on("jdist") ? String.format(Locale.ROOT, "%.1f м", dist) : null;

		if (hit instanceof EntityHitResult eh) {
			Entity e = eh.getEntity();
			if (e == null || e == mc.player) return null;
			Target t = new Target();
			t.name = e.getDisplayName().getString();
			String mod = pretty(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace());
			t.ns = joinInfo(Cfg.on("jmod") ? mod : null, distText);
			if (e instanceof LivingEntity le) t.living = le;
			if (e instanceof AbstractClientPlayer acp) t.player = acp;
			return t;
		}
		if (hit instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(bh.getBlockPos());
			if (state.isAir()) return null;
			Target t = new Target();
			t.name = state.getBlock().getName().getString();
			var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
			t.ns = joinInfo(Cfg.on("jmod") ? pretty(id.getNamespace()) : null, distText);
			String path = id.getPath();
			if (path.equals("water")) {
				t.stack = new ItemStack(Items.WATER_BUCKET);
			} else if (path.equals("lava")) {
				t.stack = new ItemStack(Items.LAVA_BUCKET);
			} else {
				t.stack = new ItemStack(state.getBlock());
				if (Cfg.on("jtool")) fillTool(mc, state, t);
			}
			return t;
		}
		return null;
	}

	private static String joinInfo(String a, String b) {
		if (a == null || a.isEmpty()) return b == null ? "" : b;
		if (b == null || b.isEmpty()) return a;
		return a + "  ·  " + b;
	}

	/** Чем добывать: инструмент и минимальный уровень. */
	private static void fillTool(Minecraft mc, BlockState state, Target t) {
		String noun;
		boolean fem = true;
		if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) noun = "кирка";
		else if (state.is(BlockTags.MINEABLE_WITH_AXE)) { noun = "топор"; fem = false; }
		else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) noun = "лопата";
		else if (state.is(BlockTags.MINEABLE_WITH_HOE)) noun = "мотыга";
		else return;

		if (!state.requiresCorrectToolForDrops()) {
			t.tool = "Лучше: " + noun;
			t.toolColor = 0xB0B0B8;
			return;
		}
		String stem = null;
		if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) stem = "алмазн";
		else if (state.is(BlockTags.NEEDS_IRON_TOOL)) stem = "железн";
		else if (state.is(BlockTags.NEEDS_STONE_TOOL)) stem = "каменн";
		String adj = stem == null ? (fem ? "любая" : "любой") : stem + (fem ? "ая" : "ый");
		t.tool = "Нужно: " + adj + " " + noun + (stem != null ? " +" : "");
		boolean ok = mc.player.getMainHandItem().isCorrectToolForDrops(state);
		t.toolColor = ok ? 0x55FF55 : 0xFF5555;
	}

	private static String pretty(String ns) {
		if (ns == null || ns.isEmpty()) return "";
		return Character.toUpperCase(ns.charAt(0)) + ns.substring(1);
	}

	// ------------------------------------------------------------ HP

	private static String hpText(LivingEntity e) {
		String s;
		if (Cfg.get("hpstyle") == 1) {
			float max = e.getMaxHealth();
			s = (max <= 0 ? 100 : Math.round(e.getHealth() / max * 100f)) + "%";
		} else {
			s = fmt(e.getHealth()) + " / " + fmt(e.getMaxHealth());
		}
		float abs = e.getAbsorptionAmount();
		if (abs > 0.01f) s += " +" + fmt(abs);
		return s;
	}

	private static int hpColor(LivingEntity e) {
		float ratio = e.getMaxHealth() <= 0 ? 1f : e.getHealth() / e.getMaxHealth();
		if (ratio > 0.5f) return 0x55FF55;
		if (ratio > 0.25f) return 0xFFFF55;
		return 0xFF5555;
	}

	private static String fmt(float v) {
		if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
		return String.format(Locale.ROOT, "%.1f", v);
	}

	// ------------------------------------------------------------ головы и иконка сервера

	private static void drawHeadOf(GuiGraphicsExtractor g, Minecraft mc, String nick,
								   int x, int y, int size, int a) {
		if (a <= 120) return;
		var conn = mc.getConnection();
		var info = conn == null ? null : conn.getPlayerInfo(nick);
		if (info != null) {
			PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
			return;
		}
		if (mc.level != null) {
			for (Object o : mc.level.players()) {
				if (o instanceof AbstractClientPlayer p && p.getName().getString().equalsIgnoreCase(nick)) {
					PlayerFaceExtractor.extractRenderState(g, p.getSkin(), x, y, size);
					return;
				}
			}
		}
		// заглушка: серый квадрат с буквой
		g.fill(x, y, x + size, y + size, col(0x3A3A3C, a));
		String letter = nick.isEmpty() ? "?" : nick.substring(0, 1).toUpperCase(Locale.ROOT);
		Font font = mc.font;
		text(g, font, letter, x + (size - font.width(letter)) / 2, y + (size - 8) / 2, 0xFFFFFF, a);
	}

	private static String serverName(Minecraft mc) {
		Object sd = call(mc, "getCurrentServer", NO);
		Object n = field(sd, "name");
		if (n instanceof String s && !s.isBlank()) return s.length() > 16 ? s.substring(0, 16) : s;
		return "Сервер";
	}

	private static byte[] serverIconBytes(Minecraft mc) {
		Object sd = call(mc, "getCurrentServer", NO);
		Object r = call(sd, "getIconBytes", NO);
		return r instanceof byte[] b ? b : null;
	}

	private static void drawServerIcon(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int size, int a) {
		if (a <= 40) return;
		byte[] b = serverIconBytes(mc);
		int[] px = null;
		if (b != null && b.length > 0) {
			int key = Arrays.hashCode(b);
			if (!iconTried || key != iconKey) {
				iconKey = key;
				iconPx = decodeIcon(b, size);
				iconTried = true;
			}
			px = iconPx;
		}
		if (px != null && px.length == size * size) {
			for (int r = 0; r < size; r++) {
				int start = 0;
				int cur = px[r * size];
				for (int i = 1; i <= size; i++) {
					int c = i < size ? px[r * size + i] : cur + 1;
					if (i == size || c != cur) {
						int alpha = ((cur >>> 24) * a) / 255;
						if (alpha > 0) g.fill(x + start, y + r, x + i, y + r + 1, (alpha << 24) | (cur & 0xFFFFFF));
						start = i;
						cur = c;
					}
				}
			}
			return;
		}
		// запасная иконка: «стойка сервера»
		g.fill(x, y, x + size, y + size, col(0x2C2C2E, a));
		int bar = Math.max(3, (size - 6) / 3 - 2);
		for (int i = 0; i < 3; i++) {
			int by = y + 3 + i * ((size - 6) / 3);
			g.fill(x + 3, by, x + size - 3, by + bar, col(0x48484A, a));
			g.fill(x + size - 7, by + 1, x + size - 5, by + 3, col(0x30D158, a));
		}
	}

	private static int[] decodeIcon(byte[] b, int size) {
		try {
			BufferedImage src = ImageIO.read(new ByteArrayInputStream(b));
			if (src == null) return null;
			BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			var g2 = dst.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2.drawImage(src, 0, 0, size, size, null);
			g2.dispose();
			int[] px = new int[size * size];
			dst.getRGB(0, 0, size, size, px, 0, size);
			return px;
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------ режим правки (открыт чат)

	private static double[] mouseGui(Minecraft mc) {
		Object mh = field(mc, "mouseHandler");
		if (mh == null) return null;
		Object xo = call(mh, "xpos", NO);
		Object yo = call(mh, "ypos", NO);
		Object so = call(mc.getWindow(), "getGuiScale", NO);
		Object lo = call(mh, "isLeftPressed", NO);
		if (!(xo instanceof Number nx) || !(yo instanceof Number ny)) return null;
		double sc = so instanceof Number ns && ns.doubleValue() > 0 ? ns.doubleValue() : 1.0;
		boolean down = lo instanceof Boolean bb && bb;
		return new double[] {nx.doubleValue() / sc, ny.doubleValue() / sc, down ? 1.0 : 0.0};
	}

	private static int hitTest(float lx, float ly, int iw, int ih) {
		int x0 = -iw / 2;
		if (in(lx, ly, x0 + iw + 2, ih + 2, 9, 9)) return 4;
		if (in(lx, ly, x0 - 9, ih / 2 - 8, 7, 16)) return 2;
		if (in(lx, ly, x0 + iw + 2, ih / 2 - 8, 7, 16)) return 3;
		if (in(lx, ly, -58, ih + 6, 56, 13)) return 10;
		if (in(lx, ly, 2, ih + 6, 40, 13)) return 11;
		if (in(lx, ly, x0, 0, iw, ih)) return 1;
		return 0;
	}

	private static boolean in(float px, float py, int x, int y, int w, int h) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}

	private static void handleEdit(Minecraft mc, int sw, int sh, int iw, int ih) {
		double[] m = mouseGui(mc);
		if (m == null) return;
		boolean down = m[2] > 0.5;
		float s = Cfg.scale;
		float cx = Math.round(sw / 2f + Cfg.offX);
		float top = Math.round(5 + Cfg.offY);
		float lx = (float) ((m[0] - cx) / s);
		float ly = (float) ((m[1] - top) / s);
		hoverCode = hitTest(lx, ly, iw, ih);

		if (down && !wasDown) {
			if (hoverCode == 10) {
				pendingSettings = true;
			} else if (hoverCode == 11) {
				Cfg.resetGeometry();
			} else if (hoverCode >= 1 && hoverCode <= 4) {
				dragMode = hoverCode;
				startMx = m[0];
				startMy = m[1];
				sOffX = Cfg.offX;
				sOffY = Cfg.offY;
				sExtra = Cfg.extraW;
				sScale = Cfg.scale;
			}
		} else if (down && dragMode != 0) {
			float dx = (float) (m[0] - startMx);
			float dy = (float) (m[1] - startMy);
			switch (dragMode) {
				case 1 -> {
					Cfg.offX = clampF(sOffX + dx, -sw / 2f + 24f, sw / 2f - 24f);
					Cfg.offY = clampF(sOffY + dy, -4f, sh - 40f);
				}
				case 2 -> Cfg.extraW = clampF(sExtra - 2f * dx / sScale, -80f, 420f);
				case 3 -> Cfg.extraW = clampF(sExtra + 2f * dx / sScale, -80f, 420f);
				case 4 -> Cfg.scale = clampF(sScale + dx / 160f, 0.6f, 2.2f);
				default -> { }
			}
		} else if (!down && dragMode != 0) {
			dragMode = 0;
			Cfg.save();
		}
		wasDown = down;
	}

	private static void drawEditOverlay(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h) {
		int edge = 0xAA5AC8FA;
		g.fill(x - 3, y - 3, x + w + 3, y - 2, edge);
		g.fill(x - 3, y + h + 2, x + w + 3, y + h + 3, edge);
		g.fill(x - 3, y - 3, x - 2, y + h + 3, edge);
		g.fill(x + w + 2, y - 3, x + w + 3, y + h + 3, edge);

		handle(g, x - 9, h / 2 - 8, 7, 16, dragMode == 2 || hoverCode == 2);
		handle(g, x + w + 2, h / 2 - 8, 7, 16, dragMode == 3 || hoverCode == 3);
		handle(g, x + w + 2, h + 2, 9, 9, dragMode == 4 || hoverCode == 4);

		button(g, font, "Настройки", -58, h + 6, 56, 13, hoverCode == 10);
		button(g, font, "Сброс", 2, h + 6, 40, 13, hoverCode == 11);

		String hint = "тяни остров · боковые ручки: ширина · уголок: размер";
		g.text(font, hint, -font.width(hint) / 2, h + 22, 0xFF8E8E93, false);
	}

	private static void handle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active) {
		g.fill(x, y, x + w, y + h, active ? 0xFFFFFFFF : 0xFF5AC8FA);
	}

	private static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover) {
		g.fill(x, y, x + w, y + h, hover ? 0xEE3A3A3C : 0xEE1C1C1E);
		g.fill(x, y, x + w, y + 1, 0xFF5AC8FA);
		g.fill(x, y + h - 1, x + w, y + h, 0xFF5AC8FA);
		g.fill(x, y, x + 1, y + h, 0xFF5AC8FA);
		g.fill(x + w - 1, y, x + w, y + h, 0xFF5AC8FA);
		g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, 0xFFFFFFFF, false);
	}

	// ------------------------------------------------------------ примитивы

	private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int rgb, int a) {
		g.text(font, s, x, y, col(rgb, a), Cfg.on("tshadow"));
	}

	private static int col(int rgb, int a) {
		return (clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static int argb(int rgb, float alpha) {
		return (clamp(Math.round(alpha * 255f), 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static int hsv(float hue, float sat, float val) {
		hue = hue - (float) Math.floor(hue);
		float c = val * sat;
		float xx = c * (1f - Math.abs((hue * 6f) % 2f - 1f));
		float m = val - c;
		float rr = 0f, gg = 0f, bb = 0f;
		switch ((int) (hue * 6f) % 6) {
			case 0 -> { rr = c; gg = xx; }
			case 1 -> { rr = xx; gg = c; }
			case 2 -> { gg = c; bb = xx; }
			case 3 -> { gg = xx; bb = c; }
			case 4 -> { rr = xx; bb = c; }
			default -> { rr = c; bb = xx; }
		}
		int r = clamp(Math.round((rr + m) * 255f), 0, 255);
		int gi = clamp(Math.round((gg + m) * 255f), 0, 255);
		int b = clamp(Math.round((bb + m) * 255f), 0, 255);
		return (r << 16) | (gi << 8) | b;
	}

	private static final String[] HEART = {
			"011000110",
			"111101111",
			"111111111",
			"111111111",
			"011111110",
			"001111100",
			"000111000",
			"000010000"
	};

	private static void heart(GuiGraphicsExtractor g, int x, int y, int a) {
		int c = col(0xFF3B4B, a);
		for (int r = 0; r < HEART.length; r++) {
			String row = HEART[r];
			int start = -1;
			for (int i = 0; i <= row.length(); i++) {
				boolean on = i < row.length() && row.charAt(i) == '1';
				if (on && start < 0) start = i;
				if (!on && start >= 0) {
					g.fill(x + start, y + r, x + i, y + r + 1, c);
					start = -1;
				}
			}
		}
	}

	private static int insetAt(int row, int h, int r) {
		int dy;
		if (row < r) dy = r - row;
		else if (row >= h - r) dy = row - (h - r) + 1;
		else dy = 0;
		if (dy <= 0) return 0;
		double d = dy - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - d * d)));
	}

	/** Скруглённая "таблетка" одним цветом. */
	private static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
		int r = Math.min(h / 2, 15);
		for (int row = 0; row < h; row++) {
			int inset = insetAt(row, h, r);
			int x1 = x + inset, x2 = x + w - inset;
			if (x2 > x1) g.fill(x1, y + row, x2, y + row + 1, argb);
		}
	}

	private static void pillA(GuiGraphicsExtractor g, int x, int y, int w, int h, int rgb, float alpha) {
		pill(g, x, y, w, h, argb(rgb, alpha));
	}

	/** Таблетка, цвет которой зависит от строки (градиенты). */
	private static void pillRows(GuiGraphicsExtractor g, int x, int y, int w, int h, IntUnaryOperator colorAtRow) {
		int r = Math.min(h / 2, 15);
		for (int row = 0; row < h; row++) {
			int c = colorAtRow.applyAsInt(row);
			if ((c >>> 24) == 0) continue;
			int inset = insetAt(row, h, r);
			int x1 = x + inset, x2 = x + w - inset;
			if (x2 > x1) g.fill(x1, y + row, x2, y + row + 1, c);
		}
	}

	/** Тонкая обводка по контуру таблетки. */
	private static void ring(GuiGraphicsExtractor g, int x, int y, int w, int h, IntUnaryOperator colorAtRow) {
		int r = Math.min(h / 2, 15);
		for (int row = 0; row < h; row++) {
			int c = colorAtRow.applyAsInt(row);
			if ((c >>> 24) == 0) continue;
			int inset = insetAt(row, h, r);
			int x1 = x + inset, x2 = x + w - inset;
			if (x2 <= x1) continue;
			if (row == 0 || row == h - 1) {
				g.fill(x1, y + row, x2, y + row + 1, c);
				continue;
			}
			int next = row < h / 2 ? insetAt(row - 1, h, r) : insetAt(row + 1, h, r);
			int seg = Math.max(1, Math.abs(next - inset));
			g.fill(x1, y + row, Math.min(x1 + seg, x2), y + row + 1, c);
			g.fill(Math.max(x2 - seg, x1), y + row, x2, y + row + 1, c);
		}
	}

	// ------------------------------------------------------------ текст

	private static List<String> wrap(Font font, String text, int maxW, int maxLines) {
		List<String> out = new ArrayList<>();
		String rest = text.replace('\n', ' ').strip();
		while (!rest.isEmpty() && out.size() < maxLines) {
			if (font.width(rest) <= maxW) {
				out.add(rest);
				break;
			}
			boolean last = out.size() == maxLines - 1;
			int cut = fit(font, rest, maxW - (last ? font.width("...") : 0));
			if (cut <= 0) cut = 1;
			if (last) {
				out.add(rest.substring(0, cut).stripTrailing() + "...");
				break;
			}
			int sp = rest.lastIndexOf(' ', cut);
			if (sp > cut / 3) {
				out.add(rest.substring(0, sp));
				rest = rest.substring(sp + 1).stripLeading();
			} else {
				out.add(rest.substring(0, cut));
				rest = rest.substring(cut);
			}
		}
		if (out.isEmpty()) out.add("");
		return out;
	}

	/** Наибольшее n, при котором ширина s[0..n) <= maxW. */
	private static int fit(Font font, String s, int maxW) {
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (font.width(s.substring(0, mid)) <= maxW) lo = mid; else hi = mid - 1;
		}
		return lo;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clampF(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	// ------------------------------------------------------------ рефлексия (на случай изменений API)

	private static Object call(Object target, String name, Class<?>[] types, Object... args) {
		if (target == null) return null;
		try {
			Method m = target.getClass().getMethod(name, types);
			try {
				m.setAccessible(true);
			} catch (Throwable ignored) {
				// публичный метод вызовется и так
			}
			return m.invoke(target, args);
		} catch (Throwable t) {
			return null;
		}
	}

	private static Object field(Object target, String name) {
		if (target == null) return null;
		try {
			return target.getClass().getField(name).get(target);
		} catch (Throwable t) {
			return null;
		}
	}
}

/** Настройки мода (хранятся в config/dynamicisland.properties). */
final class Cfg {
	static final class Opt {
		final String key;
		final String label;
		final int def;
		final String[] choices;

		Opt(String key, String label, int def, String[] choices) {
			this.key = key;
			this.label = label;
			this.def = def;
			this.choices = choices;
		}
	}

	static final List<Opt> OPTS = new ArrayList<>();
	private static final Map<String, Integer> VALUES = new HashMap<>();
	private static final String[] ONOFF = {"Выкл", "Вкл"};

	// положение и размер (меняются в режиме правки)
	static float offX, offY, extraW, scale = 1f;
	private static boolean loaded;

	private static void add(String key, String label, int def, String... choices) {
		OPTS.add(new Opt(key, label, def, choices));
	}

	private static void flag(String key, String label, boolean def) {
		OPTS.add(new Opt(key, label, def ? 1 : 0, ONOFF));
	}

	static {
		// вид
		add("style", "Стиль", 1, "Чёрный", "Стекло", "Тёмное стекло", "Светлое стекло");
		add("opacity", "Прозрачность стекла", 1, "100%", "85%", "70%", "55%", "40%");
		add("tint", "Оттенок стекла", 0, "Нет", "Синий", "Розовый", "Зелёный", "Фиолетовый", "Янтарный");
		add("rim", "Обводка", 1, "Нет", "Тонкая", "Яркая", "Радужная");
		add("bounce", "Пружина", 1, "Нет", "Мягкая", "Сильная");
		add("accent", "Цвет ника в чате", 0, "Голубой", "Розовый", "Зелёный", "Оранжевый", "Белый");
		flag("tshadow", "Тень текста", false);
		flag("pulse", "Красный пульс при малом HP", true);
		flag("breath", "Дыхание острова", false);
		// обычный остров
		flag("head", "Голова в обычном режиме", true);
		add("hpstyle", "Формат HP", 0, "Числа", "Проценты");
		flag("clock", "Часы", false);
		flag("fps", "FPS", false);
		// чат
		flag("chat", "Сообщения чата", true);
		add("chattime", "Время сообщения", 2, "3 сек", "5 сек", "10 сек", "15 сек", "20 сек");
		flag("chatown", "Мои сообщения", true);
		flag("chatserver", "Сообщения сервера", true);
		flag("chatbar", "Полоска времени", true);
		// Jade
		flag("jtool", "Чем добывать блок", true);
		flag("jfluid", "Вода и лава", true);
		flag("jmod", "Название мода", true);
		flag("jhp", "HP цели", true);
		flag("jdist", "Расстояние до цели", false);
		flag("jfood", "Еда в нижней строке", true);
		flag("jcoords", "Координаты", true);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("dynamicisland.properties");
	}

	static int get(String key) {
		if (!loaded) load();
		Integer v = VALUES.get(key);
		if (v != null) return v;
		for (Opt o : OPTS) {
			if (o.key.equals(key)) return o.def;
		}
		return 0;
	}

	static boolean on(String key) {
		return get(key) == 1;
	}

	static String text(Opt o) {
		return o.label + ": " + o.choices[Math.min(get(o.key), o.choices.length - 1)];
	}

	static void cycle(Opt o) {
		int v = (get(o.key) + 1) % o.choices.length;
		VALUES.put(o.key, v);
		save();
	}

	static void resetGeometry() {
		offX = 0f;
		offY = 0f;
		extraW = 0f;
		scale = 1f;
		save();
	}

	static void load() {
		loaded = true;
		try {
			Path p = file();
			if (!Files.exists(p)) return;
			Properties pr = new Properties();
			try (var in = Files.newInputStream(p)) {
				pr.load(in);
			}
			for (Opt o : OPTS) {
				String s = pr.getProperty("opt." + o.key);
				if (s != null) {
					int v = Integer.parseInt(s.trim());
					if (v >= 0 && v < o.choices.length) VALUES.put(o.key, v);
				}
			}
			offX = Float.parseFloat(pr.getProperty("offX", "0"));
			offY = Float.parseFloat(pr.getProperty("offY", "0"));
			extraW = Float.parseFloat(pr.getProperty("extraW", "0"));
			scale = Float.parseFloat(pr.getProperty("scale", "1"));
			if (!(scale >= 0.6f && scale <= 2.2f)) scale = 1f;
		} catch (Throwable ignored) {
			// битый конфиг — остаются значения по умолчанию
		}
	}

	static void save() {
		try {
			Properties pr = new Properties();
			for (Opt o : OPTS) {
				pr.setProperty("opt." + o.key, String.valueOf(get(o.key)));
			}
			pr.setProperty("offX", String.valueOf(offX));
			pr.setProperty("offY", String.valueOf(offY));
			pr.setProperty("extraW", String.valueOf(extraW));
			pr.setProperty("scale", String.valueOf(scale));
			Path p = file();
			Files.createDirectories(p.getParent());
			try (var out = Files.newOutputStream(p)) {
				pr.store(out, "Dynamic Island");
			}
		} catch (Throwable ignored) {
			// не удалось сохранить — не страшно
		}
	}
}

/** Экран настроек (открывается из Mod Menu или кнопкой "Настройки" в режиме правки). */
class SettingsScreen extends Screen {
	private static final int PER_PAGE = 10;
	private final Screen parent;
	private int page;
	private int labelY;

	SettingsScreen(Screen parent) {
		super(Component.literal("Dynamic Island"));
		this.parent = parent;
	}

	private int pageCount() {
		return (Cfg.OPTS.size() + PER_PAGE - 1) / PER_PAGE;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = Math.max(34, this.height / 2 - 82);
		int pages = pageCount();
		if (page >= pages) page = pages - 1;
		int from = page * PER_PAGE;

		for (int i = 0; i < PER_PAGE && from + i < Cfg.OPTS.size(); i++) {
			Cfg.Opt o = Cfg.OPTS.get(from + i);
			int col = i % 2;
			int row = i / 2;
			int bx = cx - 154 + col * 158;
			int by = top + row * 24;
			this.addRenderableWidget(Button.builder(Component.literal(Cfg.text(o)), btn -> {
				Cfg.cycle(o);
				btn.setMessage(Component.literal(Cfg.text(o)));
			}).bounds(bx, by, 150, 20).build());
		}

		int y1 = top + 5 * 24 + 4;
		labelY = y1 + 6;
		this.addRenderableWidget(Button.builder(Component.literal("< Назад"), btn -> {
			if (page > 0) {
				page--;
				reload();
			}
		}).bounds(cx - 154, y1, 70, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Дальше >"), btn -> {
			if (page < pageCount() - 1) {
				page++;
				reload();
			}
		}).bounds(cx + 84, y1, 70, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Сбросить положение"), btn -> Cfg.resetGeometry())
				.bounds(cx - 154, y1 + 24, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), btn -> this.onClose())
				.bounds(cx + 4, y1 + 24, 150, 20).build());
	}

	private void reload() {
		this.clearWidgets();
		this.init();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		String title = "Dynamic Island — настройки";
		graphics.text(this.font, title, this.width / 2 - this.font.width(title) / 2, 14, 0xFFFFFFFF, true);
		String p = "Страница " + (page + 1) + " / " + pageCount();
		graphics.text(this.font, p, this.width / 2 - this.font.width(p) / 2, labelY, 0xFFB0B0B8, false);
	}

	@Override
	public void onClose() {
		Cfg.save();
		this.minecraft.setScreen(this.parent);
	}
}
