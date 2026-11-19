package de.quadrixyt.qavlen.language

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationsTest {
    private val german = Locale.GERMAN
    private val austrian = Locale.of("de", "AT")

    private fun translations() = Translations.builder()
        .defaultLocale(Locale.ENGLISH)
        .messages(Locale.ENGLISH, mapOf("greeting" to "Hello {player}!", "only.english" to "English", "coins" to "{amount} coins"))
        .messages(german, mapOf("greeting" to "Hallo {player}!", "coins" to "{amount} Münzen"))
        .messages(austrian, mapOf("greeting" to "Servus {player}!"))
        .build()

    @Test
    fun `falls back through region, language and default locale`() {
        val translations = translations()

        assertEquals("Servus Alex!", translations.format(austrian, "greeting", placeholder("player", "Alex")))
        assertEquals("1.000 Münzen", translations.format(german, "coins", placeholder("amount", 1000)))
        assertEquals("English", translations.format(austrian, "only.english"))
        assertEquals("Hello Alex!", translations.format(Locale.FRENCH, "greeting", placeholder("player", "Alex")))
        assertEquals("missing.key", translations.format(german, "missing.key"))
        assertNull(translations.template(german, "missing.key"))
    }

    @Test
    fun `formats typed values for the locale`() {
        val translations = Translations.builder()
            .messages(
                Locale.ENGLISH,
                mapOf(
                    "ratio" to "{value:percent}",
                    "price" to "{value:number:#,##0.00}",
                    "date" to "{value:date:short}",
                    "wait" to "{value}",
                    "items" to "{count:choice:0#no items|1#one item|1<{count} items}",
                    "braces" to "{{literal}} {value}",
                ),
            )
            .build()
        val english = Locale.ENGLISH

        assertEquals("50%", translations.format(english, "ratio", placeholder("value", 0.5)))
        assertEquals("1,234.50", translations.format(english, "price", placeholder("value", 1234.5)))
        assertEquals("1/2/25", translations.format(english, "date", placeholder("value", LocalDate.of(2025, 1, 2))))
        assertEquals("1h30m", translations.format(english, "wait", placeholder("value", Duration.ofMinutes(90))))
        assertEquals("no items", translations.format(english, "items", placeholder("count", 0)))
        assertEquals("one item", translations.format(english, "items", placeholder("count", 1)))
        assertEquals("3 items", translations.format(english, "items", placeholder("count", 3)))
        assertEquals("{literal} x", translations.format(english, "braces", placeholder("value", "x")))
    }

    @Test
    fun `untrusted values are inserted verbatim`() {
        val translations = translations()

        assertEquals("Hello {amount}!", translations.format(Locale.ENGLISH, "greeting", placeholder("player", "{amount}")))
    }

    @Test
    fun `malformed templates are reported with their origin`() {
        val error = assertFailsWith<TranslationException> {
            Translations.builder().messages(Locale.ENGLISH, mapOf("broken" to "Hello {player")).build()
        }
        assertTrue(error.message!!.contains("'broken'"))
        assertFailsWith<TranslationException> {
            Translations.builder().messages(Locale.ENGLISH, mapOf("style" to "{x:unknown}")).build()
        }
    }

    @Test
    fun `loads namespaces from directories and reloads atomically`(@TempDir directory: Path) {
        directory.resolve("en.yml").writeText("welcome:\n  title: Welcome\n  lines:\n    - first\n    - second\n")
        directory.resolve("de_DE.properties").writeText("welcome.title=Willkommen\n")
        directory.resolve("shop").createDirectories().resolve("en.json").writeText("""{"buy": "Bought {item}"}""")

        val translations = Translations.builder().directory(directory).build()

        assertEquals("first\nsecond", translations.format(Locale.ENGLISH, "welcome.lines"))
        assertEquals("Willkommen", translations.format(Locale.GERMANY, "welcome.title"))
        assertEquals("Bought apple", translations.format(Locale.ENGLISH, "shop:buy", placeholder("item", "apple")))
        assertEquals(setOf("welcome.lines", "shop:buy"), translations.missingKeys(Locale.GERMANY))

        directory.resolve("en.yml").writeText("welcome: [broken")
        assertFailsWith<TranslationException> { translations.reload() }
        assertEquals("Welcome", translations.format(Locale.ENGLISH, "welcome.title"))

        directory.resolve("en.yml").writeText("welcome:\n  title: Hi\n")
        translations.reload()
        assertEquals("Hi", translations.format(Locale.ENGLISH, "welcome.title"))
    }

    @Test
    fun `later sources override earlier ones`() {
        val translations = Translations.builder()
            .messages(Locale.ENGLISH, mapOf("a" to "bundled"))
            .messages(Locale.ENGLISH, mapOf("a" to "custom"))
            .build()

        assertEquals("custom", translations.format(Locale.ENGLISH, "a"))
    }

    @Test
    fun `validation finds missing keys and unknown placeholders`() {
        val translations = Translations.builder()
            .messages(Locale.ENGLISH, mapOf("a" to "{x}", "b" to "b"))
            .messages(german, mapOf("a" to "{y}"))
            .build()

        val issues = translations.validate().map { it.toString() }

        assertEquals(listOf("[de] b: missing translation", "[de] a: unknown placeholders [y]"), issues)
    }

    @Test
    fun `loads bundled resources`() {
        val translations = Translations.builder()
            .resources(javaClass.classLoader, "lang", listOf(Locale.ENGLISH, german), namespace = "test")
            .build()

        assertEquals("Hallo", translations.format(german, "test:hello"))
        assertEquals(mapOf(Locale.ENGLISH to "Hello", german to "Hallo"), translations.localizations("test:hello"))
    }

    @Test
    fun `validation reports extra and required keys`() {
        val translations = Translations.builder()
            .messages(Locale.ENGLISH, mapOf("shop.title" to "Shop"))
            .messages(german, mapOf("shop.title" to "Laden", "shop.titel" to "Tippfehler"))
            .build()

        assertEquals(
            listOf("[en] shop.footer: required key is missing", "[de] shop.titel: not defined in the default locale"),
            translations.validate(listOf("shop.title", "shop.footer")).map { it.toString() },
        )
        assertTrue(translations.isTranslated(Locale.GERMANY, "shop.title"))
        assertEquals(false, translations.isTranslated(Locale.FRENCH, "shop.title"))
    }

    @Test
    fun `broken templates inside choices fail when loading`() {
        assertFailsWith<TranslationException> {
            Translations.builder().messages(Locale.ENGLISH, mapOf("items" to "{count:choice:0#none|1<{count items}")).build()
        }
    }

    @Test
    fun `editable resources keep edits and add new keys`(@TempDir directory: Path) {
        val de = directory.resolve("de.yml")
        de.writeText("greeting: Servus {player}\n")

        val translations = Translations.builder()
            .editableResources(javaClass.classLoader, "editable", listOf(Locale.ENGLISH, german), directory)
            .build()

        assertEquals("Servus Alex", translations.format(german, "greeting", placeholder("player", "Alex")))
        assertEquals("Tschüss", translations.format(german, "farewell"))
        assertTrue("greeting: Servus {player}" in de.readText() && "farewell: Tschüss" in de.readText(), de.readText())
        assertTrue(java.nio.file.Files.exists(directory.resolve("en.yml")))

        de.writeText("greeting: [broken")
        assertFailsWith<TranslationException> { translations.reload() }
        assertEquals("Servus Alex", translations.format(german, "greeting", placeholder("player", "Alex")))
        assertEquals("greeting: [broken", de.readText())
    }
}
