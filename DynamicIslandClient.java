package dev.dynamicisland;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Клиентский мод. Никаких пакетов, каналов и серверной части — поэтому
 * он не может "порвать" соединение с сервером.
 */
public class DynamicIslandClient implements ClientModInitializer {
	public static final String MOD_ID = "dynamicisland";

	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		Cfg.load();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.dynamicisland.toggle", GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				IslandHud.toggleFull();
			}
			IslandHud.tick(client);
		});

		// Сообщения игроков (подписанный чат).
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
				IslandHud.onMessage(message));

		// Системные / плагинные сообщения сервера (многие серверы шлют чат именно так).
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) IslandHud.onMessage(message);
		});

		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(MOD_ID, "island"),
				IslandHud::render);
	}
}
