package de.quadrixyt.qavlen.language

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.ConfigSection
import de.quadrixyt.qavlen.config.JsonConfig
import de.quadrixyt.qavlen.config.YamlConfig
import java.io.IOException
import java.io.StringReader
import java.nio.file.Path
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

internal class Catalog(val namespace: String, val locale: Locale, val messages: Map<String, String>, val origin: String)

internal fun interface TranslationSource {
    fun load(): List<Catalog>
}

private val extensions = listOf("yml", "yaml", "json", "properties")

internal class DirectorySource(private val directory: Path) : TranslationSource {
    override fun load(): List<Catalog> {
        if (!directory.isDirectory()) return emptyList()
        return try {
            val entries = directory.listDirectoryEntries().sortedBy { it.name }
            val root = entries.filter(::isTranslationFile).map { read(it, "") }
            val namespaced = entries.filter { it.isDirectory() }.flatMap { child ->
                child.listDirectoryEntries().sortedBy { it.name }.filter(::isTranslationFile).map { read(it, child.name) }
            }
            root + namespaced
        } catch (exception: IOException) {
            throw TranslationException("Could not read translations in $directory", exception)
        }
    }

    private fun isTranslationFile(path: Path) = path.isRegularFile() && path.extension.lowercase() in extensions

    private fun read(file: Path, namespace: String): Catalog {
        val locale = parseLocale(file.nameWithoutExtension, file.toString())
        return Catalog(namespace, locale, parse(file.readText(), file.extension, file.toString()), file.toString())
    }
}

internal class ResourceSource(
    private val loader: ClassLoader,
    private val path: String,
    private val locales: List<Locale>,
    private val namespace: String,
) : TranslationSource {
    override fun load(): List<Catalog> = locales.map { locale ->
        val names = listOf(locale.toLanguageTag(), locale.toString()).distinct()
        val (resource, extension) = names.flatMap { name -> extensions.map { "$path/$name.$it" to it } }
            .firstOrNull { (resource, _) -> loader.getResource(resource) != null }
            ?: throw TranslationException("No translation resource for $locale in '$path'")
        val text = loader.getResourceAsStream(resource)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Catalog(namespace, locale, parse(text, extension, resource), resource)
    }
}

internal fun parseLocale(name: String, origin: String): Locale {
    val locale = try {
        Locale.Builder().setLanguageTag(name.replace('_', '-')).build()
    } catch (exception: IllformedLocaleException) {
        throw TranslationException("$origin: '$name' is not a locale tag", exception)
    }
    if (locale.language.isEmpty()) throw TranslationException("$origin: '$name' is not a locale tag")
    return locale
}

/** Flattens a translation file into dotted keys. YAML and JSON lists become multi-line messages. */
internal fun parse(text: String, extension: String, origin: String): Map<String, String> = try {
    when (extension.lowercase()) {
        "properties" -> Properties().apply { load(StringReader(text)) }.let { properties ->
            properties.stringPropertyNames().sorted().associateWith(properties::getProperty)
        }
        "json" -> flatten(JsonConfig.parse(text), origin)
        else -> flatten(YamlConfig.parse(text), origin)
    }
} catch (exception: ConfigException) {
    throw TranslationException("Could not parse $origin: ${exception.message}", exception)
}

private fun flatten(config: ConfigSection, origin: String): Map<String, String> =
    config.keys(true).associateWith { key ->
        when (val value = config.get(key)) {
            is List<*> -> value.joinToString("\n")
            is Map<*, *> -> throw TranslationException("$origin: '$key' is an empty section")
            null -> throw TranslationException("$origin: '$key' has no value")
            else -> value.toString()
        }
    }
