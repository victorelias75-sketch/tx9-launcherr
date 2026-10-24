package com.tx9.launcher

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lista de cards de apps: favoritos, todos os apps ou seletor. */
class AppAdapter(
    private val kind: Int,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
    private val onAdd: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<AppEntry>()
    private var showAdd = false

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<AppEntry>, withAddTile: Boolean = false) {
        items.clear()
        items.addAll(list)
        showAdd = withAddTile && onAdd != null
        notifyDataSetChanged()
    }

    fun positionOf(pkg: String) = items.indexOfFirst { it.packageName == pkg }

    override fun getItemCount() = items.size + if (showAdd) 1 else 0

    override fun getItemViewType(position: Int) = if (position >= items.size) TYPE_ADD else TYPE_APP

    override fun getItemId(position: Int) =
        if (position >= items.size) ADD_ID else items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        if (viewType == TYPE_ADD) {
            val card = inflater.inflate(R.layout.item_add, parent, false) as GlassCard
            setup(card)
            card.dashed = true
            card.contentDescription = parent.context.getString(R.string.add_tile)
            card.setOnClickListener {
                card.pulsePress()
                onAdd?.invoke()
            }
            return AddHolder(card)
        }

        val card = inflater.inflate(R.layout.item_app, parent, false) as GlassCard
        setup(card)
        val holder = AppHolder(card)

        // Fileira "Todos os apps" usa cards mais compactos
        if (kind == ALL) {
            val ctx = parent.context
            val lp = holder.icon.layoutParams as LinearLayout.LayoutParams
            lp.width = ctx.dpi(22)
            lp.height = ctx.dpi(22)
            holder.icon.layoutParams = lp
            holder.label.textSize = 9f
            val p = ctx.dpi(7)
            card.findViewById<View>(R.id.tile_content).setPadding(p, p, p, p)
        }

        card.setOnClickListener {
            holder.entry()?.let {
                card.pulsePress()
                onOpen(it)
            }
        }
        // Segurar OK abre as opções (o botão Menu é tratado na MainActivity)
        if (kind != PICK) {
            card.setOnLongClickListener {
                holder.entry()?.let(onMenu)
                true
            }
        }
        return holder
    }

    /** Tamanho visível + 12dp de cada lado reservados para o brilho. */
    private fun setup(card: GlassCard) {
        val ctx = card.context
        val lp = card.layoutParams
        when (kind) {
            FAV -> {
                lp.width = ctx.dpi(144)
                lp.height = ctx.dpi(94)
            }
            ALL -> {
                lp.width = ctx.dpi(124)
                lp.height = ctx.dpi(79)
            }
            else -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ctx.dpi(89)
            }
        }
        card.layoutParams = lp
        card.focusScale = if (kind == PICK) 1.05f else 1.08f
        card.focusLift = if (kind == PICK) 0f else ctx.dp(3f)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AppHolder -> {
                val app = items[position]
                holder.icon.setImageBitmap(app.icon)
                holder.label.text = app.label
                holder.card.glowColor = app.color
                holder.card.contentDescription = app.label
                holder.card.syncFocus()
            }
            is AddHolder -> {
                holder.plus.setTextColor(Look.palette.accent)
                holder.card.glowColor = Look.palette.accent
                holder.card.syncFocus()
            }
        }
    }

    inner class AppHolder(val card: GlassCard) : RecyclerView.ViewHolder(card) {
        val icon: ImageView = card.findViewById(R.id.app_icon)
        val label: TextView = card.findViewById(R.id.app_label)

        fun entry(): AppEntry? {
            val p = bindingAdapterPosition
            return if (p in items.indices) items[p] else null
        }
    }

    class AddHolder(val card: GlassCard) : RecyclerView.ViewHolder(card) {
        val plus: TextView = card.findViewById(R.id.tile_plus)
    }

    companion object {
        const val FAV = 0
        const val ALL = 1
        const val PICK = 2
        private const val TYPE_APP = 0
        private const val TYPE_ADD = 1
        private const val ADD_ID = Long.MIN_VALUE
    }
}

/** Sobrepõe as áreas de brilho dos cards para o espaço visível ficar no tamanho certo. */
class Overlap(private val h: Int, private val v: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(-h, -v, -h, -v)
    }
}
