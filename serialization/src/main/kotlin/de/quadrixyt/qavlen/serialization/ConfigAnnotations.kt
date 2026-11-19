package de.quadrixyt.qavlen.serialization

/**
 * Marks the root class of a typed configuration loaded with [Configs]. Nested classes need no annotation.
 *
 * @property header comment lines written at the top of YAML files
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Configuration(vararg val header: String)

/** Comment lines written above the property in YAML files. Other formats have no comments or none Qavlen writes. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Comment(vararg val value: String)

/** The value must be present and not `null`. Useful for nullable properties and Java classes. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Required

/** A text value must contain a non-whitespace character. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class NotBlank

/** A number must lie in [min]..[max]. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Range(val min: Double = -Double.MAX_VALUE, val max: Double = Double.MAX_VALUE)

/** The length of a text or the size of a list, set or map must lie in [min]..[max]. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Length(val min: Int = 0, val max: Int = Int.MAX_VALUE)

/** A text value must match [regex] completely. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Pattern(val regex: String)
