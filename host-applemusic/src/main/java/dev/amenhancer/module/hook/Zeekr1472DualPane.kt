package dev.amenhancer.module.hook

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.amenhancer.host.applemusic.R
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Native song/lyrics panes on Apple Music 4.9.7.2. No changes to playback or lyric data. */
internal class Zeekr1472DualPane(
    private val application: Application,
    loader: ClassLoader,
    contract: JSONObject,
) {
    private val seams = FragmentProfileMembers(loader, contract, "zeekr1472DualPane", FragmentMemberRequirements(
        methods = setOf("createView", "initialize", "start", "select", "getView", "getId", "childManager",
            "stateSaved", "find", "replace", "remove", "commitNow", "newLyrics", "lyricsCreateView",
            "lyricsChrome", "lyricsMetrics", "gradientSize", "connectPlayer", "connectLyrics",
            "requestSheetState", "updateSheetState", "editorialVideoUrl", "editorialVideoPreview"),
        fields = setOf("binding", "bindingRoot", "selected", "guidePercent", "orientation",
            "leftToLeft", "leftToRight", "rightToLeft", "rightToRight", "topToTop", "topToBottom", "bottomToBottom",
            "sheet", "sheetState", "lyricsController", "lyricsSheet", "lyricsMetricsReady"),
        constructors = setOf("transaction", "guideline", "params"),
    ))
    private val names = contract.getJSONObject("resources").keys().asSequence().associateWith { name ->
        checkNotNull(application.resources.getIdentifier(name, "id", application.packageName).takeIf { it != 0 }) {
            "Missing Apple Music ID $name"
        }
    }
    private val state = checkNotNull(seams.field("selected").type.enumConstants).associateBy { (it as Enum<*>).name }
    private val song = state.getValue("SONG")
    private val lyrics = state.getValue("LYRICS")
    private val lyricsType = seams.method("lyricsCreateView").declaringClass
    private val playerShells = WeakHashMap<Any, WeakReference<PlayerShell>>()
    @Volatile private var enabled = false

    fun install() {
        // A partially registered hook group remains inert.
        hook("createView", after = { p ->
            (p.result as? ViewGroup)?.let(::mount)?.let { schedule(p.thisObject, it) }
        })
        hook("initialize", before = { p ->
            val controller = p.thisObject ?: return@hook
            val binding = seams.field("binding").get(controller) ?: return@hook
            val root = seams.field("bindingRoot").get(binding) as? ViewGroup ?: return@hook
            val pane = mount(root)
            if (eligible(pane.root.context)) {
                // u1() selects the bag default if null. Keep the native SONG transaction in
                // the original container and reserve LYRICS for the independent right pane.
                val selected = seams.field("selected")
                if (selected.get(controller).let { it == null || it === lyrics }) selected.set(controller, song)
            }
        }, after = { p -> schedule(p.thisObject) })
        hook("start", after = { p ->
            val pane = p.thisObject?.let(::mountedPane) ?: return@hook
            pane.needsConnection = true
            schedule(p.thisObject, pane)
        })
        // Both stock selectors use the aspect ratio only to reject DETAIL_TALL.
        // Keep their square-art and static-art paths intact, including when a song
        // is loaded while the Now Playing sheet is still collapsed.
        for (role in listOf("editorialVideoUrl", "editorialVideoPreview")) {
            hook(role, before = { p ->
                if (eligible(application)) p.args[1] = 0f
            })
        }
        hook("connectPlayer", after = { p ->
            val controller = p.thisObject ?: return@hook
            val pane = mountedPane(controller) ?: return@hook
            if (!pane.active) {
                schedule(controller, pane)
                return@hook
            }
            val sheet = seams.field("sheet").get(controller) ?: return@hook
            val state = seams.field("sheetState").getInt(sheet)
            // The stock C1 defers 1 (dragging) and 2 (settling) to a later callback.
            if (state == 1 || state == 2) return@hook
            val manager = seams.method("childManager").invoke(controller)
            connectLyrics(pane, sheet, seams.method("find").invoke(manager, LYRICS_TAG))
        })
        hook("select", before = { p ->
            if (p.args.firstOrNull() !== lyrics) return@hook
            val pane = p.thisObject?.let(::mountedPane) ?: return@hook
            if (pane.active && eligible(pane.root.context)) p.result = null
        })
        hook("requestSheetState", after = { p ->
            if (p.args.firstOrNull() == SHEET_EXPANDED) playerShells[p.thisObject]?.get()?.setExpanded(true)
        })
        hook("updateSheetState", after = { p ->
            val shell = playerShells[p.thisObject]?.get() ?: return@hook
            when (p.args.firstOrNull()) {
                SHEET_EXPANDED -> shell.setExpanded(true)
                SHEET_COLLAPSED, SHEET_HIDDEN, SHEET_HALF_EXPANDED -> shell.setExpanded(false)
            }
        })
        hook("lyricsCreateView", after = { p ->
            val fragment = p.thisObject ?: return@hook
            if (!isRightLyrics(fragment)) return@hook
            val root = p.result as? View ?: return@hook
            prepareLyricsView(root)
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    changedView: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    if (bottom <= top || right <= left) return
                    changedView.removeOnLayoutChangeListener(this)
                    // The stock onViewCreated post can run while the right pane is GONE.
                    changedView.post {
                        guard("initialize right lyrics metrics") {
                            val ready = seams.field("lyricsMetricsReady")
                            if (view(fragment) === root && !ready.getBoolean(fragment)) {
                                ready.setBoolean(fragment, seams.method("lyricsMetrics").invoke(fragment) == true)
                            }
                        }
                    }
                }
            })
        })
        hook("lyricsChrome", after = { p ->
            if (isRightLyrics(p.thisObject)) p.thisObject?.let(::view)?.let { hide(it, "controls") }
        })
        hook("lyricsMetrics", after = { p ->
            if (p.result == true && isRightLyrics(p.thisObject)) p.thisObject?.let(::view)?.let(::applyLyricFade)
        })
        enabled = true
    }

    private fun hook(
        role: String,
        before: ((ModernMethodHook.MethodHookParam) -> Unit)? = null,
        after: ((ModernMethodHook.MethodHookParam) -> Unit)? = null,
    ) {
        val method: Method = seams.method(role)
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled && before != null) guard(role) { before(param) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (enabled && param.throwable == null && after != null) guard(role) { after(param) }
            }
        })
    }

    private fun mount(root: ViewGroup): Pane {
        (root.getTag(R.id.am_enhancer_car_lyrics_host) as? Pane)?.let { return it }
        val player = checkNotNull(root.findViewById<ViewGroup>(id("player_root")))
        val primary = checkNotNull(player.findViewById<View>(id("player_fragments_host")))
        check(primary.parent === player)
        val originalParams = primary.layoutParams

        val guide = (seams.constructor("guideline").newInstance(root.context) as View).apply {
            id = View.generateViewId()
        }
        val guideParams = params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            seams.field("orientation").setInt(this, 1)
            seams.field("guidePercent").setFloat(this, SONG_COLUMN_FRACTION)
        }
        val leftParams = params(0, 0).apply {
            constraint("leftToLeft", 0)
            constraint("rightToLeft", guide.id)
            constraint("topToTop", 0)
            constraint("bottomToBottom", 0)
        }
        // FragmentManager persists container IDs across view recreation. Always recreate
        // the same slot, hidden until an eligible view owns the lyrics fragment again.
        val right = FrameLayout(root.context).apply {
            id = R.id.am_enhancer_car_lyrics_host
            visibility = View.GONE
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00101319, 0x60101319),
            )
            val inset = (24 * resources.displayMetrics.density).roundToInt()
            setPadding(inset, 0, inset, 0)
        }
        val rightParams = params(0, 0).apply {
            constraint("leftToRight", guide.id)
            constraint("rightToRight", 0)
            constraint("topToTop", 0)
            constraint("bottomToBottom", 0)
        }
        player.addView(guide, guideParams)
        player.addView(right, rightParams)
        return Pane(root, primary, right, originalParams, leftParams).also {
            root.setTag(R.id.am_enhancer_car_lyrics_host, it)
        }
    }

    private fun schedule(controller: Any?, mounted: Pane? = null) {
        controller ?: return
        val pane = mounted ?: mountedPane(controller) ?: return
        if (pane.pending) return
        pane.pending = true
        pane.root.post {
            pane.pending = false
            if (view(controller) !== pane.root || !pane.root.isAttachedToWindow) return@post
            guard("mount right lyrics") { updatePane(controller, pane) }
        }
    }

    private fun updatePane(controller: Any, pane: Pane) {
        val manager = seams.method("childManager").invoke(controller) ?: return
        if (seams.method("stateSaved").invoke(manager) == true) return
        val current = seams.method("find").invoke(manager, LYRICS_TAG)
        if (!eligible(pane.root.context)) {
            pane.show(false)
            pane.shell?.setExpanded(false)
            pane.needsConnection = true
            if (current != null) {
                val transaction = seams.constructor("transaction").newInstance(manager)
                seams.method("remove").invoke(transaction, current)
                seams.method("commitNow").invoke(transaction)
            }
            return
        }
        val fragment = current ?: run {
            val created = seams.method("newLyrics").invoke(null, Bundle())
            val transaction = seams.constructor("transaction").newInstance(manager)
            seams.method("replace").invoke(transaction, pane.right.id, created, LYRICS_TAG)
            // Run outside FragmentManager's lifecycle dispatch, synchronously, so a failed
            // attachment leaves the stock player at full width and its lyrics button intact.
            seams.method("commitNow").invoke(transaction)
            created
        }
        check(isRightLyrics(fragment)) { "Restored lyrics fragment does not own the car slot" }
        if (pane.needsConnection) {
            val sheet = seams.field("sheet").get(controller) ?: return
            if (!connectLyrics(pane, sheet, fragment)) return
        }
        bindPlayerShell(controller, pane)
        if (pane.shell != null) pane.show(true)
    }

    private fun bindPlayerShell(controller: Any, pane: Pane) {
        if (pane.shell != null) return
        val behavior = seams.field("sheet").get(controller) ?: return
        val sheetView = pane.root.parent as? ViewGroup ?: return
        if (sheetView.id != id("player_sheet_container")) return
        val container = sheetView.parent as? ViewGroup ?: return
        if (container.id != id("player_container") ||
            container.javaClass.name != "androidx.coordinatorlayout.widget.CoordinatorLayout") return
        val navigationRoot = container.parent as? ViewGroup ?: return
        if (navigationRoot.id != id("bottom_navigation_root_flat") &&
            navigationRoot.id != id("bottom_navigation_root_stacked")) return
        val original = container.layoutParams
        val fullWidth = params(0, original.height).apply {
            constraint("leftToLeft", 0)
            constraint("rightToRight", 0)
            constraint("topToTop", 0)
            constraint("bottomToBottom", 0)
        }
        if (fullWidth.javaClass != original.javaClass) return
        val shell = PlayerShell(container, original, fullWidth, pane)
        pane.shell = shell
        playerShells[behavior] = WeakReference(shell)
        shell.setExpanded(seams.field("sheetState").getInt(behavior) == SHEET_EXPANDED)
    }

    private fun connectLyrics(pane: Pane, sheet: Any, fragment: Any?): Boolean {
        if (fragment == null || !isRightLyrics(fragment) || view(fragment)?.parent !== pane.right) return false
        // Keep the right child on the same media and bottom-sheet lifecycle as the
        // stock tagged fragments; P1 also sends a snapshot of the current song.
        seams.method("connectLyrics").invoke(fragment)
        val controls = seams.field("lyricsController").get(fragment)
        seams.field("lyricsSheet").set(controls, sheet)
        pane.needsConnection = false
        return true
    }

    private fun mountedPane(controller: Any): Pane? = view(controller)?.getTag(R.id.am_enhancer_car_lyrics_host) as? Pane

    private fun prepareLyricsView(root: View) {
        hide(root, "current_player_item")
        hide(root, "controls")
        hide(root, "controls_tap_target")
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { margins ->
            margins.topMargin = 0
            root.layoutParams = margins
        }
        root.findViewById<View>(id("recycler_view_gradients"))?.let { fade ->
            fade.layoutParams.apply {
                constraint("topToTop", 0)
                constraint("topToBottom", -1)
            }.also { fade.layoutParams = it }
        }
    }

    private fun applyLyricFade(root: View) {
        val gradient = root.findViewById<View>(id("recycler_view_gradients")) ?: return
        val pixels = (root.height * 0.13f).roundToInt()
        if (pixels <= 0) return
        // The stock animator interpolates four bottom-fade stops by index.
        // Resize the fade while leaving its native colors and positions intact.
        seams.method("gradientSize").invoke(gradient, pixels, pixels)
    }

    private fun hide(root: View, name: String) {
        root.findViewById<View>(id(name))?.visibility = View.GONE
    }

    private fun id(name: String) = names.getValue(name)
    private fun isRightLyrics(fragment: Any?): Boolean = lyricsType.isInstance(fragment) &&
        seams.method("getId").invoke(fragment) == R.id.am_enhancer_car_lyrics_host
    private fun view(controller: Any) = seams.method("getView").invoke(controller) as? View
    private fun params(width: Int, height: Int): ViewGroup.LayoutParams =
        (seams.constructor("params").newInstance(width) as ViewGroup.LayoutParams).apply { this.height = height }
    private fun ViewGroup.LayoutParams.constraint(role: String, value: Int) = seams.field(role).setInt(this, value)

    private fun eligible(context: Context): Boolean = context.resources.configuration.run {
        orientation == Configuration.ORIENTATION_LANDSCAPE && screenWidthDp >= 640 && screenHeightDp >= 320
    }

    private fun guard(role: String, action: () -> Unit) {
        runCatching(action).onFailure { ModernXposedRuntime.log("4.9.7.2 car preview: $role failed", it) }
    }

    private class PlayerShell(
        private val container: View,
        private val collapsed: ViewGroup.LayoutParams,
        private val expanded: ViewGroup.LayoutParams,
        private val pane: Pane,
    ) {
        fun setExpanded(active: Boolean) {
            val target = if (active) expanded else collapsed
            if (container.layoutParams !== target) container.layoutParams = target
            pane.setExpanded(active)
        }
    }

    private class Pane(
        val root: ViewGroup,
        val primary: View,
        val right: FrameLayout,
        private val originalParams: ViewGroup.LayoutParams,
        private val splitParams: ViewGroup.LayoutParams,
    ) {
        var shell: PlayerShell? = null
        var pending = false
        var needsConnection = true
        var active = false
            private set
        private var expanded = false

        fun show(enabled: Boolean) {
            if (active == enabled) return
            active = enabled
            updateLayout()
        }

        fun setExpanded(expanded: Boolean) {
            if (this.expanded == expanded) return
            this.expanded = expanded
            updateLayout()
        }

        private fun updateLayout() {
            val split = active && expanded
            primary.layoutParams = if (split) splitParams else originalParams
            right.visibility = if (split) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val LYRICS_TAG = "ampp:zeekr1472:lyrics"
        const val SONG_COLUMN_FRACTION = 0.44f
        const val SHEET_EXPANDED = 3
        const val SHEET_COLLAPSED = 4
        const val SHEET_HIDDEN = 5
        const val SHEET_HALF_EXPANDED = 6
    }
}
