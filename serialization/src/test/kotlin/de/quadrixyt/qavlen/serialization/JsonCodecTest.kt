package de.quadrixyt.qavlen.serialization

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonCodecTest {
    data class Profile(val id: UUID, val name: String, val playtime: Duration, val joined: Instant, val tags: List<String> = emptyList())

    data class Key(val namespace: String, val value: String) {
        override fun toString(): String = "$namespace:$value"
    }

    data class Item(val key: Key, val amount: Int)

    private val codec = JsonCodec.default

    @Test
    fun `round trips kotlin classes with java time and uuid`() {
        val profile =
            Profile(
                UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
                "Alex",
                Duration.ofMinutes(90),
                Instant.parse("2025-01-02T03:04:05Z"),
            )

        val json = codec.encode(profile)

        assertTrue("\"playtime\":\"PT1H30M\"" in json, json)
        assertTrue("\"joined\":\"2025-01-02T03:04:05Z\"" in json, json)
        assertEquals(profile, codec.decode(json, Profile::class.java))
        assertEquals(listOf(profile), codec.decode<List<Profile>>("[$json]"))
    }

    @Test
    fun `missing non-null values and bad types are reported with a path`() {
        val missing =
            assertFailsWith<SerializationException> {
                codec.decode("""{"id":"7c9e6679-7425-40de-944b-e07fc1f90ae7"}""", Profile::class.java)
            }
        assertTrue("name" in missing.message!!, missing.message)

        val wrong = assertFailsWith<SerializationException> { codec.decodeList("""[{"key":{"namespace":"a","value":"b"},"amount":"many"}]""", Item::class.java) }
        assertEquals("[0].amount", wrong.path)
    }

    @Test
    fun `custom string types`() {
        val custom = codec.toBuilder()
            .stringType(Key::class.java, Key::toString) { text ->
                require(':' in text) { "expected namespace:value" }
                Key(text.substringBefore(':'), text.substringAfter(':'))
            }
            .build()

        val json = custom.encode(Item(Key("minecraft", "stone"), 3))

        assertEquals("""{"key":"minecraft:stone","amount":3}""", json)
        assertEquals(Item(Key("minecraft", "stone"), 3), custom.decode(json, Item::class.java))
        assertFailsWith<SerializationException> { custom.decode("""{"key":"stone","amount":1}""", Item::class.java) }
        assertEquals(mapOf("a" to 1, "b" to 2), custom.decodeMap("""{"a":1,"b":2}""", Int::class.javaObjectType))
    }
}
