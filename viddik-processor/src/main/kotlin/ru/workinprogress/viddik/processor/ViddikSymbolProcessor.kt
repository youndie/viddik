package ru.workinprogress.viddik.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

private const val DESKTOP_SCREENSHOT_FQN = "ru.workinprogress.viddik.annotations.ViddikScreenshot"
private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"
private const val PREVIEW_PARAMETER_FQN = "androidx.compose.ui.tooling.preview.PreviewParameter"
private const val GENERATED_PACKAGE = "ru.workinprogress.viddik.generated"

private sealed class ViddikEntry {
    abstract val group: String

    data class Static(
        val name: String,
        override val group: String,
        val width: Int,
        val height: Int,
        val qualifiedFunctionName: String,
        val forceDark: Boolean = false,
    ) : ViddikEntry()

    data class Parameterized(
        val name: String,
        override val group: String,
        val width: Int,
        val height: Int,
        val qualifiedFunctionName: String,
        val providerQualifiedName: String,
        val darkVariant: Boolean,
    ) : ViddikEntry()
}

class ViddikSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val generateTests: Boolean = true,
) : SymbolProcessor {
    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val annotated = resolver.getSymbolsWithAnnotation(DESKTOP_SCREENSHOT_FQN).toList()
        if (annotated.isEmpty()) return emptyList()

        val entries = mutableListOf<ViddikEntry>()
        val sourceFiles = mutableListOf<KSFile>()

        for (symbol in annotated) {
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@ViddikScreenshot can only be applied to functions", symbol)
                continue
            }

            val isComposable =
                symbol.annotations.any {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == COMPOSABLE_FQN
                }
            if (!isComposable) {
                logger.error(
                    "@ViddikScreenshot function must also be annotated @Composable: ${symbol.qualifiedName?.asString()}",
                    symbol,
                )
                continue
            }

            val singleParam = symbol.parameters.singleOrNull()
            val previewParameterAnnotation =
                singleParam?.annotations?.firstOrNull {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PREVIEW_PARAMETER_FQN
                }

            val providerQualifiedName: String?
            if (previewParameterAnnotation == null) {
                val hasOnlyDefaultableParams = symbol.parameters.all { it.hasDefault }
                if (!hasOnlyDefaultableParams) {
                    logger.error(
                        "@ViddikScreenshot function must take no required arguments (all parameters need " +
                            "default values), or a single parameter annotated @PreviewParameter: " +
                            "${symbol.qualifiedName?.asString()}",
                        symbol,
                    )
                    continue
                }
                providerQualifiedName = null
            } else {
                val providerArg =
                    previewParameterAnnotation.arguments.firstOrNull { it.name?.asString() == "provider" }?.value
                providerQualifiedName = (providerArg as? KSType)?.declaration?.qualifiedName?.asString()
                if (providerQualifiedName == null) {
                    logger.error(
                        "Could not resolve @PreviewParameter provider class for ${symbol.qualifiedName?.asString()}",
                        symbol,
                    )
                    continue
                }
            }

            val qualifiedName = symbol.qualifiedName?.asString()
            if (qualifiedName == null) {
                logger.error("@ViddikScreenshot function must have a qualified name", symbol)
                continue
            }

            val annotation =
                symbol.annotations.first {
                    it.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == DESKTOP_SCREENSHOT_FQN
                }
            val nameArg = annotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
            val groupArg = annotation.arguments.firstOrNull { it.name?.asString() == "group" }?.value as? String
            val widthArg = annotation.arguments.firstOrNull { it.name?.asString() == "width" }?.value as? Int
            val heightArg = annotation.arguments.firstOrNull { it.name?.asString() == "height" }?.value as? Int
            val darkVariantArg =
                annotation.arguments.firstOrNull { it.name?.asString() == "darkVariant" }?.value as? Boolean

            val resolvedName = nameArg?.takeIf { it.isNotBlank() } ?: symbol.simpleName.asString()
            val resolvedGroup = groupArg?.takeIf { it.isNotBlank() } ?: "Default"
            val resolvedWidth = widthArg ?: 400
            val resolvedHeight = heightArg ?: -1

            if (providerQualifiedName != null) {
                entries +=
                    ViddikEntry.Parameterized(
                        name = resolvedName,
                        group = resolvedGroup,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        qualifiedFunctionName = qualifiedName,
                        providerQualifiedName = providerQualifiedName,
                        darkVariant = darkVariantArg == true,
                    )
            } else {
                entries +=
                    ViddikEntry.Static(
                        name = resolvedName,
                        group = resolvedGroup,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        qualifiedFunctionName = qualifiedName,
                    )
                if (darkVariantArg == true) {
                    entries +=
                        ViddikEntry.Static(
                            name = "$resolvedName Dark",
                            group = resolvedGroup,
                            width = resolvedWidth,
                            height = resolvedHeight,
                            qualifiedFunctionName = qualifiedName,
                            forceDark = true,
                        )
                }
            }
            symbol.containingFile?.let { sourceFiles += it }
        }

        if (entries.isNotEmpty()) {
            val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
            generateRegistry(entries, dependencies)
            if (generateTests) generateTests(dependencies)
        }

        return emptyList()
    }

    private fun generateRegistry(
        entries: List<ViddikEntry>,
        dependencies: Dependencies,
    ) {
        val componentClass = ClassName("ru.workinprogress.viddik.annotations", "ViddikComponent")
        val compositionLocalProvider = ClassName("androidx.compose.runtime", "CompositionLocalProvider")
        val localScreenshotDarkTheme = ClassName("ru.workinprogress.viddik", "LocalViddikDarkTheme")
        val listOfComponent = LIST.parameterizedBy(componentClass)

        val initializer = CodeBlock.builder().add("buildList·{\n").indent()
        entries.forEach { entry ->
            when (entry) {
                is ViddikEntry.Static -> {
                    val contentLambda =
                        if (entry.forceDark) {
                            CodeBlock.of(
                                "{ %T(%T provides true) { %L() } }",
                                compositionLocalProvider,
                                localScreenshotDarkTheme,
                                entry.qualifiedFunctionName,
                            )
                        } else {
                            CodeBlock.of("{ %L() }", entry.qualifiedFunctionName)
                        }
                    initializer.add(
                        "add(%T(name = %S, group = %S, width = %L, height = %L, content = %L))\n",
                        componentClass,
                        entry.name,
                        entry.group,
                        entry.width,
                        entry.height,
                        contentLambda,
                    )
                }

                is ViddikEntry.Parameterized -> {
                    val providerClass = ClassName.bestGuess(entry.providerQualifiedName)
                    val previewLabelClass =
                        ClassName("ru.workinprogress.viddik.annotations", "ViddikPreviewLabel")
                    initializer.add(
                        "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                            "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                            "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index,·group·=·%S,·width·=·%L,·height·=·%L,·" +
                            "content·=·{·%L(param)·})\n" +
                            "}.toList())\n",
                        providerClass,
                        previewLabelClass,
                        componentClass,
                        entry.name,
                        entry.group,
                        entry.width,
                        entry.height,
                        entry.qualifiedFunctionName,
                    )
                    if (entry.darkVariant) {
                        initializer.add(
                            "addAll(%T().values.mapIndexed·{·index,·param·->·\n" +
                                "··val·label·=·((param·as?·%T)?.previewLabel·?:·param.toString()).take(60)\n" +
                                "··%T(name·=·%S·+·\"·-·\"·+·label·+·\"·#\"·+·index·+·\"·Dark\",·group·=·%S,·width·=·%L,·" +
                                "height·=·%L,·content·=·{·%T(%T·provides·true)·{·%L(param)·} })\n" +
                                "}.toList())\n",
                            providerClass,
                            previewLabelClass,
                            componentClass,
                            entry.name,
                            entry.group,
                            entry.width,
                            entry.height,
                            compositionLocalProvider,
                            localScreenshotDarkTheme,
                            entry.qualifiedFunctionName,
                        )
                    }
                }
            }
        }
        initializer.unindent().add("}")

        FileSpec
            .builder(GENERATED_PACKAGE, "GeneratedViddikRegistry")
            .addType(
                TypeSpec
                    .objectBuilder("GeneratedViddikRegistry")
                    .addProperty(
                        PropertySpec
                            .builder("components", listOfComponent)
                            .initializer(initializer.build())
                            .build(),
                    ).build(),
            ).build()
            .writeTo(codeGenerator, dependencies)
    }

    private fun generateTests(dependencies: Dependencies) {
        val engineClass = ClassName("ru.workinprogress.viddik.core", "ViddikEngine")
        val registryClass = ClassName(GENERATED_PACKAGE, "GeneratedViddikRegistry")
        val dynamicTestClass = ClassName("org.junit.jupiter.api", "DynamicTest")
        val testFactoryClass = ClassName("org.junit.jupiter.api", "TestFactory")

        FileSpec
            .builder(GENERATED_PACKAGE, "GeneratedViddikTests")
            .addType(
                TypeSpec
                    .classBuilder("GeneratedViddikTests")
                    .addFunction(
                        FunSpec
                            .builder("runAllScreenshots")
                            .addAnnotation(testFactoryClass)
                            .returns(LIST.parameterizedBy(dynamicTestClass))
                            .addStatement("return %T.dynamicTests(%T.components)", engineClass, registryClass)
                            .build(),
                    ).build(),
            ).build()
            .writeTo(codeGenerator, dependencies)
    }
}
