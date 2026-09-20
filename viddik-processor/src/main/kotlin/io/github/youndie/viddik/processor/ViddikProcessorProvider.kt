package io.github.youndie.viddik.processor

import com.google.devtools.ksp.processing.JvmPlatformInfo
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

public class ViddikProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ViddikSymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            generateTests = environment.options["viddik.generateTests"] != "false",
            // How many test classes to split the fixtures over. One is the shape everything else
            // assumes; more exists so Gradle, which divides work by class, can put them in
            // several forks.
            shards = environment.options["viddik.shards"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            // KSP options are project-wide — there is one `ksp { arg(...) }` map for every run in a
            // module — so "which run is this" cannot be answered by an option and is read off the
            // environment instead. A run over one compilation reports that compilation's single
            // platform; a run over commonMain reports every target the module has.
            commonRun = environment.platforms.size > 1,
            jvmRun = environment.platforms.singleOrNull() is JvmPlatformInfo,
        )
}
