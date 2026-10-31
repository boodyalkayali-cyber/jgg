package com.scrollhub

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

class Section(val title: String, val url: String)

class Platform(
    val key: String,
    val title: String,
    val following: String,
    val explore: String,
    val sections: List<Section>,
    val navHrefs: List<String>,   // روابط أيقونات شريط الموقع الأصلي (للتعرف عليه وإخفائه)
    val navSels: List<String>,
    val hideNavDefault: Boolean,  // إخفاء الشريط الأصلي افتراضيًا
    val intercept: Boolean        // فتح الوسائط بالمشغل الخاص
)

class MainActivity : AppCompatActivity() {

    private val platforms = listOf(
        Platform("fb", "فيسبوك", "https://m.facebook.com/?sk=h_chr", "https://m.facebook.com/watch/", listOf(
            Section("الرئيسية", "https://m.facebook.com/"),
            Section("الأصدقاء", "https://m.facebook.com/friends/"),
            Section("فيديو", "https://m.facebook.com/watch/"),
            Section("الأسواق", "https://m.facebook.com/marketplace/"),
            Section("الإشعارات", "https://m.facebook.com/notifications/"),
            Section("الرسائل", "https://m.facebook.com/messages/"),
            Section("القائمة", "https://m.facebook.com/bookmarks/")
        ),
            listOf("/friends", "/watch", "/marketplace", "/notifications", "/bookmarks", "/menu", "/home.php", "/gaming", "/groups", "/profile.php"),
            listOf("#mJewelNav"), true, true),
        Platform("x", "تويتر", "https://x.com/home", "https://x.com/explore", listOf(
            Section("الرئيسية", "https://x.com/home"),
            Section("استكشاف", "https://x.com/explore"),
            Section("الإشعارات", "https://x.com/notifications"),
            Section("الرسائل", "https://x.com/messages"),
            Section("العلامات", "https://x.com/i/bookmarks")
        ),
            listOf("/home", "/explore", "/notifications", "/messages"),
            listOf("nav[role=\"navigation\"]"), false, true),
        Platform("ig", "إنستغرام", "https://www.instagram.com/", "https://www.instagram.com/explore/", listOf(
            Section("الرئيسية", "https://www.instagram.com/"),
            Section("استكشاف", "https://www.instagram.com/explore/"),
            Section("ريلز", "https://www.instagram.com/reels/"),
            Section("الرسائل", "https://www.instagram.com/direct/inbox/")
        ),
            listOf("/explore", "/reels", "/direct", "/accounts/activity"),
            listOf(), false, true),
        Platform("yt", "يوتيوب", "https://m.youtube.com/feed/subscriptions", "https://m.youtube.com/", listOf(
            Section("الرئيسية", "https://m.youtube.com/"),
            Section("شورتس", "https://m.youtube.com/shorts"),
            Section("الاشتراكات", "https://m.youtube.com/feed/subscriptions"),
            Section("المكتبة", "https://m.youtube.com/feed/library")
        ),
            listOf("/feed/subscriptions", "/shorts", "/feed/library"),
            listOf("ytm-pivot-bar-renderer"), false, false),
        Platform("th", "ثريدز", "https://www.threads.com/?filter=following", "https://www.threads.com/", listOf(
            Section("الرئيسية", "https://www.threads.com/"),
            Section("بحث", "https://www.threads.com/search"),
            Section("النشاط", "https://www.threads.com/activity")
        ),
            listOf("/search", "/activity"),
            listOf(), false, true)
    )

    private val views = HashMap<String, WebView>()
    private val exploreMode = HashMap<String, Boolean>()
    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var nav: BottomNavigationView
    private lateinit var sectionBar: LinearLayout
    private var current = platforms[0]
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var viewer: MediaViewer? = null
    private val prefs by lazy { getSharedPreferences("scrollhub", MODE_PRIVATE) }
    private var bgEnabled = false
    private var viewerEnabled = true

    private val UA = MediaUtils.UA
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // يخدع الموقع ليظن أن الصفحة ظاهرة دائمًا فلا يوقف الصوت عند الخروج
    private val BG_JS = "(function(){try{Object.defineProperty(document,'hidden',{get:function(){return false}});" +
            "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});" +
            "document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);}catch(e){}})()"

    private val EXTRACT_JS = """
        (function(){
          var seen={},r=[],blob=0;
          document.querySelectorAll('img').forEach(function(i){
            var u=i.currentSrc||i.src;
            if(u&&u.indexOf('http')==0&&i.naturalWidth>250&&!seen[u]&&r.length<40){seen[u]=1;r.push({t:'image',u:u});}
          });
          document.querySelectorAll('video').forEach(function(v){
            var u=v.currentSrc||v.src;
            if(!u){var s=v.querySelector('source');if(s)u=s.src;}
            if(u&&u.indexOf('http')==0){if(!seen[u]){seen[u]=1;r.push({t:'video',u:u});}}
            else if(u){blob++;}
          });
          return JSON.stringify({title:document.title,text:document.body.innerText.substring(0,30000),media:r,blob:blob});
        })()
    """.trimIndent()

    /** جسر يستقبل نقرات الوسائط من الصفحة */
    inner class Bridge {
        @JavascriptInterface
        fun openMedia(json: String) { runOnUiThread { openViewer(json) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        bgEnabled = prefs.getBoolean("bg", false)
        viewerEnabled = prefs.getBoolean("viewer", true)

        root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this)
        sectionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sectionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sectionBar)
        }
        container = FrameLayout(this)
        nav = BottomNavigationView(this)
        col.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(sectionScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        platforms.forEachIndexed { i, p -> nav.menu.add(0, i, i, p.title).setIcon(R.drawable.ic_feed) }
        nav.setOnItemSelectedListener { select(platforms[it.itemId]); true }

        val m = toolbar.menu
        m.add(0, 1, 0, "استكشاف").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 2, 1, "حفظ").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 3, 2, "المحفوظات").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        m.add(0, 4, 3, "حفظ الصوت (m4a) من الصفحة").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        m.add(0, 5, 4, "التشغيل في الخلفية").apply {
            isCheckable = true; isChecked = bgEnabled; setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        m.add(0, 6, 5, "إخفاء شريط الموقع الأصلي").apply {
            isCheckable = true; setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        m.add(0, 7, 6, "المشغل الخاص للوسائط").apply {
            isCheckable = true; isChecked = viewerEnabled; setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        m.add(0, 8, 7, "تحديث").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleMode()
                2 -> savePost(false)
                3 -> showSaved()
                4 -> savePost(true)
                5 -> {
                    it.isChecked = !it.isChecked
                    bgEnabled = it.isChecked
                    prefs.edit().putBoolean("bg", bgEnabled).apply()
                    if (bgEnabled) views.values.forEach { w -> w.evaluateJavascript(BG_JS, null) }
                    toast(if (bgEnabled) "التشغيل في الخلفية مفعّل (أعد تحميل الصفحة إن لزم)" else "تم الإيقاف")
                }
                6 -> {
                    it.isChecked = !it.isChecked
                    prefs.edit().putBoolean("nav_${current.key}", it.isChecked).apply()
                    views[current.key]?.evaluateJavascript("window.__sh&&__sh.setNav(${it.isChecked})", null)
                }
                7 -> {
                    it.isChecked = !it.isChecked
                    viewerEnabled = it.isChecked
                    prefs.edit().putBoolean("viewer", viewerEnabled).apply()
                    platforms.forEach { p ->
                        views[p.key]?.evaluateJavascript("window.__sh&&(__sh.intercept=${p.intercept && viewerEnabled})", null)
                    }
                }
                8 -> views[current.key]?.reload()
            }
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewer != null) { viewer?.requestClose(); return }
                if (customView != null) { hideCustom(); return }
                val w = views[current.key]
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })

        select(current)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun navOn(p: Platform) = prefs.getBoolean("nav_${p.key}", p.hideNavDefault)

    // ---------- شريط الأقسام الثابت (بديل شريط الموقع) ----------
    private fun buildSections(p: Platform) {
        sectionBar.removeAllViews()
        p.sections.forEach { s ->
            val tv = TextView(this).apply {
                text = s.title
                textSize = 14f
                alpha = 0.65f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    views[p.key]?.loadUrl(s.url)
                    for (i in 0 until sectionBar.childCount) {
                        (sectionBar.getChildAt(i) as TextView).apply { alpha = 0.65f; setTypeface(null, Typeface.NORMAL) }
                    }
                    alpha = 1f; setTypeface(null, Typeface.BOLD)
                }
            }
            sectionBar.addView(tv)
        }
    }

    private fun platformScript(p: Platform): String {
        val js = assets.open("scrollhub.js").bufferedReader().use { it.readText() }
        val pre = "window.__SH_PLATFORM=${JSONObject.quote(p.key)};" +
                "window.__SH_NAV_HREFS=${JSONArray(p.navHrefs)};" +
                "window.__SH_NAV_SELS=${JSONArray(p.navSels)};" +
                "window.__SH_INTERCEPT=${p.intercept && viewerEnabled};" +
                "window.__SH_NAV_ON=${navOn(p)};\n"
        return pre + js
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewFor(p: Platform): WebView {
        views[p.key]?.let { return it }
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = UA
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "ScrollHubBridge")

        val script = platformScript(p)
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(w, script, setOf("*"))

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                !(r.url.scheme == "http" || r.url.scheme == "https")
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                if (!docStart) v.evaluateJavascript(script, null)
                if (bgEnabled) v.evaluateJavascript(BG_JS, null)
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (!docStart) v.evaluateJavascript(script, null)
                if (bgEnabled) v.evaluateJavascript(BG_JS, null)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view; customCb = cb
                root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            override fun onHideCustomView() = hideCustom()
        }
        views[p.key] = w
        container.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        exploreMode[p.key] = false
        w.loadUrl(p.following)
        return w
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customCb?.onCustomViewHidden()
        customView = null; customCb = null
    }

    private fun select(p: Platform) {
        current = p
        val w = webViewFor(p)
        views.values.forEach { it.visibility = View.GONE }
        w.visibility = View.VISIBLE
        toolbar.title = p.title
        toolbar.menu.findItem(6)?.isChecked = navOn(p)
        buildSections(p)
        updateToggleTitle()
    }

    private fun updateToggleTitle() {
        toolbar.menu.findItem(1)?.title = if (exploreMode[current.key] == true) "المتابَعون" else "استكشاف"
    }

    private fun toggleMode() {
        val ex = !(exploreMode[current.key] ?: false)
        exploreMode[current.key] = ex
        views[current.key]?.loadUrl(if (ex) current.explore else current.following)
        updateToggleTitle()
    }

    // ---------- المشغل الخاص ----------
    private fun parseEntry(x: JSONObject) = MediaEntry(
        x.optString("key"), x.optString("t"), x.optString("u"),
        x.optString("p"), x.optString("link"), x.optBoolean("hls", false)
    )

    private fun parseEntries(raw: String?): List<MediaEntry> {
        return try {
            val arr = JSONArray(JSONTokener(raw).nextValue() as String)
            (0 until arr.length()).map { parseEntry(arr.getJSONObject(it)) }.filter { it.url.startsWith("http") }
        } catch (e: Exception) { emptyList() }
    }

    private fun openViewer(json: String) {
        if (viewer != null) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val arr = o.optJSONArray("items") ?: return
        val list = ArrayList<MediaEntry>()
        for (i in 0 until arr.length()) list.add(parseEntry(arr.getJSONObject(i)))
        if (list.isEmpty() || list.any { !it.url.startsWith("http") }) return
        val idx = o.optInt("index", 0).coerceIn(0, list.size - 1)
        val vertical = o.optString("mode") == "v"
        val v = MediaViewer(this, list, idx, vertical, views[current.key]?.url ?: "", viewerHost)
        viewer = v
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        views[current.key]?.evaluateJavascript("__sh.freeze(true)", null)
    }

    private val viewerHost = object : MediaViewer.Host {
        override fun save(e: MediaEntry, audioOnly: Boolean) = saveEntry(e, audioOnly)

        override fun loadMore(cb: (List<MediaEntry>) -> Unit) {
            val w = views[current.key]
            if (w == null) { cb(emptyList()); return }
            w.evaluateJavascript("__sh.more()", null)
            w.postDelayed({
                w.evaluateJavascript("JSON.stringify(__sh.reelList())") { raw -> cb(parseEntries(raw)) }
            }, 1800)
        }

        override fun close(navigateTo: String?) {
            viewer?.let { root.removeView(it) }
            viewer = null
            val w = views[current.key]
            w?.evaluateJavascript("__sh.freeze(false)", null)
            if (navigateTo != null) w?.loadUrl(navigateTo)
        }
    }

    private fun saveEntry(e: MediaEntry, audioOnly: Boolean) {
        if (e.hls) { toast("هذا الفيديو بث متدفق (HLS) ولا يمكن تنزيله مباشرة"); return }
        val ref = views[current.key]?.url ?: ""
        toast("جارٍ التنزيل…")
        Thread {
            try {
                val stamp = System.currentTimeMillis()
                val tmp = File(cacheDir, "dl_$stamp.tmp")
                val ct = MediaUtils.download(e.url, tmp, ref)
                if (ct == null) { runOnUiThread { toast("فشل التنزيل") }; return@Thread }
                if (audioOnly) {
                    val aud = File(cacheDir, "dl_$stamp.m4a")
                    if (MediaUtils.extractAudio(tmp, aud)) {
                        MediaUtils.publish(this, aud, "audio/mp4", "ScrollHub_$stamp.m4a")
                        runOnUiThread { toast("تم حفظ الصوت في Music/ScrollHub") }
                    } else runOnUiThread { toast("لا يوجد مسار صوتي قابل للاستخراج") }
                    aud.delete()
                } else {
                    val mime = MediaUtils.guessMime(ct, e.type)
                    val f = File(cacheDir, "dl_$stamp." + MediaUtils.extOf(mime))
                    tmp.renameTo(f)
                    MediaUtils.publish(this, f, mime, "ScrollHub_$stamp." + MediaUtils.extOf(mime))
                    f.delete()
                    runOnUiThread { toast("تم الحفظ في المعرض (ScrollHub)") }
                }
                tmp.delete()
            } catch (ex: Exception) {
                runOnUiThread { toast("تعذر الحفظ") }
            }
        }.start()
    }

    // ---------- حفظ الصفحة/المنشور كاملًا ----------
    private fun loadPosts(): JSONArray = JSONArray(prefs.getString("posts", "[]"))
    private fun storePosts(a: JSONArray) = prefs.edit().putString("posts", a.toString()).apply()

    private fun savePost(audioOnly: Boolean) {
        val w = views[current.key] ?: return
        val pageUrl = w.url ?: return
        val key = current.key
        toast("جارٍ الحفظ…")
        w.evaluateJavascript(EXTRACT_JS) { raw ->
            val data = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (e: Exception) { toast("تعذر قراءة الصفحة"); return@evaluateJavascript }
            val title = data.optString("title").ifBlank { pageUrl }
            val media = data.getJSONArray("media")
            val blobs = data.optInt("blob", 0)
            val id = System.currentTimeMillis().toString()

            if (audioOnly) {
                val vids = ArrayList<String>()
                for (i in 0 until media.length()) {
                    val o = media.getJSONObject(i)
                    if (o.getString("t") == "video") vids.add(o.getString("u"))
                }
                if (vids.isEmpty()) {
                    toast("لا يوجد فيديو برابط مباشر في هذه الصفحة" + if (blobs > 0) " (الفيديو مشفّر/متدفق)" else "")
                    return@evaluateJavascript
                }
                Thread {
                    var ok = 0
                    vids.forEachIndexed { i, u ->
                        try {
                            val tmp = File(cacheDir, "v_${id}_${i}.tmp")
                            val aud = File(cacheDir, "a_${id}_${i}.m4a")
                            if (MediaUtils.download(u, tmp, pageUrl) != null && MediaUtils.extractAudio(tmp, aud)) {
                                MediaUtils.publish(this, aud, "audio/mp4", "${MediaUtils.safe(title)}_$i.m4a"); ok++
                            }
                            tmp.delete(); aud.delete()
                        } catch (e: Exception) { }
                    }
                    runOnUiThread {
                        toast(if (ok > 0) "تم حفظ $ok ملف صوتي في Music/ScrollHub" else "لا يوجد مسار صوتي قابل للاستخراج")
                    }
                }.start()
                return@evaluateJavascript
            }

            val dir = File(filesDir, "posts/$id").apply { mkdirs() }
            File(dir, "text.txt").writeText("$title\n$pageUrl\n\n" + data.optString("text"))
            w.saveWebArchive(File(dir, "post.mht").absolutePath, false) { path ->
                val arr = loadPosts()
                arr.put(JSONObject().put("id", id).put("k", key).put("t", title).put("u", pageUrl).put("f", path ?: ""))
                storePosts(arr)
                Thread {
                    var ok = 0; var fail = 0
                    val mdir = File(dir, "media").apply { mkdirs() }
                    for (i in 0 until media.length()) {
                        val o = media.getJSONObject(i)
                        val t = o.getString("t"); val u = o.getString("u")
                        try {
                            val tmp = File(mdir, "$i.tmp")
                            val ct = MediaUtils.download(u, tmp, pageUrl)
                            if (ct == null) { fail++; continue }
                            val mime = MediaUtils.guessMime(ct, t)
                            val f = File(mdir, "$i." + MediaUtils.extOf(mime))
                            tmp.renameTo(f)
                            MediaUtils.publish(this, f, mime, "ScrollHub_${id}_$i." + MediaUtils.extOf(mime)); ok++
                        } catch (e: Exception) { fail++ }
                    }
                    runOnUiThread {
                        toast("تم حفظ المنشور (نص + نسخة صفحة) و$ok ملف وسائط" +
                                (if (fail > 0) "، تعذر $fail" else "") +
                                (if (blobs > 0) "، $blobs فيديو لا يُنزَّل مباشرة" else ""))
                    }
                }.start()
            }
        }
    }

    // ---------- المحفوظات ----------
    private fun showSaved() {
        val arr = loadPosts()
        if (arr.length() == 0) { toast("لا توجد منشورات محفوظة"); return }
        val labels = Array(arr.length()) {
            val o = arr.getJSONObject(it)
            "[${platforms.first { p -> p.key == o.getString("k") }.title}] ${o.getString("t")}"
        }
        AlertDialog.Builder(this)
            .setTitle("المحفوظات (اضغط للفتح بدون إنترنت، مطولًا للحذف)")
            .setItems(labels) { _, i -> openPost(arr.getJSONObject(i)) }
            .create().apply {
                setOnShowListener {
                    listView.setOnItemLongClickListener { _, _, pos, _ ->
                        File(filesDir, "posts/" + arr.getJSONObject(pos).getString("id")).deleteRecursively()
                        val n = JSONArray()
                        for (j in 0 until arr.length()) if (j != pos) n.put(arr.get(j))
                        storePosts(n); dismiss(); toast("تم الحذف"); true
                    }
                }
            }.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPost(o: JSONObject) {
        val wv = WebView(this)
        wv.settings.apply { allowFileAccess = true; javaScriptEnabled = false; blockNetworkLoads = true }
        wv.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.75).toInt())
        wv.loadUrl("file://" + o.getString("f"))
        AlertDialog.Builder(this)
            .setView(wv)
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("فتح الرابط الأصلي") { _, _ ->
                val p = platforms.first { it.key == o.getString("k") }
                nav.selectedItemId = platforms.indexOf(p)
                webViewFor(p).loadUrl(o.getString("u"))
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- الخلفية ----------
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        if (bgEnabled) {
            views.values.forEach { it.resumeTimers() }
            ContextCompat.startForegroundService(this, Intent(this, BackgroundService::class.java))
        } else {
            viewer?.pausePlayback()
        }
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, BackgroundService::class.java))
    }

    override fun onDestroy() {
        viewer?.release()
        super.onDestroy()
    }
}
