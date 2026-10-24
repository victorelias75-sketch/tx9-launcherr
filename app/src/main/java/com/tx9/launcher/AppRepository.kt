package com.tx9.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val isTv: Boolean,
    val icon: Bitmap,
    val color: Int
) {
    // ID estável para as listas manterem o foco ao recarregar
    val id: Long = component.flattenToShortString().hashCode().toLong()
}

class Snapshot(
    val apps: List<AppEntry>,
    val main: AppEntry?,
    val mainIcon: Bitmap?,
    val store: AppEntry?,
    val storeIcon: Bitmap?
) {
    val byPackage: Map<String, AppEntry> = apps.associateBy { it.packageName }
}

object AppRepository {

    /** Versões do YouTube procuradas, em ordem de preferência. */
    val YOUTUBE_PACKAGES = listOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube",
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttubetv"
    )
    const val STORE_PACKAGE = "com.android.vending"

    /** Último resultado, para a tela abrir instantaneamente ao trocar tema. */
    @Volatile
    var cached: Snapshot? = null

    /** Lista os apps que podem ser abertos. Deve rodar fora da thread principal. */
    fun load(context: Context, iconPx: Int, bigIconPx: Int): Snapshot {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, AppEntry>()
        val infos = HashMap<String, ResolveInfo>()

        // Versão de TV primeiro: abre a interface feita para controle remoto
        val categories = listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER to true,
            Intent.CATEGORY_LAUNCHER to false
        )
        for ((category, isTv) in categories) {
            @Suppress("DEPRECATION")
            val results = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            for (info in results) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || byPackage.containsKey(pkg)) continue
                val drawable = runCatching { info.loadIcon(pm) }.getOrNull() ?: continue
                val icon = render(drawable, iconPx)
                byPackage[pkg] = AppEntry(
                    label = info.loadLabel(pm).toString().trim(),
                    packageName = pkg,
                    component = ComponentName(pkg, activity.name),
                    isTv = isTv,
                    icon = icon,
                    color = dominantColor(icon)
                )
                infos[pkg] = info
            }
        }

        val collator = Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }
        val apps = byPackage.values.sortedWith { a, b -> collator.compare(a.label, b.label) }

        val preferred = Prefs(context).mainPackage
        val main = preferred?.let { byPackage[it] } ?: YOUTUBE_PACKAGES.firstNotNullOfOrNull { byPackage[it] }
        val store = byPackage[STORE_PACKAGE]

        fun bigIcon(entry: AppEntry?): Bitmap? {
            val info = entry?.let { infos[it.packageName] } ?: return null
            return runCatching { render(info.loadIcon(pm), bigIconPx) }.getOrNull()
        }

        return Snapshot(apps, main, bigIcon(main), store, bigIcon(store)).also { cached = it }
    }

    /** Converte o ícone em bitmap pequeno (bem mais leve que o original). */
    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    /** Cor predominante do ícone, usada no brilho e na luz ambiente. */
    private fun dominantColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 12, 12, true)
        val hsv = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (x in 0 until 12) {
            for (y in 0 until 12) {
                val p = small.getPixel(x, y)
                if (Color.alpha(p) < 160) continue
                Color.colorToHSV(p, hsv)
                val weight = hsv[1] * hsv[2] + 0.02
                r += Color.red(p) * weight
                g += Color.green(p) * weight
                b += Color.blue(p) * weight
                total += weight
            }
        }
        if (small !== src) small.recycle()
        if (total < 0.5) return 0xFF7D8A9C.toInt()

        Color.colorToHSV(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(0.75f)
        return Color.HSVToColor(hsv)
    }
}
