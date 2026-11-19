package de.quadrixyt.qavlen.adventure

import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.language.placeholder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AdventureMessagesTest {
    private val messages = AdventureMessages(
        Translations.builder()
            .messages(
                Locale.ENGLISH,
                mapOf(
                    "prefix" to "<gray>[Shop]</gray>",
                    "welcome" to "<prefix> <green>Welcome, {player}!</green> You have {coins} coins.",
                    "item" to "Bought {item}",
                    "lore" to "<gray>Price: {price}\n<prefix> <yellow>Click to buy",
                ),
            )
            .build(),
    )
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `renders markup with prefix and formatted values`() {
        val component = messages.render(Locale.ENGLISH, "welcome", placeholder("player", "Alex"), placeholder("coins", 1500))

        assertEquals("[Shop] Welcome, Alex! You have 1,500 coins.", plain.serialize(component))
    }

    @Test
    fun `untrusted placeholder values cannot inject tags`() {
        val component = messages.render(
            Locale.ENGLISH,
            "welcome",
            placeholder("player", "<red><click:run_command:/op me>x"),
            placeholder("coins", 0),
        )

        assertEquals("[Shop] Welcome, <red><click:run_command:/op me>x! You have 0 coins.", plain.serialize(component))
        assertEquals(null, component.clickEvent())
    }

    @Test
    fun `component values keep their style`() {
        val item = Component.text("Diamond", NamedTextColor.AQUA)
        val component = messages.render(Locale.ENGLISH, "item", placeholder("item", item))

        assertEquals(MiniMessage.miniMessage().deserialize("Bought <aqua>Diamond</aqua>"), component.compact())
    }

    @Test
    fun `renders multi-line translations line by line`() {
        val lines = messages.renderLines(Locale.ENGLISH, "lore", placeholder("price", 1200))

        assertEquals(listOf("Price: 1,200", "[Shop] Click to buy"), lines.map(plain::serialize))
    }

    @Test
    fun `renders configuration texts with safe placeholders and the prefix`() {
        val welcome = "<prefix> <green>Welcome, {player}! Online since {since:date:short}"

        val component = messages.renderText(
            welcome,
            Locale.ENGLISH,
            placeholder("player", "<bold>Alex"),
            placeholder("since", java.time.LocalDate.of(2025, 1, 2)),
        )

        assertEquals("[Shop] Welcome, <bold>Alex! Online since 1/2/25", plain.serialize(component))
    }
}
