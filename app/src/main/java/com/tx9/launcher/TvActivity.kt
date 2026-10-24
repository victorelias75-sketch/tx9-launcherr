package com.tx9.launcher

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics

/**
 * Base das telas: fixa uma tela lógica de 960dp de largura,
 * para o layout ter as mesmas proporções em qualquer box (720p ou 1080p).
 */
abstract class TvActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val res = newBase.resources
        val metrics = res.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        val target = longSide * DisplayMetrics.DENSITY_DEFAULT / DESIGN_WIDTH_DP
        if (target > 0 && res.configuration.densityDpi != target) {
            // Android 7+ aceita só o campo alterado; versões antigas precisam da cópia completa
            val config = if (Build.VERSION.SDK_INT >= 24) Configuration() else Configuration(res.configuration)
            config.densityDpi = target
            applyOverrideConfiguration(config)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Look.load(this)
        super.onCreate(savedInstanceState)
        val p = Look.palette
        window.setBackgroundDrawable(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.bgTop, p.bgBottom, p.bgBottom))
        )
    }

    private companion object {
        const val DESIGN_WIDTH_DP = 960
    }
}
