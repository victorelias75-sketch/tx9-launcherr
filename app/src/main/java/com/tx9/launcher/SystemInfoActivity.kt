package com.tx9.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.View
import android.widget.TextView
import java.io.File
import java.util.Locale

/** Diagnóstico somente leitura: substitui os comandos ADB da etapa 1. */
class SystemInfoActivity : TvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)
        findViewById<TextView>(R.id.info_text).text = buildInfo()
        findViewById<View>(R.id.info_scroll).requestFocus()
    }

    private fun buildInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val storage = StatFs(Environment.getDataDirectory().path)

        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val homeInfo = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val defaultHome = when (val pkg = homeInfo?.activityInfo?.packageName) {
            null -> "desconhecido"
            "android" -> "nenhum fixo (o sistema pergunta)"
            else -> pkg
        }

        @Suppress("DEPRECATION")
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
        val liteMode = (if (Look.lite) "ligado" else "desligado") +
            (if (Prefs(this).lite == null) " (automático)" else " (manual)")

        return buildString {
            appendLine("Android exibido : ${Build.VERSION.RELEASE}")
            appendLine("Nível de API    : ${Build.VERSION.SDK_INT} (= Android ${apiName(Build.VERSION.SDK_INT)})")
            appendLine("Fabricante      : ${Build.MANUFACTURER}")
            appendLine("Modelo          : ${Build.MODEL}")
            appendLine("Placa / HW      : ${Build.BOARD} / ${Build.HARDWARE}")
            appendLine("Processador     : ${readCpuHardware()}")
            appendLine("Núcleos         : ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Arquiteturas    : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("RAM total       : ${gb(mem.totalMem)}")
            appendLine("RAM livre       : ${gb(mem.availMem)}")
            appendLine("Armazenamento   : ${gb(storage.totalBytes)} (livre ${gb(storage.availableBytes)})")
            appendLine()
            appendLine("Tela            : ${metrics.widthPixels}x${metrics.heightPixels} @ ${display.refreshRate.toInt()} Hz")
            appendLine("Densidade real  : ${Resources.getSystem().displayMetrics.densityDpi} dpi")
            appendLine("Densidade app   : ${resources.displayMetrics.densityDpi} dpi (tela lógica de 960 dp)")
            appendLine()
            appendLine("Kernel          : ${System.getProperty("os.version")}")
            appendLine("Build           : ${Build.DISPLAY}")
            appendLine("Tipo / chaves   : ${Build.TYPE} / ${Build.TAGS}")
            appendLine("Fingerprint     : ${Build.FINGERPRINT}")
            appendLine("Android TV      : ${if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) "sim" else "não"}")
            appendLine("Entradas de TV  : ${tvInputs()}")
            appendLine("Launcher padrão : $defaultHome")
            appendLine("Modo leve       : $liteMode")
            appendLine("TX9 Launcher    : versão $appVersion")
        }
    }

    /** Lista entradas de TV/HDMI registradas no sistema (a TX9 só tem HDMI de saída). */
    private fun tvInputs(): String {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) {
            return "nenhuma (sem suporte a TV ao vivo)"
        }
        return try {
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return "serviço ausente"
            val list = tim.tvInputList
            if (list.isEmpty()) "nenhuma" else list.joinToString { it.loadLabel(this).toString() }
        } catch (e: Exception) {
            "n/d"
        }
    }

    private fun apiName(api: Int) = when (api) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        else -> "?"
    }

    private fun gb(bytes: Long) =
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun readCpuHardware(): String = try {
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(":")?.trim()
            ?: "n/d"
    } catch (e: Exception) {
        "n/d"
    }
}
