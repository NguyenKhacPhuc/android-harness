package dev.weft.osbridge.imageops

import dev.weft.contracts.RectPx
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.Test

/**
 * Runs against the real filesystem + CoreGraphics on the simulator.
 * ImageOps is a pure transform (no daemon / entitlement), so every case
 * runs in CI.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImageOpsTest {

    private val ops = IosImageOps()

    @Test
    fun resizeShrinksLongestEdgeKeepingAspect() {
        runBlocking {
            val ref = requireNotNull(ops.resize(makeSource(100, 80), 50, "resized"))
            pixelSize(ref.uri) shouldBe (50 to 40)
        }
    }

    @Test
    fun resizeLeavesAlreadySmallImageDimensionsIntact() {
        runBlocking {
            val ref = requireNotNull(ops.resize(makeSource(40, 30), 100, "resized"))
            pixelSize(ref.uri) shouldBe (40 to 30)
        }
    }

    @Test
    fun cropReturnsRequestedRegion() {
        runBlocking {
            val ref = requireNotNull(ops.crop(makeSource(100, 80), RectPx(10, 10, 50, 40), "cropped"))
            pixelSize(ref.uri) shouldBe (40 to 30)
        }
    }

    @Test
    fun cropOutsideBoundsReturnsNull() {
        runBlocking {
            ops.crop(makeSource(100, 80), RectPx(0, 0, 200, 90), "cropped") shouldBe null
        }
    }

    @Test
    fun rotateNinetySwapsDimensions() {
        runBlocking {
            val ref = requireNotNull(ops.rotate(makeSource(100, 80), 90, "rotated"))
            pixelSize(ref.uri) shouldBe (80 to 100)
        }
    }

    private fun makeSource(w: Int, h: Int): String {
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(w.toDouble(), h.toDouble()), format = format)
        val data = requireNotNull(UIImagePNGRepresentation(renderer.imageWithActions { }))
        val path = NSTemporaryDirectory() + "imgops-src-" + NSUUID().UUIDString + ".png"
        data.writeToFile(path, atomically = true)
        return path
    }

    private fun pixelSize(uri: String): Pair<Int, Int> {
        val data = requireNotNull(NSURL.URLWithString(uri)?.let { NSData.dataWithContentsOfURL(it) })
        val cg = requireNotNull(requireNotNull(UIImage.imageWithData(data)).CGImage)
        return CGImageGetWidth(cg).toInt() to CGImageGetHeight(cg).toInt()
    }
}
