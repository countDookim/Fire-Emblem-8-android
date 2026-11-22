/**
 * Android side of the ROM portrait icon (see [PortraitIcon]): builds the bitmap from the kept ROM
 * and sets it as the recents icon. The bitmap is built on each use and held only in memory; it is
 * never written to `res/`, `assets/` or the repo (CLAUDE.md rule 3).
 */

package fe8.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap

/**
 * Builds the lord portrait icon ([PortraitIcon]) from the kept ROM, or returns null when no ROM is
 * kept or its bytes do not decode (any failure is swallowed, so the app keeps its default icon).
 * Reads the whole ROM image (about 16 MB) from disk: call off the main thread.
 *
 * @param ctx Android context.
 * @return A [PortraitIcon.SIZE] square ARGB bitmap, or `null`.
 */
internal fun portraitBitmap(ctx: Context): Bitmap? {
    val file = keptRom(ctx) ?: return null
    return runCatching {
        val px = PortraitIcon.build(file.readBytes())
        Bitmap.createBitmap(px, PortraitIcon.SIZE, PortraitIcon.SIZE, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

/**
 * The [Activity] behind [this] context, or null. Compose hands out a wrapped context, so the
 * activity is found by unwrapping [ContextWrapper]s.
 */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Shows [icon] as the app's icon in the recents (overview) screen; the task label is "FE8".
 * Uses the `TaskDescription(String, Bitmap)` constructor, deprecated on newer Android versions.
 *
 * @param activity The activity whose task is described.
 * @param icon The portrait bitmap.
 */
@Suppress("DEPRECATION")
internal fun setRecentsIcon(activity: Activity, icon: Bitmap) {
    activity.setTaskDescription(ActivityManager.TaskDescription("FE8", icon))
}
