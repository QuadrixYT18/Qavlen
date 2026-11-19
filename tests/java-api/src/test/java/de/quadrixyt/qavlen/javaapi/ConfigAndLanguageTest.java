package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.config.ConfigChange;
import de.quadrixyt.qavlen.config.ConfigException;
import de.quadrixyt.qavlen.config.ConfigOptions;
import de.quadrixyt.qavlen.config.ConfigSchema;
import de.quadrixyt.qavlen.config.ConfigType;
import de.quadrixyt.qavlen.config.ConfigValidationException;
import de.quadrixyt.qavlen.config.YamlConfig;
import de.quadrixyt.qavlen.language.Placeholder;
import de.quadrixyt.qavlen.language.Translations;
import de.quadrixyt.qavlen.serialization.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndLanguageTest {
    record Database(String host, int port, Duration timeout) {
    }

    @TempDir
    Path directory;

    @Test
    void configurationsWithDefaultsSchemasListenersAndBinding() throws Exception {
        ConfigSchema schema = ConfigSchema.builder()
            .required("database.host", ConfigType.STRING)
            .optional("database.port", ConfigType.INTEGER).range("database.port", 1, 65535)
            .optional("database.timeout", ConfigType.STRING)
            .rejectUnknownKeys()
            .build();
        ConfigOptions options = ConfigOptions.builder()
            .defaults(Map.of("database", Map.of("host", "localhost", "port", 3306, "timeout", "10s")))
            .schema(schema)
            .version(2)
            .migration(1, config -> config.set("database.port", 3307))
            .build();
        Path file = directory.resolve("config.yml");

        YamlConfig config = YamlConfig.load(file, options);
        List<ConfigChange> changes = new ArrayList<>();
        try (AutoCloseable ignored = config.addListener(changes::add)) {
            config.set("database.port", 5432);
            config.save();
        }

        assertEquals(5432, config.getInt("database.port", 0));
        assertEquals("localhost", config.getString("database.host"));
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).affects("database"));
        assertEquals(new Database("localhost", 5432, Duration.ofSeconds(10)), ConfigBinding.bind(config, "database", Database.class));
        assertTrue(Files.readString(file).contains("port: 5432"));

        config.set("database.extra", true);
        assertThrows(ConfigValidationException.class, config::save);
        assertThrows(ConfigException.class, () -> YamlConfig.parse("a: [broken"));
    }

    @Test
    void translationsWithPlaceholdersAndValidation() {
        Translations translations = Translations.builder()
            .defaultLocale(Locale.ENGLISH)
            .messages(Locale.ENGLISH, Map.of("coins", "{amount:choice:1#one coin|1<{amount} coins}", "hello", "Hello {name}"))
            .messages(Locale.GERMAN, Map.of("hello", "Hallo {name}"))
            .build();

        assertEquals("3 coins", translations.format(Locale.ENGLISH, "coins", Placeholder.of("amount", 3)));
        assertEquals("Hallo Alex", translations.format(Locale.GERMAN, "hello", Map.of("name", "Alex")));
        assertEquals(1, translations.validate().size());
        assertEquals(2, translations.validate(List.of("hello", "missing")).size());
    }
}
