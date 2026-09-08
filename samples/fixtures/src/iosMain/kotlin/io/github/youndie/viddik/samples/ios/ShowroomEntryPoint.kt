package io.github.youndie.viddik.samples.ios

import io.github.youndie.viddik.generated.GeneratedViddikRegistry
import io.github.youndie.viddik.showroom.ViddikShowroomUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow

// The whole iOS application: a `UIApplicationMain`, a delegate that owns a window, and the showroom
// as its root view controller. All three come out of `platform.UIKit`, so this is built by the same
// compiler from the same source set as the components it draws — no Xcode project, no `.pbxproj` to
// go stale in a library repository. `scripts/ios-showroom.sh` wraps the linked Mach-O in a bundle and
// hands it to the simulator.
//
// The registry is referenced here, in the consumer's own module, rather than looked up: it is
// generated into `commonMain` by `viddik { showroomTargets = true }`, and Kotlin/Native has no
// reflective way to find it even if it wanted one.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public fun showroomMain() {
    memScoped {
        val args = arrayOf("ViddikShowroom")
        UIApplicationMain(
            argc = args.size,
            argv = args.map { it.cstr.ptr }.toCValues().ptr,
            principalClassName = null,
            // UIKit finds the delegate by name, with no storyboard to name it. A misspelling here is a
            // black screen and no error at all: UIKit simply proceeds without a delegate.
            delegateClassName = NSStringFromClass(ShowroomAppDelegate),
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class ShowroomAppDelegate :
    UIResponder,
    UIApplicationDelegateProtocol {
    // UIKit INSTANTIATES THIS ITSELF, with `[[Class alloc] init]`, and a Kotlin class exports no such
    // initializer by default. Without this the app dies before any of its own code runs, with
    // "Initializer is not implemented" in the device log and nothing on screen.
    @OverrideInit
    constructor() : super()

    public companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    // Backed by a field of another name: `window` is a protocol property, and a private one spelled
    // the same way would hide it rather than implement it.
    private var held: UIWindow? = null

    override fun window(): UIWindow? = held

    override fun setWindow(window: UIWindow?) {
        held = window
    }

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?,
    ): Boolean {
        held =
            UIWindow(frame = UIScreen.mainScreen.bounds).apply {
                rootViewController = ViddikShowroomUIViewController(GeneratedViddikRegistry.components)
                makeKeyAndVisible()
            }
        return true
    }
}
