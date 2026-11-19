package de.quadrixyt.qavlen.javaapi;

import com.sun.net.httpserver.HttpServer;
import de.quadrixyt.qavlen.cache.LocalCache;
import de.quadrixyt.qavlen.network.RestClient;
import de.quadrixyt.qavlen.scheduler.ScheduledTask;
import de.quadrixyt.qavlen.scheduler.TaskScheduler;
import de.quadrixyt.qavlen.serialization.JsonCodec;
import de.quadrixyt.qavlen.storage.Database;
import de.quadrixyt.qavlen.storage.Migrations;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureTest {
    record Player(String name, int coins) {
    }

    @Test
    void databaseWithMigrationsTransactionsAndAsyncQueries() throws Exception {
        try (Database database = Database.builder("jdbc:h2:mem:" + UUID.randomUUID()).queryTimeout(Duration.ofSeconds(5)).connect()) {
            new Migrations(database).add(1, "players", "CREATE TABLE players (name VARCHAR(32) PRIMARY KEY, coins INT NOT NULL)").migrate();
            database.transaction(session -> {
                session.update("INSERT INTO players (name, coins) VALUES (?, ?)", "Alex", 5);
                return session.update("UPDATE players SET coins = coins + ? WHERE name = ?", 10, "Alex");
            });

            List<Player> players = database.queryAsync("SELECT name, coins FROM players", row -> new Player(row.getString(1), row.getInt(2)))
                .get(5, TimeUnit.SECONDS);

            assertEquals(List.of(new Player("Alex", 15)), players);
        }
    }

    @Test
    void cacheSchedulerSerializationAndHttp() throws Exception {
        LocalCache<String, Integer> cache = LocalCache.builder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(1)).build();
        assertEquals(5, cache.get("hello", String::length));
        assertEquals(3, cache.getAsync("abc", key -> CompletableFuture.completedFuture(key.length())).get());

        JsonCodec codec = JsonCodec.getDefault();
        assertEquals(List.of(new Player("Sam", 2)), codec.decodeList(codec.encode(List.of(new Player("Sam", 2))), Player.class));

        try (TaskScheduler scheduler = new TaskScheduler("java-test", 1)) {
            CountDownLatch ran = new CountDownLatch(1);
            ScheduledTask task = scheduler.runLater(Duration.ofMillis(10), ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertTrue(task.isDone());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/player", exchange -> {
            byte[] body = "{\"name\":\"Robin\",\"coins\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (RestClient client = RestClient.builder("http://127.0.0.1:" + server.getAddress().getPort() + "/").build()) {
            assertEquals(new Player("Robin", 7), client.get("player").query("id", 1).sendJson(Player.class).get(5, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }
}
