package ru.workinprogress.viddik.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ViddikScreenshot(
    val name: String = "",
    val group: String = "",
    val width: Int = 400,
    val height: Int = AUTO_HEIGHT,
    val darkVariant: Boolean = false,
)
