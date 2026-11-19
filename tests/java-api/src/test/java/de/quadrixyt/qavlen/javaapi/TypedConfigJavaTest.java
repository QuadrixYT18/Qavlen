package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.config.ConfigValidationException;
import de.quadrixyt.qavlen.serialization.Comment;
import de.quadrixyt.qavlen.serialization.ConfigFileFormat;
import de.quadrixyt.qavlen.serialization.Configs;
import de.quadrixyt.qavlen.serialization.Configuration;
import de.quadrixyt.qavlen.serialization.Range;
import de.quadrixyt.qavlen.serialization.TypedConfig;
import de.quadrixyt.qavlen.serialization.TypedConfigOptions;
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

    @TempDir
    Path directory;

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
