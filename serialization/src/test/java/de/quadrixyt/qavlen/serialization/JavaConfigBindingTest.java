package de.quadrixyt.qavlen.serialization;

import de.quadrixyt.qavlen.config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaConfigBindingTest {
    record Rewards(int dailyCoins, Duration cooldown, List<String> worlds) {
    }

    @Test
    void bindsRecords() {
        YamlConfig config = YamlConfig.parse("rewards:\n  daily-coins: 50\n  cooldown: 20h\n  worlds: [world, nether]\n");

        Rewards rewards = ConfigBinding.bind(config, "rewards", Rewards.class);

        assertEquals(new Rewards(50, Duration.ofHours(20), List.of("world", "nether")), rewards);
    }
}
