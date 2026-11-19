package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.config.ConfigValidationException;
import de.quadrixyt.qavlen.serialization.Comment;
import de.quadrixyt.qavlen.serialization.ConfigFileFormat;
import de.quadrixyt.qavlen.serialization.Configs;
import de.quadrixyt.qavlen.serialization.Configuration;
import de.quadrixyt.qavlen.serialization.Range;
import de.quadrixyt.qavlen.serialization.TypedConfig;
import de.quadrixyt.qavlen.serialization.TypedConfigOptions;
import de.quadrixyt.qavlen.storage.Database;
import de.quadrixyt.qavlen.storage.DatabaseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedConfigJavaTest {
    @Configuration(header = "Shop settings")
    record ShopConfig(
        @Comment("Items per page") @Range(min = 1, max = 54) int pageSize,
        @Comment("Discount duration") Duration discount,
        List<String> categories
    ) {
        static ShopConfig defaults() {
            return new ShopConfig(27, Duration.ofHours(1), List.of("blocks", "tools"));
        }
    }

    record AppConfig(DatabaseSettings database) {
    }

    @TempDir
    Path directory;

    @Test
    void databaseSettingsInConfigurationsKeepSecretsOutOfTheFile() throws Exception {
        Path file = directory.resolve("app.yml");
        Files.writeString(file, "database:\n  url: jdbc:h2:mem:java-config\n  username: sa\n");
        TypedConfigOptions<AppConfig> options = TypedConfigOptions.<AppConfig>builder()
            .environment("APP", Map.of("APP_DATABASE_PASSWORD", "from-env"))
            .build();

        TypedConfig<AppConfig> config = Configs.open(file, AppConfig.class, options);

        assertEquals("from-env", config.getValue().database().getPassword());
        assertEquals(4, config.getValue().database().getPoolSize());
        assertTrue(!Files.readString(file).contains("from-env"), Files.readString(file));
        assertTrue(Files.readString(file).contains("pool-size: 4"), Files.readString(file));
        try (Database database = Database.connect(config.getValue().database())) {
            Integer one = database.queryFirst("SELECT 1", row -> row.getInt(1));
            assertEquals(1, one);
        }
    }

    @Test
    void recordsWithCommentsValidationAndOverrides() throws Exception {
        Path file = directory.resolve("shop.yml");
        Files.writeString(file, Configs.encode(ShopConfig.defaults(), ConfigFileFormat.YAML));
        TypedConfigOptions<ShopConfig> options = TypedConfigOptions.<ShopConfig>builder()
            .environment("SHOP", Map.of("SHOP_PAGE_SIZE", "9"))
            .build();

        TypedConfig<ShopConfig> config = Configs.open(file, ShopConfig.class, options);
        config.update(current -> new ShopConfig(current.pageSize(), Duration.ofMinutes(30), current.categories()));
        config.save();

        assertEquals(9, config.getValue().pageSize());
        String text = Files.readString(file);
        assertTrue(text.startsWith("# Shop settings\n\n# Items per page\npage-size: 27\n"), text);
        assertTrue(text.contains("discount: 30m"), text);
        assertEquals(9, config.update(current -> new ShopConfig(40, current.discount(), current.categories())).pageSize());

        TypedConfig<ShopConfig> memory = Configs.parse(text, ShopConfig.class, ConfigFileFormat.YAML);
        assertEquals(ShopConfig.defaults().categories(), memory.getValue().categories());
        assertThrows(ConfigValidationException.class, () -> memory.update(current -> new ShopConfig(99, current.discount(), current.categories())));
    }
}
