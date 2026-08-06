package com.maxisch.client

import com.maxisch.client.gui.ApSettingsScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Puts the settings screen behind the config button in ModMenu's mod list. */
class ApModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ApSettingsScreen.create(parent) }
}
