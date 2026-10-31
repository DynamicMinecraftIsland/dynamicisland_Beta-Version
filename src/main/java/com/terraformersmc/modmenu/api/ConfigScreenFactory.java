package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/** Заглушка для компиляции. В готовый jar не попадает (см. build.gradle). */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
	S create(Screen parent);
}
