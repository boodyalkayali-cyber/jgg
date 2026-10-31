package com.scrollhub

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.github.chrisbanes.photoview.PhotoView

/**
 * مشغّل الوسائط الخاص: تمرير جانبي بين وسائط المنشور (vertical=false)
 * أو تمرير عمودي بين الريلز (vertical=true).
 */
@OptIn(UnstableApi::class)
class MediaViewer(
    ctx: Context,
    private val items: MutableList<MediaEntry>,
    startIndex: Int,
    private val vertical: Boolean,
    private val referer: String,
    private val host: Host
) : FrameLayout(ctx) {

    interface Host {
        fun save(e: MediaEntry, audioOnly: Boolean)
        fun loadMore(cb: (List<MediaEntry>) -> Unit)
        fun close(navigateTo: String?)
    }

    class VH(val root: FrameLayout, val photo: PhotoView?, val pv: PlayerView?) : RecyclerView.ViewHolder(root) {
        var player: ExoPlayer? = null
        var entry: MediaEntry? = null
    }

    private val pager = ViewPager2(ctx)
    private val counter = TextView(ctx)
    private val btnAudio = chip("صوت")
    private val attached = HashSet<VH>()
    private val startKey: String? = items.getOrNull(startIndex)?.key
    private var loading = false
    private var moved = false
    private val adapter = Adapter()

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        pager.orientation = if (vertical) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
        pager.adapter = adapter
        pager.setCurrentItem(startIndex, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { onSelected(position) }
        })
        addView(pager, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
            setBackgroundColor(0x55000000)
        }
        val close = chip("✕").apply { setOnClickListener { requestClose() } }
        counter.setTextColor(Color.WHITE)
        counter.gravity = Gravity.CENTER
        val save = chip("حفظ").apply { setOnClickListener { cur()?.let { host.save(it, false) } } }
        btnAudio.setOnClickListener { cur()?.let { host.save(it, true) } }
        val web = chip("ويب").apply {
            setOnClickListener {
                val c = cur()
                release()
                host.close(c?.link?.takeIf { it.isNotBlank() })
            }
        }
        bar.addView(close)
        bar.addView(counter, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(btnAudio)
        bar.addView(space())
        bar.addView(save)
        bar.addView(space())
        bar.addView(web)
        addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        updateUi(startIndex)
    }

    private fun space() = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun chip(t: String) = TextView(context).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x66FFFFFF) }
    }

    private fun cur(): MediaEntry? = items.getOrNull(pager.currentItem)

    private fun updateUi(pos: Int) {
        counter.text = "${pos + 1}/${items.size}"
        btnAudio.visibility = if (items.getOrNull(pos)?.type == "video") View.VISIBLE else View.GONE
    }

    private fun onSelected(pos: Int) {
        attached.forEach { it.player?.playWhenReady = (it.bindingAdapterPosition == pos) }
        updateUi(pos)
        if (vertical && pos >= items.size - 2) more()
    }

    private fun more() {
        if (loading) return
        loading = true
        moved = true
        host.loadMore { added ->
            loading = false
            val keys = items.map { it.key }.toHashSet()
            val n = items.size
            added.filter { it.key !in keys }.forEach { items.add(it) }
            if (items.size > n) adapter.notifyItemRangeInserted(n, items.size - n)
            updateUi(pager.currentItem)
        }
    }

    /** يُستدعى عند زر الرجوع */
    fun requestClose() {
        val c = cur()
        val changed = c != null && c.key != startKey
        release()
        host.close(if (vertical && (changed || moved) && c != null && c.link.isNotBlank()) c.link else null)
    }

    fun pausePlayback() { attached.forEach { it.player?.playWhenReady = false } }

    fun release() { attached.toList().forEach { stopPlayer(it) } }

    private fun startPlayer(h: VH) {
        val e = h.entry ?: return
        val pv = h.pv ?: return
        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent(MediaUtils.UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to referer))
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .build()
        p.setMediaItem(MediaItem.fromUri(e.url))
        p.repeatMode = if (vertical) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = h.bindingAdapterPosition == pager.currentItem
        pv.player = p
        h.player = p
        attached.add(h)
    }

    private fun stopPlayer(h: VH) {
        h.pv?.player = null
        h.player?.release()
        h.player = null
        attached.remove(h)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position].type == "video") 1 else 0

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val fl = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
            }
            return if (viewType == 1) {
                val pv = PlayerView(parent.context).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
                fl.addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                VH(fl, null, pv)
            } else {
                val ph = PhotoView(parent.context)
                fl.addView(ph, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                VH(fl, ph, null)
            }
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.entry = e
            val ph = h.photo ?: return
            val url = GlideUrl(
                e.url,
                LazyHeaders.Builder().addHeader("Referer", referer).addHeader("User-Agent", MediaUtils.UA).build()
            )
            Glide.with(ph).load(url).into(ph)
        }

        override fun onViewAttachedToWindow(h: VH) { if (h.pv != null) startPlayer(h) }
        override fun onViewDetachedFromWindow(h: VH) { if (h.pv != null) stopPlayer(h) }
    }
}
