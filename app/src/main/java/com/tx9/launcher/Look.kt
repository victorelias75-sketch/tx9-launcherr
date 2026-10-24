package com.tx9.launcher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color

/** Cores de cada tema. */
class Palette(
    val key: String,
    val label: String,
    val bgTop: Int,
    val bgBottom: Int,
    val card: Int,
    val accent: Int
)

/** Estado visual global: tema e modo leve. */
object Look {
    val PALETTES = listOf(
        Palette("grafite", "Grafite", 0xFF151A26.toInt(), 0xFF0A0C11.toInt(), 0xFF1A1F2B.toInt(), 0xFF4FC3F7.toInt()),
        Palette("aurora", "Aurora", 0xFF1B1431.toInt(), 0xFF0C0914.toInt(), 0xFF221A38.toInt(), 0xFFB388FF.toInt()),
        Palette("ambar", "Âmbar", 0xFF24190F.toInt(), 0xFF110C07.toInt(), 0xFF2B2117.toInt(), 0xFFFFB74D.toInt())
    )

    var palette: Palette = PALETTES[0]
        private set

    /** Modo leve ativo (manual ou automático). */
    var lite = false
        private set

    /** true se a box tem pouca RAM (liga o modo leve automaticamente). */
    var liteAuto = false
        private set

    fun load(context: Context) {
        val prefs = Prefs(context)
        palette = PALETTES.firstOrNull { it.key == prefs.theme } ?: PALETTES[0]
        liteAuto = isLowMemory(context)
        lite = prefs.lite ?: liteAuto
    }

    private fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem < 1_500L * 1024 * 1024
    }
}

/** Preferências do launcher, salvas só dentro do próprio app. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = sp.getString("favs", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(value) {
            sp.edit().putString("favs", value.joinToString(",")).apply()
        }

    var theme: String
        get() = sp.getString("theme", "grafite") ?: "grafite"
        set(value) {
            sp.edit().putString("theme", value).apply()
        }

    /** null = automático (decidido pela RAM da box). */
    var lite: Boolean?
        get() = if (sp.contains("lite")) sp.getBoolean("lite", false) else null
        set(value) {
            val editor = sp.edit()
            if (value == null) editor.remove("lite") else editor.putBoolean("lite", value)
            editor.apply()
        }

    var mainPackage: String?
        get() = sp.getString("main", null)
        set(value) {
            sp.edit().putString("main", value).apply()
        }
}

fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

fun mix(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(a: Int, b: Int) = (a + (b - a) * k).toInt()
    return Color.argb(
        ch(Color.alpha(from), Color.alpha(to)),
        ch(Color.red(from), Color.red(to)),
        ch(Color.green(from), Color.green(to)),
        ch(Color.blue(from), Color.blue(to))
    )
}

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
