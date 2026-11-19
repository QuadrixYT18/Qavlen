package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.commands.ArgumentTypes;
import de.quadrixyt.qavlen.commands.CommandDefinition;
import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.commands.CommandException;
import de.quadrixyt.qavlen.commands.CommandHelp;
import de.quadrixyt.qavlen.commands.CommandResult;
import de.quadrixyt.qavlen.commands.CommandSource;
import de.quadrixyt.qavlen.commands.Cooldowns;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    static final class Console implements CommandSource {
        final List<String> messages = new ArrayList<>();

        @Override public String getName() { return "console"; }
        @Override public String getId() { return "console"; }
        @Override public Locale getLocale() { return Locale.ENGLISH; }
        @Override public Object getHandle() { return this; }
        @Override public boolean hasPermission(String permission) { return true; }
        @Override public void sendMessage(String message) { messages.add(message); }
    }

    @Test
    void programmaticCommandsWithConditionsCooldownsAndHelp() {
        List<String> log = new ArrayList<>();
        boolean[] open = {true};
        CommandDefinition shop = CommandDefinition.builder("shop")
            .condition(source -> {
                if (!open[0]) {
                    throw new CommandException("shop.closed", Map.of());
                }
            })
            .subcommand("buy", buy -> buy
                .description("Buy an item")
                .cooldown(Duration.ofMinutes(1))
                .argument("item", ArgumentTypes.word(2, 10))
                .optionalArgument("amount", ArgumentTypes.integer(1, 64))
                .executes(context -> log.add(context.argument("item", String.class) + ":" + context.argumentOrNull("amount", Integer.class))))
            .build();
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(shop);
        Console console = new Console();

        assertEquals(CommandResult.SUCCESS, dispatcher.execute(console, "shop buy apple 3"));
        assertEquals(CommandResult.COOLDOWN, dispatcher.execute(console, "shop buy apple"));
        open[0] = false;
        assertEquals(CommandResult.REJECTED, dispatcher.execute(console, "shop buy pear"));
        assertEquals(List.of("apple:3"), log);
        assertEquals(List.of(new CommandHelp("/shop buy <item> [amount]", "Buy an item")), dispatcher.help(console));
        assertEquals(List.of("buy"), dispatcher.suggest(console, "shop b"));

        Cooldowns cooldowns = new Cooldowns();
        assertTrue(cooldowns.tryStart("key", Duration.ofSeconds(5)));
        assertFalse(cooldowns.tryStart("key", Duration.ofSeconds(5)));
    }
}
