package com.tx9.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/** View simples que não cria camada extra ao animar transparência. */
class FlatView(context: Context) : View(context) {
    override fun hasOverlappingRendering() = false
}

/**
 * Brilhos desfocados gerados uma única vez, em 1/4 da resolução, e reaproveitados.
 * Evita desfoque em tempo real, que travaria a GPU da box.
 */
object GlowCache {
    private const val SCALE = 4
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(w: Int, h: Int, pad: Float, radius: Float, color: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = "$w:$h:${radius.toInt()}:$color"
        cache.get(key)?.let { return it }

        val bw = (w / SCALE).coerceAtLeast(1)
        val bh = (h / SCALE).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = pad / SCALE
        val r = radius / SCALE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.maskFilter = BlurMaskFilter((p * 0.8f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)

        // Sombra escura (profundidade)
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(RectF(p, p * 1.5f, bw - p, bh - p * 0.3f), r, r, paint)
        // Brilho na cor do app
        paint.color = withAlpha(color, 0.75f)
        canvas.drawRoundRect(RectF(p * 0.6f, p * 0.6f, bw - p * 0.6f, bh - p * 0.6f), r, r, paint)

        cache.put(key, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()
}

/**
 * Card no estilo vidro: corpo translúcido, reflexo superior, borda clara,
 * brilho colorido e reflexo que atravessa o card ao receber foco.
 * A área de padding (12dp) reserva espaço para o brilho.
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var glowColor: Int = Look.palette.accent
        set(value) {
            if (field != value) {
                field = value
                glowBitmap = null
                invalidate()
            }
        }

    var radius: Float = context.dp(11f)
        set(value) {
            field = value
            glowBitmap = null
            invalidate()
        }

    /** Contorno tracejado (cards de "Adicionar"). */
    var dashed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Fundo personalizado (usado no card principal). */
    var fill: Drawable? = null
        set(value) {
            field = value
            value?.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            invalidate()
        }

    var focusScale = 1.08f
    var focusLift = context.dp(3f)

    private val pad = context.dp(GLOW_PAD_DP)
    private val rect = RectF()
    private val tmp = RectF()
    private val dst = Rect()
    private var level = 0f
    private var sweep = -1f
    private var sweepBand = 0f
    private var levelAnim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null
    private var glowBitmap: Bitmap? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = Color.argb(30, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = Color.argb(70, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(5f), context.dp(4f)), 0f)
    }

    init {
        setWillNotDraw(false)
        val p = pad.toInt()
        setPadding(p, p, p, p)
        isFocusable = true
        isClickable = true
        // O launcher desenha o próprio destaque de foco
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
    }

    // Transparência aplicada direto nos desenhos, sem camada extra (mais rápido)
    override fun hasOverlappingRendering() = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(pad, pad, w - pad, h - pad)
        shinePaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.5f,
            Color.argb(40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        sweepBand = rect.width() * 0.45f
        sweepPaint.shader = LinearGradient(
            0f, 0f, sweepBand, sweepBand * 0.35f,
            intArrayOf(Color.TRANSPARENT, Color.argb(56, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        fill?.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        glowBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.isEmpty) return

        // 1. Brilho colorido atrás do card
        if (!Look.lite && level > 0.01f) {
            val bmp = glowBitmap ?: GlowCache.get(width, height, pad, radius, glowColor).also { glowBitmap = it }
            if (bmp != null) {
                glowPaint.alpha = (255 * level).toInt()
                dst.set(0, 0, width, height)
                canvas.drawBitmap(bmp, null, dst, glowPaint)
            }
        }

        // 2. Corpo do card
        val f = fill
        when {
            f != null -> f.draw(canvas)
            dashed -> {
                if (level > 0f) {
                    bodyPaint.color = Color.argb((18 * level).toInt(), 255, 255, 255)
                    canvas.drawRoundRect(rect, radius, radius, bodyPaint)
                }
                val half = dashPaint.strokeWidth / 2
                tmp.set(rect)
                tmp.inset(half, half)
                canvas.drawRoundRect(tmp, radius, radius, dashPaint)
            }
            else -> {
                bodyPaint.color = withAlpha(mix(Look.palette.card, Color.WHITE, 0.03f + 0.05f * level), 0.9f)
                canvas.drawRoundRect(rect, radius, radius, bodyPaint)
            }
        }

        // 3. Vidro: reflexo superior e borda clara
        if (!dashed) {
            shinePaint.alpha = (150 + 105 * level).toInt()
            canvas.drawRoundRect(rect, radius, radius, shinePaint)
            val half = edgePaint.strokeWidth / 2
            tmp.set(rect)
            tmp.inset(half, half)
            canvas.drawRoundRect(tmp, radius, radius, edgePaint)
        }

        // 4. Reflexo que atravessa o card
        if (sweep >= 0f && sweepBand > 0f) {
            val dx = rect.left - sweepBand + (rect.width() + sweepBand) * sweep
            canvas.save()
            canvas.translate(dx, 0f)
            tmp.set(rect)
            tmp.offset(-dx, 0f)
            canvas.drawRoundRect(tmp, radius, radius, sweepPaint)
            canvas.restore()
        }

        // 5. Contorno de foco (sempre visível, inclusive no modo leve)
        if (level > 0.01f) {
            ringPaint.color = withAlpha(glowColor, level)
            val o = ringPaint.strokeWidth / 2
            tmp.set(rect)
            tmp.inset(-o, -o)
            canvas.drawRoundRect(tmp, radius + o, radius + o, ringPaint)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyFocus(gainFocus, animated = true)
    }

    /** Ajusta o estado visual sem animação (usado quando a lista reaproveita o card). */
    fun syncFocus() {
        animate().cancel()
        levelAnim?.cancel()
        sweepAnim?.cancel()
        sweep = -1f
        applyFocus(isFocused, animated = false)
    }

    private fun applyFocus(on: Boolean, animated: Boolean) {
        val scale = if (on) focusScale else 1f
        val lift = if (on) -focusLift else 0f
        val z = if (on) context.dp(8f) else 0f
        val target = if (on) 1f else 0f

        if (!animated) {
            scaleX = scale
            scaleY = scale
            translationY = lift
            translationZ = z
            level = target
            invalidate()
            return
        }

        animate().scaleX(scale).scaleY(scale).translationY(lift).translationZ(z)
            .setDuration(if (Look.lite) 110 else 220)
            .setInterpolator(EASE)
            .start()

        levelAnim?.cancel()
        if (Look.lite) {
            level = target
            invalidate()
        } else {
            levelAnim = ValueAnimator.ofFloat(level, target).apply {
                duration = 220
                interpolator = EASE
                addUpdateListener {
                    level = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        sweepAnim?.cancel()
        if (on && !Look.lite && !dashed) {
            sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                startDelay = 50
                interpolator = EASE
                addUpdateListener {
                    sweep = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        sweep = -1f
                        invalidate()
                    }
                })
                start()
            }
        }
    }

    /** Leve "afundada" ao apertar OK. */
    fun pulsePress() {
        if (Look.lite) return
        val s = if (isFocused) focusScale else 1f
        animate().scaleX(s * 0.96f).scaleY(s * 0.96f).setDuration(80)
            .withEndAction { animate().scaleX(s).scaleY(s).setDuration(120).start() }
            .start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        syncFocus()
    }

    companion object {
        const val GLOW_PAD_DP = 12f
        private val EASE = DecelerateInterpolator(1.6f)
    }
}
