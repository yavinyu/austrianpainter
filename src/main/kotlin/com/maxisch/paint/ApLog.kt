package com.maxisch.paint

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The one logger the mod writes to. SLF4J hands back the same instance for the same name anyway;
 * naming it once keeps the name from drifting and stops every file declaring its own field.
 */
internal object ApLog {
    val LOGGER: Logger = LoggerFactory.getLogger("austrianpainter")
}

/**
 * The two Gson configurations in use. [PRETTY] is for files a person is expected to open and edit;
 * [PLAIN] is for the downloaded room list, which nobody reads by hand.
 *
 * Positional presets are written by hand in [PresetCodec] rather than through either of these, so
 * their coordinate arrays stay on one line.
 */
internal object ApJson {
    val PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()
    val PLAIN: Gson = Gson()
}
