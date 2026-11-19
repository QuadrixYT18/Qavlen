package com.example.qavlen;

import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.commands.CommandResult;
import de.quadrixyt.qavlen.commands.CommandSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetCommandTest {
    static final class TestSource implements CommandSource {
        final List<String> messages = new ArrayList<>();
        private final Locale locale;
        private final boolean trusted;

        TestSource(Locale locale, boolean trusted) {
            this.locale = locale;
            this.trusted = trusted;
        }

        @Override public String getName() { return "tester"; }
        @Override public String getId() { return "tester"; }
        @Override public Locale getLocale() { return locale; }
        @Override public Object getHandle() { return this; }
        @Override public boolean hasPermission(String permission) { return trusted; }
        @Override public void sendMessage(String message) { messages.add(message); }
    }

    @Test
    void generatedJavaCommandRuns() {
        CommandDispatcher dispatcher = Main.createDispatcher();
        TestSource german = new TestSource(Locale.GERMAN, false);
        TestSource trusted = new TestSource(Locale.ENGLISH, true);

        assertEquals(CommandResult.SUCCESS, dispatcher.execute(german, "greet user Alex formal"));
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(german, "hello user Sam"));
        assertEquals(CommandResult.NO_PERMISSION, dispatcher.execute(german, "greet repeat 2 hi"));
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(trusted, "greet repeat 2 hi there"));
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(trusted, "greet repeat 9 hi"));

        assertEquals(List.of("Guten Tag, Alex.", "Hallo Sam!", "Du hast keine Berechtigung für diesen Befehl."), german.messages);
        assertEquals(List.of("hi there", "hi there", "The number must be between 1 and 5."), trusted.messages);
    }
}
