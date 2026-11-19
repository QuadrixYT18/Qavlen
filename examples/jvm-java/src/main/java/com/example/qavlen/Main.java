package com.example.qavlen;

import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.commands.CommandSource;
import de.quadrixyt.qavlen.config.ConfigOptions;
import de.quadrixyt.qavlen.config.YamlConfig;
import de.quadrixyt.qavlen.language.Translations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static CommandDispatcher createDispatcher() {
        Translations translations = Translations.builder()
            .defaultLocale(Locale.ENGLISH)
            .resources(Main.class.getClassLoader(), "lang", List.of(Locale.ENGLISH, Locale.GERMAN))
            .build();
        CommandDispatcher dispatcher = new CommandDispatcher(translations);
        dispatcher.register(new GreetCommand());
        return dispatcher;
    }

    public static void main(String[] args) throws IOException {
        YamlConfig config = YamlConfig.load(Path.of("greeter.yml"), ConfigOptions.builder()
            .defaults(Map.of("locale", "en"))
            .build());
        Locale locale = Locale.forLanguageTag(config.getString("locale", "en"));
        CommandDispatcher dispatcher = createDispatcher();
        CommandSource console = new ConsoleSource(locale);

        System.out.println("Type a command such as 'greet user Alex formal' or 'exit'.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null && !line.trim().equals("exit")) {
            if (!line.isBlank()) {
                dispatcher.execute(console, line);
            }
        }
    }

    record ConsoleSource(Locale locale) implements CommandSource {
        @Override
        public String getName() {
            return "console";
        }

        @Override
        public String getId() {
            return "console";
        }

        @Override
        public Locale getLocale() {
            return locale;
        }

        @Override
        public Object getHandle() {
            return this;
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void sendMessage(String message) {
            System.out.println(message);
        }
    }
}
