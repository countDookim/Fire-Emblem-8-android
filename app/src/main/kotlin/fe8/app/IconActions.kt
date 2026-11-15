/**
 * Android side of the ROM portrait icon (see [PortraitIcon]): the bitmap, the recents icon and the
 * pinned home-screen shortcut. The bitmap is built from the kept ROM on each use and held only in
 * memory; it is never written to `res/`, `assets/` or the repo (CLAUDE.md rule 3).
 */

package fe8.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Id of the pinned shortcut; pinning again updates the same shortcut. */
private const val SHORTCUT_ID = "fe8-launch"

/**
 * Builds Eirika's portrait icon from the kept ROM, or returns null when no ROM is kept or the
 * ROM does not decode. Reads 16 MB: call off the main thread.
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

/** The [Activity] behind [this] context (Compose wraps it), or null. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Shows [icon] as the app's icon in the recents screen.
 *
 * @param activity The activity whose task is described.
 * @param icon The portrait bitmap.
 */
@Suppress("DEPRECATION")
internal fun setRecentsIcon(activity: Activity, icon: Bitmap) {
    activity.setTaskDescription(ActivityManager.TaskDescription("FE8", icon))
}

/** Whether the launcher supports pinned shortcuts. */
internal fun canPinShortcut(ctx: Context): Boolean =
    ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

/**
 * Asks the launcher to pin a home-screen shortcut that starts the app, drawn with [icon]. The
 * system shows its own confirmation.
 *
 * @param ctx Android context.
 * @param icon The portrait bitmap.
 * @return `true` if the request was sent.
 */
internal fun requestPinShortcut(ctx: Context, icon: Bitmap): Boolean {
    if (!canPinShortcut(ctx)) return false
    val launch = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
    val info = ShortcutInfoCompat.Builder(ctx, SHORTCUT_ID)
        .setShortLabel("FE8")
        .setIcon(IconCompat.createWithBitmap(icon))
        .setIntent(launch)
        .build()
    return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}
