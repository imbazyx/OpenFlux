package io.openflux.android.platform

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.openflux.android.core.AppSelection

/**
 * Picks which applications the tunnel carries. Reached from Settings → VPN.
 *
 * The list comes from what the PackageManager reports at the moment the screen
 * opens, so an app uninstalled since the last save is simply gone instead of
 * failing the VPN setup later. Saving only writes preferences; the rule is read
 * the next time the tunnel is established, which keeps a running connection
 * alive while the user browses.
 */
class AppSelectionActivity : Activity() {
    private companion object {
        const val STATE_PICKED = "picked"
        const val STATE_ONLY = "only"
        const val STATE_SEARCH = "search"
    }

    private data class Item(val pkg: String, val label: String, val system: Boolean)

    // Replaced wholesale, never mutated. Filter.performFiltering runs on a
    // worker thread, so a list that the UI thread appends to while a search is
    // in flight is a ConcurrentModificationException waiting to happen on a
    // phone with a few hundred apps. Item is immutable, so publishing a new
    // reference is enough to make every reader see a stable list.
    @Volatile private var items: List<Item> = emptyList()
    private val picked = linkedSetOf<String>()
    private lateinit var list: ListView
    private lateinit var only: CheckBox
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var resetBtn: Button
    private lateinit var saveBtn: Button
    private var loaded = false
    private var loading = false
    private var loadError = false

    /** Set by the checkbox listener, so loader corrections are not mistaken for user edits. */
    private var userToggledOnly = false

    /** True while the code itself changes the checkbox, so the listener stays quiet. */
    private var suppressOnlyListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore first: a rotation must not silently drop picks the user made
        // but has not saved yet.
        if (savedInstanceState != null) {
            picked.addAll(savedInstanceState.getStringArrayList(STATE_PICKED).orEmpty())
        } else {
            picked.addAll(AppSelection.packages(this))
        }
        setContentView(build())
        applyInsets()
        if (savedInstanceState != null) {
            only.isChecked = savedInstanceState.getBoolean(STATE_ONLY)
            search.setText(savedInstanceState.getString(STATE_SEARCH).orEmpty())
        }
        val adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            // A disabled ListView still delivers clicks on Android, so the
            // "only selected" switch has to gate the tap here. Without this the
            // user picks apps in a greyed-out list and the selections appear
            // later, unexplained, the moment they enable the switch.
            if (!only.isChecked) return@setOnItemClickListener
            val item = adapter.itemAt(position) ?: return@setOnItemClickListener
            if (!picked.add(item.pkg)) picked.remove(item.pkg)
            list.invalidateViews()
            sync()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                adapter.filter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        only.setOnCheckedChangeListener { _, _ ->
            // Distinguish a real tap from our own setChecked() corrections, which
            // must not count as the user having made a choice. The flag alone was
            // not enough: our own correction fires this very listener, so it used
            // to set userToggledOnly itself and quietly disarm the guard for any
            // second load. suppressOnlyListener is what actually separates them.
            if (!suppressOnlyListener) userToggledOnly = true
            sync()
        }
        sync()
    }

    /** Sets the checkbox without it counting as the user having toggled it. */
    private fun setOnlyChecked(value: Boolean) {
        if (only.isChecked == value) return
        suppressOnlyListener = true
        only.isChecked = value
        suppressOnlyListener = false
    }

    /**
     * Nothing was ever saved by pressing Back, so a user who ticked apps, read
     * "5" off the summary and left got zero with no word about it. The
     * predictive-back gesture makes it worse: there the animation itself
     * promises the change is being kept. Ask before throwing the work away.
     */
    @Deprecated("Kept for API < 33, where onBackInvokedCallback does not exist")
    override fun onBackPressed() {
        if (dirty()) {
            AlertDialog.Builder(this)
                .setTitle("Не сохранять изменения?")
                .setMessage("Выбранные приложения не применятся. Нажмите «Сохранить», чтобы применить их при следующем подключении.")
                .setPositiveButton("Не сохранять") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Остаться", null)
                .setCancelable(true)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * At targetSdk 35+ Android enforces edge-to-edge in every window, so a
     * plain Activity lays its content out under the status and navigation bars:
     * the title would sit behind the clock and the "Сохранить" button behind the
     * gesture handle. MainActivity handles this with enableEdgeToEdge; this
     * window has to consume the system-bar insets itself.
     */
    private fun applyInsets() {
        val root = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val base = resources.displayMetrics.density
        val l = (16 * base).toInt()
        val t = (12 * base).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // systemBars alone is not enough here. This activity targets SDK 36,
            // where the window is no longer resized for the IME, so the soft
            // keyboard has to be counted explicitly - without it the summary and
            // both buttons sit under the keyboard the moment the user types in
            // the search box, which is the main way this screen is used.
            // displayCutout matters in landscape, where systemBars left/right are
            // 0 and the title would otherwise be drawn under the camera.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left + l, bars.top + t, bars.right + l, bars.bottom + t)
            insets
        }
    }

    private fun build(): View {
        val dp = { n: Int -> (n * resources.displayMetrics.density).toInt() }
        val background = themeColor(android.R.attr.colorBackground, android.graphics.Color.WHITE)
        val primary = themeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK)
        val secondary = themeColor(android.R.attr.textColorSecondary, android.graphics.Color.DKGRAY)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // The header scrolls so the footer can never be pushed off the screen.
        // It must be a WEIGHTED child, not WRAP_CONTENT: inside a vertical
        // LinearLayout, WRAP_CONTENT is measured with AT_MOST(parent size), so a
        // header taller than the screen takes ALL of it, the weighted ListView
        // collapses to 0 and the summary plus both buttons are laid out past the
        // bottom edge - clipped, because clipChildren defaults to true. A
        // weighted child is measured at exactly its share, so header and list
        // split what is left over and the footer is always on screen. isFillViewport
        // only matters once the header gets a bounded height: it re-measures the
        // child to that height, which is what lets a short header stay short.
        val header = ScrollView(this).apply { isFillViewport = true }
        val headerColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(
            headerColumn,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))

        headerColumn.addView(TextView(this).apply {
            text = "Приложения через прокси"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(primary)
        })
        headerColumn.addView(TextView(this).apply {
            text = "По умолчанию через ноду идёт весь трафик телефона. Можно оставить в туннеле только выбранные приложения — остальные выйдут в интернет напрямую, мимо ноды."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
            setPadding(0, dp(8), 0, dp(8))
        })

        only = CheckBox(this).apply {
            text = "Только выбранные приложения"
            // Seeded from the NORMALISED rule, exactly what sync() and dirty()
            // compare against. Seeding from the raw stored rule (only=true with
            // a non-empty set) left the form dirty before the user touched
            // anything, so Back asked "discard your changes?" over a rule that
            // would have degraded to a full tunnel anyway.
            isChecked = AppSelection.onlySelected(this@AppSelectionActivity) &&
                AppSelection.selected(this@AppSelectionActivity).any { isInstalled(it) }
            setTextColor(primary)
        }
        headerColumn.addView(only)

        search = EditText(this).apply {
            hint = "Поиск по названию"
            setSingleLine()
        }
        headerColumn.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        list = ListView(this).apply { divider = null }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        summary = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        }
        root.addView(summary)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        // Bare assignment, NOT `val`: these are the lateinit fields above. A local
        // `val` of the same name shadowed them, so enableActions() tested a field
        // that was never assigned, returned on its guard forever, and left both
        // buttons disabled on every path. Compiles clean, ships dead.
        resetBtn = Button(this).apply {
            text = "Сбросить"
            setOnClickListener {
                // Clears the form only, it does not save. A button that commits
                // the same form as "Сохранить" makes two irreversible writes out
                // of one screen, and a mis-tap here used to destroy a selection
                // that took minutes to build with no way back. Back now asks
                // before discarding, so nothing is lost by not committing here.
                picked.clear()
                only.isChecked = false
                search.setText("")   // also restores the unfiltered list
                (list.adapter as? Adapter)?.showAll()
                sync()
            }
        }
        row.addView(resetBtn)
        saveBtn = Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                // An enabled switch with nothing ticked would leave the phone
                // with a tunnel nobody uses; store the full-tunnel rule instead.
                val onlySelected = only.isChecked && picked.isNotEmpty()
                AppSelection.save(this@AppSelectionActivity, onlySelected, picked)
                Toast.makeText(
                    this@AppSelectionActivity,
                    "Сохранено. Применится при следующем подключении.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
        row.addView(saveBtn)
        root.addView(row)
        resetBtn.isEnabled = false
        saveBtn.isEnabled = false
        return root
    }

    /** True when the package is installed and is not us. */
    private fun isInstalled(pkg: String): Boolean =
        pkg != packageName &&
            runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    /**
     * Both buttons stay disabled until the app list is in. During the load the
     * list is empty and the summary says "Загрузка…", so an enabled "Сохранить"
     * would write an empty allow list over a selection the user never even saw -
     * silently, and then claim "Сохранено". Nothing on screen says what Save
     * would persist in that window, so the honest answer is to not offer it.
     */
    private fun enableActions() {
        // No ::isInitialized guard. It used to be here and it is exactly what
        // turned the shadowing bug above into a silent dead screen: the guard
        // caught the never-assigned field and returned instead of crashing, so
        // both buttons stayed greyed out with no error anywhere. build() assigns
        // these before any callback can run, so there is nothing to guard.
        saveBtn.isEnabled = true
        resetBtn.isEnabled = true
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun sync() {
        list.alpha = if (only.isChecked) 1f else 0.4f
        // One predicate, used by the summary, by dirty() and by Save. The three
        // used to compute the rule separately and drifted apart: the summary
        // said "nothing will be applied" while Save wrote a FULL TUNNEL, and the
        // saved count included apps that had been uninstalled, so this screen and
        // Settings answered the same question differently.
        val willPerApp = only.isChecked && picked.isNotEmpty()
        val kept = picked.count { isInstalled(it) }
        // What is stored, filtered to what still exists.
        //
        // This is the same expression as in AndroidPlatformServices and
        // CoreService, but NOT the same rule, and the difference is real:
        // CoreService also models VpnService.Builder.verifyApp rejecting a
        // package, and degrades to a full tunnel when it does. This screen and
        // perAppSummary() only ever ask getApplicationInfo, so a package the
        // builder refuses - a work-profile app, an app from a secondary user,
        // or one uninstalled between the check and establish() - is reported
        // here as selected while the tunnel is actually carrying the whole
        // phone. The window is one connection, and CoreService rewrites the
        // stored rule when it happens, so it self-corrects. Asserting that all
        // three agree was wrong; they agree on installation, not on acceptance.
        val savedLive = AppSelection.selected(this@AppSelectionActivity).filter { isInstalled(it) }
        val savedPerApp = AppSelection.onlySelected(this@AppSelectionActivity) && savedLive.isNotEmpty()
        val pending = if (!willPerApp) {
            "весь трафик телефона"
        } else {
            "${pluralApps(kept)} — остальные напрямую"
        }
        val applied = if (!savedPerApp) {
            "весь трафик телефона"
        } else {
            "${pluralApps(savedLive.size)} — остальные напрямую"
        }
        // "Сейчас сохранено" must describe what is IN the prefs, not the filtered
        // view of it. A stored only=true with both apps uninstalled printed
        // "весь трафик телефона" here, which is a statement about the future,
        // not about the rule that is actually stored and would be applied
        // tomorrow if the apps came back.
        val storedRaw = AppSelection.selected(this@AppSelectionActivity)
        val storedText = if (AppSelection.onlySelected(this@AppSelectionActivity) &&
            storedRaw.isNotEmpty()
        ) {
            "${pluralApps(storedRaw.size)} — остальные напрямую"
        } else {
            "весь трафик телефона"
        }
        summary.text = when {
            loadError -> "Не удалось прочитать список приложений. Сейчас сохранено: $storedText."
            !loaded -> "Загрузка списка приложений…"
            willPerApp != savedPerApp || picked.toSet() != savedLive.toSet() ->
                "Будет применено: $pending.\nСейчас сохранено: $storedText."
            else -> "Сохранено: $storedText."
        }
    }

    /** True when the form differs from what is stored, i.e. Back would lose work. */
    private fun dirty(): Boolean {
        val savedLive = AppSelection.selected(this@AppSelectionActivity).filter { isInstalled(it) }
        // BOTH sides need the same filter, not just the stored one. Normalising
        // only savedLive fixed the mode half and left the set half: picked still
        // held packages the loader had not pruned yet, so a rule whose apps were
        // all uninstalled still read as dirty and Back still asked "discard your
        // changes?" over a form nobody had touched. It healed itself only once the
        // async load finished, which is a window, not a fix.
        val pickedLive = picked.filterTo(HashSet()) { isInstalled(it) }
        val sameMode = only.isChecked ==
            (AppSelection.onlySelected(this@AppSelectionActivity) && savedLive.isNotEmpty())
        return !sameMode || pickedLive != savedLive.toSet()
    }

    /**
     * Walks every installed package and resolves each label, which takes long
     * enough on a full phone to be worth keeping off the main thread.
     */
    private fun loadInstalled(): List<Item> {
        val pm = packageManager
        val own = packageName
        return pm.getInstalledApplications(0)
            .filter { it.packageName != own }
            .map { app: ApplicationInfo ->
                Item(
                    pkg = app.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName),
                    system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    override fun onResume() {
        super.onResume()
        // Always re-read the stored rule: CoreService rewrites it when the chosen
        // apps stop existing, so a summary computed once at onCreate goes stale
        // and starts contradicting the Back dialog, which reads prefs live.
        if (loaded && !loadError) {
            sync()
            return
        }
        // A failed enumeration used to be permanent: `loaded` stayed true, so
        // this early-return fired forever and the screen kept its dead buttons
        // with no way back. A PackageManager hiccup is usually transient, so let
        // a resume retry it. The buttons stay disabled until a load succeeds.
        if (loading) return
        // Retry means retry: clear the previous verdict before loading again.
        loadError = false
        loaded = false
        loading = true
        Thread({
            // Without this the throw escapes Thread.run, hits the default
            // uncaught handler and takes the whole process down: a single
            // PackageManager hiccup would crash the app, not the screen.
            val loadedItems = runCatching { loadInstalled() }
            runOnUiThread {
                // The Activity may be gone (back press, rotation) by now.
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                val found = loadedItems.getOrElse { error ->
                    Log.w("OpenFluxApps", "cannot enumerate installed apps", error)
                    // Mark the screen loaded anyway: otherwise sync() would keep
                    // saying "Загрузка…" over an error the user cannot act on.
                    loadError = true
                    loaded = true
                    // Do NOT prune here. Pruning asks isInstalled() about every
                    // package, and the one component we know is broken right now
                    // is exactly the one answering those questions: every lookup
                    // comes back false and the whole selection is wiped in memory
                    // - with no error shown, because dirty() then compares two
                    // empty sets and reports no change. Guessing what is alive
                    // while the only oracle is down is not a repair, it is data
                    // loss. The stale entries stay until a load actually succeeds.
                    if (picked.isEmpty() && !userToggledOnly) setOnlyChecked(false)
                    // Both buttons stay disabled. With the list missing, every
                    // isInstalled() on this screen answers false, so the
                    // checkbox seeds UNCHECKED even though the stored rule says
                    // only=true with two apps - and Save then writes
                    // only=false, destroying the rule, while toasting
                    // "Сохранено". dirty() returns false there too, because it
                    // filters both sides through the same broken oracle, so Back
                    // offers no warning either. The list is the whole point of
                    // this screen; without it there is nothing honest to save.
                    sync()
                    return@runOnUiThread
                }
                items = found
                // Drop anything that disappeared since it was ticked, so the
                // count in the summary and the saved list stay honest.
                val alive = found.mapTo(HashSet()) { it.pkg }
                val dropped = picked.count { it !in alive }
                if (dropped > 0) picked.retainAll(alive)
                // The checkbox was set from the stored rule, which can name
                // apps that are now gone. Left ticked with an empty list it
                // contradicted the summary on the very same screen: the box
                // promised per-app mode while the text said full tunnel.
                // Only correct the box if the USER left it alone: the box and
                // the search field are live during the load, and forcing the box
                // off after the user ticked it made the screen look dead - every
                // later tap on the list is gated on this box.
                if (picked.isEmpty() && !userToggledOnly) setOnlyChecked(false)
                loaded = true
                enableActions()
                // Re-apply the search rather than dumping the whole list: a
                // query typed during the load already returned nothing because
                // items was still empty, and showAll() would then reveal every
                // app while the search box still holds the text.
                (list.adapter as? Adapter)?.refreshForCurrentQuery()
                if (dropped > 0) {
                    Toast.makeText(
                        this,
                        "${pluralApps(dropped)} больше нет на телефоне — убраны из списка.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                sync()
            }
        }, "openflux-app-list").start()
    }

    /** A rotation must not silently drop the picks made but not saved yet. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_PICKED, ArrayList(picked))
        outState.putBoolean(STATE_ONLY, only.isChecked)
        outState.putString(STATE_SEARCH, search.text.toString())
    }

    private inner class Adapter : BaseAdapter(), Filterable {
        private var shown: List<Item> = emptyList()

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: TextView(this@AppSelectionActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, pad / 3)
            }
            val item = shown[position]
            (view as TextView).apply {
                text = if (item.system) item.label else "${item.label}  ·  ${item.pkg}"
                setTextColor(themeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK))
                alpha = if (picked.contains(item.pkg)) 1f else 0.55f
            }
            return view
        }

        fun itemAt(position: Int): Item? = shown.getOrNull(position)

        fun showAll() {
            shown = items
            notifyDataSetChanged()
        }

        /** Re-runs the filter over the freshly loaded list, query intact. */
        fun refreshForCurrentQuery() {
            val query = search.text?.toString().orEmpty()
            if (query.isEmpty()) showAll() else appFilter.filter(query)
        }

        private val appFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val needle = constraint?.toString()?.trim()?.lowercase().orEmpty()
                // Read the reference once: items is swapped by the loader thread
                // and never mutated, so any single read sees a stable list.
                val source = items
                val out = FilterResults()
                out.values = if (needle.isEmpty()) {
                    source
                } else {
                    source.filter {
                        it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle)
                    }
                }
                return out
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                @Suppress("UNCHECKED_CAST")
                shown = results?.values as? List<Item> ?: emptyList()
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = appFilter
    }
}
