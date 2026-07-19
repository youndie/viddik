package ru.workinprogress.viddik.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class ViddikProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ViddikSymbolProcessor(
            environment.codeGenerator,
            environment.logger,
            generateTests = environment.options["viddik.generateTests"] != "false",
        )
}
