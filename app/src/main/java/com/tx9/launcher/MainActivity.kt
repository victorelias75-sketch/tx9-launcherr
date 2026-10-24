package com.tx9.launcher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : TvActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var content: ViewGroup
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var pillThemeText: TextView
    private lateinit var pillLiteText: TextView
    private lateinit var hero: GlassCard
    private lateinit var heroTag: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroIcon: ImageView
    private lateinit var heroCircle: View
    private lateinit var storeCard: GlassCard
    private lateinit var storeIcon: ImageView
    private lateinit var storeSub: TextView
    private lateinit var addCard: GlassCard
    private lateinit var favRow: RecyclerView
    private lateinit var allRow: RecyclerView
    private lateinit var favHint: TextView
    private lateinit var allCount: TextView
    private lateinit var overlay: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sheetTitle: TextView
    private lateinit var sheetBody: LinearLayout
    private lateinit var toastView: TextView
    private lateinit var favAdapter: AppAdapter
    private lateinit var allAdapter: AppAdapter
    private lateinit var ambientA: View
    private lateinit var ambientB: View
    private lateinit var ambientFront: View

    private val rings = ArrayList<View>()
    private val ringDrawables = ArrayList<GradientDrawable>()
    private val ringAnims = ArrayList<Animator>()
    private var ringsOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val locale = Locale("pt", "BR")
    private val topGroup = Any()
    private var dimGroup: Any? = null
    private var snapshot: Snapshot? = null
    private var favs = mutableListOf<String>()
    private var returnFocus: View? = null
    private var ambientColor = 0
    private var started = false
    private var lastRepeat = 0L

    private val reloadRunnable = Runnable { loadApps(immediate = true) }
    private val hideToastRunnable = Runnable { hideToast() }

    // Recarrega quando um app é instalado, removido, atualizado ou desativado
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    // Relógio: minuto a minuto e quando a hora ou o fuso mudam (ex.: após ligar a box)
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
        }
    }

    // ===== Ciclo de vida =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        favs = prefs.favorites.toMutableList()

        bindViews()
        setupAmbient()
        setupHeader()
        setupTop()
        setupRows()

        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        register(packageReceiver, pkgFilter)
        // Apps instalados no cartão ou pen drive ficam disponíveis depois do boot
        val extFilter = IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        register(packageReceiver, extFilter)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus -> onFocusMoved(newFocus) }

        val cached = AppRepository.cached
        if (cached != null) {
            showSnapshot(cached)
        } else {
            bindHero()
            bindStore()
        }
        loadApps(immediate = true)

        if (savedInstanceState == null) hero.requestFocus()
        root.post {
            if (currentFocus == null) hero.requestFocus()
            onFocusMoved(currentFocus)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        register(timeReceiver, filter)
        updateClock()
        updateRings()
    }

    override fun onStop() {
        super.onStop()
        started = false
        try {
            unregisterReceiver(timeReceiver)
        } catch (e: IllegalArgumentException) {
            // Já estava desregistrado
        }
        updateRings()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            // Já estava desregistrado
        }
        handler.removeCallbacksAndMessages(null)
        ringAnims.forEach { it.cancel() }
        io.shutdownNow()
        super.onDestroy()
    }

    // Home apertado com o launcher já aberto: volta ao app principal
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeSheet()
        favRow.scrollToPosition(0)
        allRow.scrollToPosition(0)
        hero.requestFocus()
    }

    // Libera memória de efeitos quando outro app está em uso
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) GlowCache.clear()
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // ===== Montagem da tela =====

    private fun bindViews() {
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        pillThemeText = findViewById(R.id.pill_theme_text)
        pillLiteText = findViewById(R.id.pill_lite_text)
        hero = findViewById(R.id.hero)
        heroTag = findViewById(R.id.hero_tag)
        heroTitle = findViewById(R.id.hero_title)
        heroSub = findViewById(R.id.hero_sub)
        heroIcon = findViewById(R.id.hero_icon)
        heroCircle = findViewById(R.id.hero_circle)
        storeCard = findViewById(R.id.store_card)
        storeIcon = findViewById(R.id.store_icon)
        storeSub = findViewById(R.id.store_sub)
        addCard = findViewById(R.id.add_card)
        favRow = findViewById(R.id.fav_row)
        allRow = findViewById(R.id.all_row)
        favHint = findViewById(R.id.fav_hint)
        allCount = findViewById(R.id.all_count)
        overlay = findViewById(R.id.overlay)
        sheet = findViewById(R.id.sheet)
        sheetTitle = findViewById(R.id.sheet_title)
        sheetBody = findViewById(R.id.sheet_body)
        toastView = findViewById(R.id.toast)

        sheet.background = rounded(Look.palette.bgTop, dp(14f))
        toastView.background = rounded(withAlpha(mix(Look.palette.bgTop, Color.WHITE, 0.04f), 0.96f), dp(8f))
    }

    /** Luz ambiente: duas camadas que trocam de cor suavemente. */
    private fun setupAmbient() {
        fun make(): View {
            val d = GradientDrawable()
            d.setGradientType(GradientDrawable.RADIAL_GRADIENT)
            d.setGradientRadius(dp(287f))
            d.setColors(intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT))
            val v = FlatView(this)
            v.background = d
            v.alpha = 0f
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            return v
        }
        ambientA = make()
        ambientB = make()
        ambientFront = ambientA
        for (v in listOf(ambientA, ambientB)) {
            val lp = FrameLayout.LayoutParams(dpi(750), dpi(575))
            lp.leftMargin = -dpi(125)
            lp.topMargin = -dpi(210)
            root.addView(v, 0, lp)
        }
    }

    private fun setAmbient(color: Int) {
        if (color == ambientColor) return
        ambientColor = color
        val peak = if (Look.lite) 0.14f else 0.26f
        val back = if (ambientFront === ambientA) ambientB else ambientA
        (back.background as GradientDrawable).setColors(intArrayOf(withAlpha(color, 1f), withAlpha(color, 0f)))
        back.animate().cancel()
        ambientFront.animate().cancel()
        if (Look.lite) {
            back.alpha = peak
            ambientFront.alpha = 0f
        } else {
            back.animate().alpha(peak).setDuration(600).start()
            ambientFront.animate().alpha(0f).setDuration(600).start()
        }
        ambientFront = back
    }

    private fun setupHeader() {
        val pills = listOf(R.id.pill_theme, R.id.pill_lite, R.id.pill_settings, R.id.pill_info, R.id.pill_original)
        for (id in pills) {
            val pill = findViewById<GlassCard>(id)
            pill.radius = dp(13f)
            pill.focusScale = 1.06f
            pill.focusLift = 0f
        }
        findViewById<View>(R.id.pill_theme).setOnClickListener { cycleTheme() }
        findViewById<View>(R.id.pill_lite).setOnClickListener { toggleLite() }
        findViewById<View>(R.id.pill_settings).setOnClickListener {
            safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        findViewById<View>(R.id.pill_info).setOnClickListener {
            safeStart(Intent(this, SystemInfoActivity::class.java))
        }
        findViewById<View>(R.id.pill_original).setOnClickListener { openOriginalLauncher() }
        updatePillLabels()
    }

    private fun updatePillLabels() {
        pillThemeText.text = getString(R.string.pill_theme, Look.palette.label)
        val base = getString(if (Look.lite) R.string.lite_on else R.string.lite_off)
        pillLiteText.text = if (prefs.lite == null) base + getString(R.string.lite_auto_suffix) else base
    }

    private fun setupTop() {
        hero.focusScale = 1.025f
        hero.focusLift = 0f
        storeCard.focusScale = 1.04f
        storeCard.focusLift = 0f
        addCard.focusScale = 1.04f
        addCard.focusLift = 0f
        addCard.dashed = true

        hero.setOnClickListener {
            hero.pulsePress()
            openMain()
        }
        hero.setOnLongClickListener {
            showHeroMenu()
            true
        }
        storeCard.setOnClickListener {
            storeCard.pulsePress()
            openStore()
        }
        storeCard.setOnLongClickListener {
            snapshot?.store?.let { showAppMenu(it, KIND_STORE) }
            true
        }
        addCard.setOnClickListener {
            addCard.pulsePress()
            openFavPicker()
        }

        val plus = findViewById<TextView>(R.id.add_plus)
        plus.setTextColor(Look.palette.accent)
        plus.background = rounded(Color.argb(20, 255, 255, 255), dp(12f))

        // Anéis pulsantes atrás do ícone do app principal
        val deco = findViewById<FrameLayout>(R.id.hero_deco)
        for (size in intArrayOf(140, 106)) {
            val d = GradientDrawable()
            d.setShape(GradientDrawable.OVAL)
            d.setColor(Color.TRANSPARENT)
            val ring = FlatView(this)
            ring.background = d
            deco.addView(ring, 0, FrameLayout.LayoutParams(dpi(size), dpi(size), Gravity.CENTER))
            rings.add(ring)
            ringDrawables.add(d)
        }
    }

    private fun setupRows() {
        favAdapter = AppAdapter(AppAdapter.FAV, { launch(it) }, { showAppMenu(it, AppAdapter.FAV) }, { openFavPicker() })
        allAdapter = AppAdapter(AppAdapter.ALL, { launch(it) }, { showAppMenu(it, AppAdapter.ALL) })

        for ((rv, adapter) in listOf(favRow to favAdapter, allRow to allAdapter)) {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = adapter
            rv.itemAnimator = null
            rv.setHasFixedSize(true)
            rv.setItemViewCacheSize(4)
            rv.addItemDecoration(Overlap(dpi(7), 0))
            rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.alpha = if (!Look.lite && dimGroup === rv && !view.hasFocus()) DIM else 1f
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.alpha = 1f
                }
            })
        }
    }

    // ===== Dados =====

    private fun loadApps(immediate: Boolean = false) {
        if (!immediate) {
            // Agrupa vários avisos seguidos (ex.: atualização de app) em uma recarga só
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 400)
            return
        }
        if (io.isShutdown) return
        val ctx = applicationContext
        val iconPx = dpi(32)
        val bigPx = dpi(56)
        try {
            io.execute {
                val result = try {
                    AppRepository.load(ctx, iconPx, bigPx)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao listar apps", e)
                    null
                }
                if (result != null) {
                    handler.post { if (!isDestroyed) showSnapshot(result) }
                }
            }
        } catch (e: RejectedExecutionException) {
            // Tela sendo fechada
        }
    }

    private fun showSnapshot(s: Snapshot) {
        snapshot = s
        bindHero()
        bindStore()
        renderFavs()
        allAdapter.submit(s.apps)
        allCount.text = getString(R.string.all_count, s.apps.size)
        root.post { applyDim() }
    }

    private fun bindHero() {
        val s = snapshot
        val main = s?.main
        val color = main?.color ?: HERO_RED

        hero.glowColor = color
        hero.fill = heroFill(color)
        heroTitle.text = main?.label ?: getString(if (s == null) R.string.hero_loading else R.string.hero_fallback)
        heroSub.text = when {
            main != null -> getString(R.string.hero_open)
            s == null -> ""
            s.store != null -> getString(R.string.hero_missing_store)
            else -> getString(R.string.hero_missing_usb)
        }
        heroSub.setTextColor(mix(color, Color.WHITE, 0.65f))
        heroTag.setTextColor(mix(color, Color.WHITE, 0.45f))
        heroTag.background = rounded(withAlpha(color, 0.22f), dp(10f))

        val circle = GradientDrawable()
        circle.setShape(GradientDrawable.OVAL)
        circle.setColor(withAlpha(color, 0.22f))
        heroCircle.background = circle
        ringDrawables.forEachIndexed { i, d ->
            d.setStroke(dpi(1), withAlpha(color, if (i == 0) 0.2f else 0.32f))
        }

        heroIcon.setImageBitmap(s?.mainIcon)
        heroIcon.visibility = if (s?.mainIcon != null) View.VISIBLE else View.INVISIBLE
        hero.contentDescription = heroTitle.text
    }

    /** Fundo do card principal: degradê escuro com luz na cor do app. */
    private fun heroFill(c: Int): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(c, Color.BLACK, 0.80f), mix(c, Color.BLACK, 0.92f))
        )
        base.setCornerRadius(hero.radius)
        val tone = mix(c, Color.BLACK, 0.6f)
        val glow = GradientDrawable()
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        glow.setColors(intArrayOf(withAlpha(tone, 1f), withAlpha(tone, 0f)))
        glow.setGradientRadius(dp(260f))
        glow.setGradientCenter(0.82f, 0.4f)
        glow.setCornerRadius(hero.radius)
        return LayerDrawable(arrayOf<Drawable>(base, glow))
    }

    private fun bindStore() {
        val s = snapshot
        val store = s?.store
        storeIcon.setImageBitmap(s?.storeIcon)
        storeIcon.visibility = if (s?.storeIcon != null) View.VISIBLE else View.INVISIBLE
        storeSub.setText(if (store != null || s == null) R.string.store_sub else R.string.store_missing)
        storeCard.glowColor = store?.color ?: STORE_GREEN
    }

    // ===== Favoritos =====

    private fun visibleFavs(): List<AppEntry> {
        val s = snapshot ?: return emptyList()
        val mainPkg = s.main?.packageName
        return favs.mapNotNull { s.byPackage[it] }.filter { it.packageName != mainPkg }
    }

    private fun renderFavs() {
        val list = visibleFavs()
        favAdapter.submit(list, withAddTile = list.size < MAX_FAVS)
        favHint.setText(if (list.isEmpty()) R.string.fav_hint_empty else R.string.fav_hint)
    }

    private fun saveFavs() {
        prefs.favorites = favs
    }

    private fun addFav(pkg: String, refocusAll: Boolean = false) {
        if (favs.contains(pkg)) return
        if (visibleFavs().size >= MAX_FAVS) {
            toast(getString(R.string.toast_limit, MAX_FAVS))
            return
        }
        favs.add(pkg)
        saveFavs()
        renderFavs()
        if (refocusAll) focusInRow(allRow, allAdapter, pkg) else focusInRow(favRow, favAdapter, pkg)
        toast(getString(R.string.toast_added, labelOf(pkg)))
        root.post { applyDim() }
    }

    private fun removeFav(pkg: String, refocusAll: Boolean = false) {
        val before = visibleFavs()
        val index = before.indexOfFirst { it.packageName == pkg }
        if (!favs.remove(pkg)) return
        saveFavs()
        renderFavs()
        if (refocusAll) {
            focusInRow(allRow, allAdapter, pkg)
        } else {
            val after = visibleFavs()
            if (after.isEmpty()) {
                focusPosition(favRow, 0)
            } else {
                focusInRow(favRow, favAdapter, after[index.coerceIn(0, after.size - 1)].packageName)
            }
        }
        toast(getString(R.string.toast_removed, labelOf(pkg)))
        root.post { applyDim() }
    }

    private fun moveFav(pkg: String, delta: Int) {
        val order = visibleFavs().map { it.packageName }.toMutableList()
        val i = order.indexOf(pkg)
        if (i < 0) return
        val j = i + delta
        if (j < 0 || j >= order.size) {
            focusInRow(favRow, favAdapter, pkg)
            toast(R.string.toast_edge)
            return
        }
        val tmp = order[i]
        order[i] = order[j]
        order[j] = tmp
        // Mantém salvos os favoritos de apps temporariamente indisponíveis
        val hidden = favs.filter { it !in order }
        favs = (order + hidden).toMutableList()
        saveFavs()
        renderFavs()
        focusInRow(favRow, favAdapter, pkg)
    }

    private fun labelOf(pkg: String): String = snapshot?.byPackage?.get(pkg)?.label ?: pkg

    private fun focusInRow(rv: RecyclerView, adapter: AppAdapter, pkg: String) {
        val pos = adapter.positionOf(pkg)
        if (pos < 0) {
            hero.requestFocus()
            return
        }
        focusPosition(rv, pos)
    }

    private fun focusPosition(rv: RecyclerView, pos: Int) {
        rv.scrollToPosition(pos)
        rv.post {
            val v = rv.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) {
                v.requestFocus()
            } else {
                rv.postDelayed({ rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 60)
            }
        }
    }

    // ===== Abrir apps =====

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        safeStart(intent)
    }

    private fun openMain() {
        val s = snapshot ?: return
        val main = s.main
        when {
            main != null -> launch(main)
            s.store != null -> safeStart(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + AppRepository.YOUTUBE_PACKAGES[0]))
                    .setPackage(AppRepository.STORE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            else -> toast(R.string.hero_missing_usb)
        }
    }

    private fun openStore() {
        val store = snapshot?.store
        if (store != null) launch(store) else toast(R.string.toast_no_store)
    }

    private fun openDetails(pkg: String) {
        safeStart(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun setMain(app: AppEntry) {
        prefs.mainPackage = app.packageName
        if (favs.remove(app.packageName)) saveFavs()
        toast(getString(R.string.toast_main_set, app.label))
        hero.requestFocus()
        loadApps(immediate = true)
    }

    /** Saída de segurança: abre o launcher original do sistema. */
    private fun openOriginalLauncher() {
        @Suppress("DEPRECATION")
        val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter {
                val pkg = it.activityInfo.packageName
                pkg != packageName && pkg != "com.android.settings"
            }
        when (homes.size) {
            0 -> toast(R.string.no_other_launcher)
            1 -> launchHome(homes[0])
            else -> menu(
                getString(R.string.choose_launcher),
                homes.map { info -> info.loadLabel(packageManager) to { launchHome(info) } }
            )
        }
    }

    private fun launchHome(info: ResolveInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        safeStart(intent)
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_open)
        } catch (e: SecurityException) {
            toast(R.string.error_open)
        }
    }

    // ===== Menus =====

    private fun showHeroMenu() {
        val s = snapshot ?: return
        val opts = ArrayList<Pair<CharSequence, () -> Unit>>()
        opts.add(getString(R.string.opt_open) to { openMain() })
        opts.add(getString(R.string.opt_change_main) to { openMainPicker() })
        val main = s.main
        if (main != null) opts.add(getString(R.string.opt_details) to { openDetails(main.packageName) })
        menu(main?.label ?: getString(R.string.hero_fallback), opts)
    }

    private fun showAppMenu(app: AppEntry, kind: Int) {
        val pkg = app.packageName
        val isMain = pkg == snapshot?.main?.packageName
        val opts = ArrayList<Pair<CharSequence, () -> Unit>>()
        opts.add(getString(R.string.opt_open) to { launch(app) })
        if (kind == AppAdapter.FAV) {
            opts.add(getString(R.string.opt_left) to { moveFav(pkg, -1) })
            opts.add(getString(R.string.opt_right) to { moveFav(pkg, 1) })
            opts.add(getString(R.string.opt_remove) to { removeFav(pkg) })
        } else if (kind == AppAdapter.ALL && !isMain && pkg != AppRepository.STORE_PACKAGE) {
            if (favs.contains(pkg)) {
                opts.add(getString(R.string.opt_remove) to { removeFav(pkg, refocusAll = true) })
            } else {
                opts.add(getString(R.string.opt_add) to { addFav(pkg, refocusAll = true) })
            }
        }
        if (!isMain) opts.add(getString(R.string.opt_set_main) to { setMain(app) })
        opts.add(getString(R.string.opt_details) to { openDetails(pkg) })
        menu(app.label, opts)
    }

    /** Painel de opções no estilo do launcher (substitui as janelas do sistema). */
    private fun menu(title: CharSequence, options: List<Pair<CharSequence, () -> Unit>>) {
        openSheet(title, wide = false) { body ->
            options.forEachIndexed { i, (label, action) ->
                val card = GlassCard(this)
                card.radius = dp(8f)
                card.focusScale = 1.03f
                card.focusLift = 0f
                val tv = TextView(this)
                tv.text = label
                tv.setTextColor(Color.WHITE)
                tv.textSize = 11f
                tv.setPadding(dpi(13), dpi(10), dpi(13), dpi(10))
                card.addView(
                    tv,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.topMargin = -dpi(18)
                body.addView(card, lp)
                card.setOnClickListener {
                    root.post {
                        closeSheet()
                        action()
                    }
                }
            }
        }
    }

    /** Grade de apps para escolher (adicionar favorito ou trocar o app principal). */
    private fun picker(title: CharSequence, apps: List<AppEntry>, onPick: (AppEntry) -> Unit) {
        if (apps.isEmpty()) {
            toast(R.string.toast_none_left)
            return
        }
        openSheet(title, wide = true) { body ->
            val rv = RecyclerView(this)
            val adapter = AppAdapter(AppAdapter.PICK, { app ->
                root.post {
                    closeSheet()
                    onPick(app)
                }
            }, { })
            rv.layoutManager = GridLayoutManager(this, 5)
            rv.adapter = adapter
            rv.itemAnimator = null
            rv.clipToPadding = false
            rv.clipChildren = false
            rv.addItemDecoration(Overlap(dpi(7), dpi(7)))
            val pad = dpi(7)
            rv.setPadding(pad, pad, pad, pad)
            adapter.submit(apps)
            val rows = (apps.size + 4) / 5
            val height = minOf(rows, 4) * dpi(75) + 2 * pad
            body.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }
    }

    private fun openFavPicker() {
        val s = snapshot ?: return
        if (visibleFavs().size >= MAX_FAVS) {
            toast(getString(R.string.toast_limit, MAX_FAVS))
            return
        }
        val mainPkg = s.main?.packageName
        val list = s.apps.filter {
            it.packageName != mainPkg && it.packageName != AppRepository.STORE_PACKAGE && it.packageName !in favs
        }
        picker(getString(R.string.picker_title), list) { addFav(it.packageName) }
    }

    private fun openMainPicker() {
        val s = snapshot ?: return
        val mainPkg = s.main?.packageName
        picker(getString(R.string.picker_main_title), s.apps.filter { it.packageName != mainPkg }) { setMain(it) }
    }

    private fun openSheet(title: CharSequence, wide: Boolean, build: (LinearLayout) -> Unit) {
        if (overlay.visibility != View.VISIBLE) returnFocus = currentFocus
        sheetTitle.text = title
        sheetBody.removeAllViews()
        build(sheetBody)
        val lp = sheet.layoutParams
        lp.width = dpi(if (wide) 690 else 360)
        sheet.layoutParams = lp

        overlay.visibility = View.VISIBLE
        overlay.animate().cancel()
        if (Look.lite) {
            overlay.alpha = 1f
        } else {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(180).start()
        }
        focusFirst(sheetBody, 0)
        // Prende o foco do controle dentro do painel
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        updateRings()
    }

    private fun closeSheet() {
        if (overlay.visibility != View.VISIBLE) return
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val back = returnFocus?.takeIf { it.isAttachedToWindow && it.isShown } ?: hero
        returnFocus = null
        back.requestFocus()
        overlay.animate().cancel()
        overlay.visibility = View.GONE
        sheetBody.removeAllViews()
        onFocusMoved(currentFocus)
    }

    private fun focusFirst(container: ViewGroup, attempt: Int) {
        val target = firstCard(container)
        if (target != null && target.requestFocus()) return
        if (attempt < 6) {
            container.postDelayed({
                if (overlay.visibility == View.VISIBLE) focusFirst(container, attempt + 1)
            }, 32)
        }
    }

    private fun firstCard(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is GlassCard) return child
            if (child is ViewGroup) firstCard(child)?.let { return it }
        }
        return null
    }

    // ===== Foco, destaque e animações =====

    private fun onFocusMoved(v: View?) {
        if (v == null || overlay.visibility == View.VISIBLE) return
        (v as? GlassCard)?.let { setAmbient(it.glowColor) }
        applyDim()
        updateRings()
    }

    /** Apaga levemente os vizinhos do item focado (desligado no modo leve). */
    private fun applyDim() {
        val f = currentFocus
        dimGroup = when {
            Look.lite || f == null || overlay.visibility == View.VISIBLE -> null
            f === hero || f === storeCard || f === addCard -> topGroup
            isInside(f, favRow) -> favRow
            isInside(f, allRow) -> allRow
            else -> null
        }
        for (c in listOf(hero, storeCard, addCard)) {
            fade(c, if (dimGroup === topGroup && c !== f) DIM else 1f)
        }
        for (rv in listOf(favRow, allRow)) {
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                fade(c, if (dimGroup === rv && !c.hasFocus()) DIM else 1f)
            }
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun fade(v: View, target: Float) {
        if (v.alpha == target) return
        v.animate().alpha(target).setDuration(180).start()
    }

    /** Anéis pulsam só com o app principal focado e o launcher visível. */
    private fun updateRings() {
        val run = started && !Look.lite && hero.isFocused && overlay.visibility != View.VISIBLE
        if (run == ringsOn) return
        ringsOn = run
        ringAnims.forEach { it.cancel() }
        ringAnims.clear()
        if (run) {
            rings.forEachIndexed { i, ring ->
                val anim = ObjectAnimator.ofPropertyValuesHolder(
                    ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f)
                )
                anim.duration = 1300
                anim.repeatCount = ValueAnimator.INFINITE
                anim.repeatMode = ValueAnimator.REVERSE
                anim.interpolator = AccelerateDecelerateInterpolator()
                anim.startDelay = if (i == 1) 400L else 0L
                anim.start()
                ringAnims.add(anim)
            }
        } else {
            for (ring in rings) {
                ring.scaleX = 1f
                ring.scaleY = 1f
                ring.alpha = 1f
            }
        }
    }

    private fun updateClock() {
        val now = Date()
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clock.text = SimpleDateFormat(pattern, locale).format(now)
        date.text = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(now)
            .replaceFirstChar { it.titlecase(locale) }
    }

    // ===== Controle remoto =====

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Descarta repetições rápidas demais ao segurar a seta (evita "atraso" em box lenta)
        if (code in DPAD && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeat < REPEAT_MS) return true
            lastRepeat = now
        }
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackKey()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP && overlay.visibility != View.VISIBLE) {
                    currentFocus?.performLongClick()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Voltar: fecha o painel aberto ou retorna ao app principal. O launcher nunca fecha. */
    private fun onBackKey() {
        if (overlay.visibility == View.VISIBLE) {
            closeSheet()
        } else {
            favRow.scrollToPosition(0)
            allRow.scrollToPosition(0)
            hero.requestFocus()
        }
    }

    // ===== Tema e modo leve =====

    private fun cycleTheme() {
        val list = Look.PALETTES
        val next = list[(list.indexOf(Look.palette) + 1) % list.size]
        prefs.theme = next.key
        recreate()
    }

    private fun toggleLite() {
        prefs.lite = !Look.lite
        recreate()
    }

    // ===== Avisos =====

    private fun toast(text: CharSequence) {
        handler.removeCallbacks(hideToastRunnable)
        toastView.animate().cancel()
        toastView.text = text
        toastView.visibility = View.VISIBLE
        if (Look.lite) {
            toastView.alpha = 1f
            toastView.translationY = 0f
        } else {
            toastView.alpha = 0f
            toastView.translationY = dp(10f)
            toastView.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        handler.postDelayed(hideToastRunnable, 2200)
    }

    private fun toast(resId: Int) {
        toast(getString(resId))
    }

    private fun hideToast() {
        if (Look.lite) {
            toastView.visibility = View.GONE
            return
        }
        toastView.animate().alpha(0f).setDuration(200)
            .withEndAction { toastView.visibility = View.GONE }
            .start()
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(radius)
        return d
    }

    companion object {
        private const val TAG = "TX9Launcher"
        private const val MAX_FAVS = 10
        private const val DIM = 0.72f
        private const val REPEAT_MS = 70L
        private const val KIND_STORE = 3
        private val HERO_RED = 0xFFFF3D3D.toInt()
        private val STORE_GREEN = 0xFF34C38F.toInt()
        private val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}
