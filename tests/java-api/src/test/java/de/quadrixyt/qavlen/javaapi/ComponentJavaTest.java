package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.adventure.AdventureMessages;
import de.quadrixyt.qavlen.config.ConfigException;
import de.quadrixyt.qavlen.config.ConfigSection;
import de.quadrixyt.qavlen.config.JsonConfig;
import de.quadrixyt.qavlen.config.MessageComponents;
import de.quadrixyt.qavlen.config.YamlConfig;
import de.quadrixyt.qavlen.language.Placeholder;
import de.quadrixyt.qavlen.language.Translations;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentJavaTest {
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    void readsComponentsFromConfigurations() {
        YamlConfig config = YamlConfig.parse("""
                prefix: "<gray>[Shop]</gray>"
                welcome: "<prefix> <gradient:gold:yellow>Welcome, {player}!</gradient>"
                lore:
                  - "<gray>Price: {price}"
                  - "<yellow>Click to buy"
                """);

        assertEquals("<gray>[Shop]</gray>", config.getString("prefix"));
        assertEquals("default", config.getString("missing", "default"));
        Component welcome = config.getComponent("welcome", Map.of("player", "<red>Alex"));
        assertEquals("[Shop] Welcome, <red>Alex!", plain.serialize(welcome));
        assertEquals("[Shop] Welcome, {player}!", plain.serialize(config.getComponent("welcome")));
        assertNull(config.getComponentOrNull("missing"));
        assertThrows(ConfigException.class, () -> config.getComponent("missing"));

        List<Component> lore = config.getComponentList("lore", Map.of("price", 5));
        assertEquals(List.of("Price: 5", "Click to buy"), lore.stream().map(plain::serialize).toList());

        ConfigSection json = JsonConfig.parse("{\"prefix\": \"[J]\", \"messages\": {\"hello\": \"<prefix> Hi {name}\"}}");
        assertEquals("[J] Hi Bob", plain.serialize(json.getComponent("messages.hello", Map.of("name", "Bob"))));
        ConfigSection messages = json.getSection("messages");
        assertEquals("[J] Hi Bob", plain.serialize(messages.getComponent("hello", Map.of("name", "Bob"))));

        List<Component> sent = new ArrayList<>();
        Audience player = new Audience() {
            @Override
            public void sendMessage(Component message) {
                sent.add(message);
            }
        };
        player.sendMessage(config.getComponent("welcome", Map.of("player", "Alex")));
        assertEquals("[Shop] Welcome, Alex!", plain.serialize(sent.get(0)));
    }

    @Test
    void rendersTextsWithoutAFile() {
        Component item = Component.text("Diamond", NamedTextColor.AQUA);

        assertEquals("Bought Diamond", plain.serialize(MessageComponents.render("<green>Bought {item}", Map.of("item", item))));
        assertEquals("<b>", plain.serialize(MessageComponents.render("{value}", Map.of("value", "<b>"))));
        assertEquals("Hi <i>Bob", plain.serialize(MessageComponents.render("<green>Hi <player>", Map.of("player", "<i>Bob"))));
    }

    @Test
    void rendersTranslations() {
        AdventureMessages messages = new AdventureMessages(
                Translations.builder().messages(Locale.ENGLISH, Map.of("hello", "<green>Hello {player}")).build());

        Component hello = messages.render(Locale.ENGLISH, "hello", Placeholder.of("player", "<click:run_command:/stop>x"));
        assertEquals("Hello <click:run_command:/stop>x", plain.serialize(hello));
    }
}
