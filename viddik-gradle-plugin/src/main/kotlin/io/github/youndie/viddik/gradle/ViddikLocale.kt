package io.github.youndie.viddik.gradle

import org.gradle.api.GradleException
import java.util.Locale

/**
 * The system properties that make [tag] the default locale of a forked test JVM.
 *
 * Compose Resources pick `values-<lang>` by `Locale.getDefault()`, so the host's locale is drawn into
 * every golden that shows a string resource, and a task that does not know the locale stays
 * `UP-TO-DATE` when it changes (#65). `systemProperty` values are task inputs, which fixes both at
 * once. Every field is set, an empty one included, so nothing of the host's locale is left to merge
 * in — and with `user.language` on the command line the JDK no longer fills in the platform's
 * separate `user.language.format`, so number formatting follows as well. `JAVA_TOOL_OPTIONS` is not
 * a substitute: Gradle hands the worker the daemon's `user.language`, so the result depended on which
 * daemon the build got.
 */
internal fun localeSystemProperties(tag: String): Map<String, String> {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) {
        throw GradleException(
            "viddik { locale = \"$tag\" } is not a BCP 47 language tag such as \"en-US\" or \"de\". " +
                "Set it to null to leave the host's locale in place.",
        )
    }
    return mapOf(
        "user.language" to locale.language,
        "user.script" to locale.script,
        "user.country" to locale.country,
        "user.variant" to locale.variant,
    )
}
