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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(!Files.readString(file).contains("jdbc-url"), Files.readString(file));
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

    public static final class LegacyBean {
        @Comment("A Java bean field")
        public int limit = 5;
        public String name = "bean";
    }

    @Test
    void wholeTypedApiFromJava() throws Exception {
        Path file = directory.resolve("bean.yml");
        TypedConfig<LegacyBean> config = Configs.open(file, LegacyBean.class);
        List<LegacyBean> changes = new java.util.ArrayList<>();
        AutoCloseable subscription = config.addListener(changes::add);

        config.update(current -> {
            LegacyBean next = new LegacyBean();
            next.limit = current.limit + 1;
            next.name = current.name;
            return next;
        });
        assertTrue(config.save());
        config.save(true);
        assertEquals(false, config.reloadIfModified());
        subscription.close();

        assertEquals(6, Configs.open(file, LegacyBean.class).getValue().limit);
        assertTrue(Files.readString(file).contains("# A Java bean field\nlimit: 6"), Files.readString(file));
        assertEquals(1, changes.size());
        assertEquals(ConfigFileFormat.TOML, ConfigFileFormat.fromFileName("x.toml"));
        assertEquals(java.util.Set.of(), config.getUnknownKeys());

        Files.writeString(file, Files.readString(file).replace("limit: 6", "limit: 7"));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(5)));
        // An edit made to the file while the program runs is taken over instead of being overwritten.
        assertFalse(config.save(false));
        assertEquals(7, config.getValue().limit);
        assertEquals(7, config.reload().limit);

        TypedConfig<ShopConfig> validated = Configs.parse(
            Configs.encode(ShopConfig.defaults()),
            ShopConfig.class,
            ConfigFileFormat.YAML,
            TypedConfigOptions.<ShopConfig>builder()
                .validator(value -> value.categories().isEmpty()
                    ? List.of(new de.quadrixyt.qavlen.config.ConfigViolation("categories", "must not be empty"))
                    : List.of())
                .unknownKeys(de.quadrixyt.qavlen.serialization.UnknownKeys.WARN)
                .build());
        assertThrows(ConfigValidationException.class,
            () -> validated.update(current -> new ShopConfig(current.pageSize(), current.discount(), List.of())));
        String preserved = de.quadrixyt.qavlen.config.YamlComments.preserveYamlComments("# hi\na: 1\n", "a: 2\n");
        assertEquals("# hi\na: 2\n", preserved);
    }
}
