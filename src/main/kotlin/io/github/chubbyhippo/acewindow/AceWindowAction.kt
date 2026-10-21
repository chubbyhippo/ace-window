package io.github.chubbyhippo.acewindow

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.ln

class AceWindowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val editor = CommonDataKeys.EDITOR.getData(event.dataContext) ?: return
        ApplicationManager.getApplication().getService(AceWindowLifecycle::class.java)
        AceWindow.start(editor, swap = false)
    }
}

class AceSwapWindowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val editor = CommonDataKeys.EDITOR.getData(event.dataContext) ?: return
        ApplicationManager.getApplication().getService(AceWindowLifecycle::class.java)
        AceWindow.start(editor, swap = true)
    }
}

class AceWindowTypedHandler(
    private val original: TypedActionHandler,
) : TypedActionHandler {
    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        if (!AceWindow.handleTyped(charTyped)) {
            original.execute(editor, charTyped, dataContext)
        }
    }
}

@Service(Service.Level.APP)
class AceWindowLifecycle : Disposable {
    init {
        IdeEventQueue.getInstance().addDispatcher(AceWindow.dispatcher, this)
    }

    override fun dispose() {
        AceWindow.cancel()
    }
}

@Suppress("TooManyFunctions")
object AceWindow {
    private const val LABEL_KEYS = "asdfghjkl"
    private const val LABEL_THRESHOLD = 2
    private const val LABEL_EPSILON = 1e-6
    private const val ORDER_Y_MULTIPLIER = 100_000
    private const val LABEL_PADDING = 4
    private const val COLOR_RED = 0x2E
    private const val COLOR_GREEN = 0x34
    private const val COLOR_BLUE = 0x40

    private enum class Plan { NONE, OTHER, LABELS }

    internal class Target(
        val rect: Rectangle,
        val editor: Editor?,
        val focusComponent: JComponent,
        val container: Component,
        val layer: JLayeredPane?,
    )

    private class Session(
        val swap: Boolean,
        val targets: List<Target>,
        val current: Target?,
    ) {
        var prefix = ""
        val canvases = mutableListOf<JComponent>()
    }

    private var session: Session? = null

    internal val dispatcher =
        object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean = this@AceWindow.dispatch(e)
        }

    fun start(
        editor: Editor,
        swap: Boolean,
    ) {
        val frame = SwingUtilities.getWindowAncestor(editor.component)
        val targets = if (frame != null) collect(editor, frame, swap) else emptyList()
        begin(swap, targets, currentTarget(targets, editor))
    }

    internal fun begin(
        swap: Boolean,
        targets: List<Target>,
        current: Target?,
    ) {
        cancel()
        when (plan(targets.size)) {
            Plan.NONE -> {
                return
            }

            Plan.OTHER -> {
                perform(swap, otherTarget(targets, current), current)
            }

            Plan.LABELS -> {
                session = Session(swap, targets, current)
                paintLabels()
            }
        }
    }

    fun cancel() {
        session?.canvases?.forEach(::detach)
        session = null
    }

    fun handleTyped(char: Char): Boolean = session?.let { active -> pick(active, char) } ?: false

    private fun plan(count: Int): Plan =
        when {
            count <= 1 -> Plan.NONE
            count <= LABEL_THRESHOLD -> Plan.OTHER
            else -> Plan.LABELS
        }

    internal fun otherTarget(
        targets: List<Target>,
        current: Target?,
    ): Target = targets.first { it !== current }

    internal fun ordered(targets: List<Target>): List<Target> =
        targets.sortedBy { target -> target.rect.x * ORDER_Y_MULTIPLIER + target.rect.y }

    private fun currentTarget(
        targets: List<Target>,
        editor: Editor,
    ): Target? {
        val byEditor = targets.firstOrNull { it.editor === editor }
        if (byEditor != null) return byEditor
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        return targets.firstOrNull { SwingUtilities.isDescendingFrom(focus, it.container) }
    }

    private fun collect(
        editor: Editor,
        frame: Window,
        swap: Boolean,
    ): List<Target> {
        val layer = (frame as? RootPaneContainer)?.rootPane?.layeredPane
        val editors = editorTargets(editor, frame, layer)
        val panels = if (swap) emptyList() else previewTargets(editor, frame, layer) + toolWindowTargets(editor, frame, editors, layer)
        return ordered(editors + panels)
    }

    private fun editorTargets(
        editor: Editor,
        frame: Window,
        layer: JLayeredPane?,
    ): List<Target> =
        (listOf(editor) + visibleEditors(editor, frame)).mapNotNull { visibleEditor ->
            rectIn(frame, visibleEditor.component)?.let {
                Target(it, visibleEditor, visibleEditor.contentComponent, visibleEditor.contentComponent, layer)
            }
        }

    private fun visibleEditors(
        editor: Editor,
        frame: Window,
    ): List<Editor> =
        EditorFactory.getInstance().allEditors.filter { other ->
            other !== editor &&
                (other as? EditorEx)?.isOneLineMode != true &&
                other.component.isShowing &&
                SwingUtilities.getWindowAncestor(other.component) === frame &&
                !SwingUtilities.isDescendingFrom(other.component, editor.component) &&
                !SwingUtilities.isDescendingFrom(editor.component, other.component)
        }

    private fun previewTargets(
        editor: Editor,
        frame: Window,
        layer: JLayeredPane?,
    ): List<Target> {
        val project = editor.project ?: return emptyList()
        return FileEditorManager
            .getInstance(project)
            .allEditors
            .filterIsInstance<TextEditorWithPreview>()
            .mapNotNull { composite ->
                val preview = composite.previewEditor
                if (SwingUtilities.getWindowAncestor(preview.component) !== frame) return@mapNotNull null
                rectIn(frame, preview.component)?.let {
                    Target(it, null, preview.preferredFocusedComponent ?: preview.component, preview.component, layer)
                }
            }
    }

    private fun toolWindowTargets(
        editor: Editor,
        frame: Window,
        editors: List<Target>,
        layer: JLayeredPane?,
    ): List<Target> {
        val project = editor.project ?: return emptyList()
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.flatMap { id ->
            val toolWindow = manager.getToolWindow(id)?.takeIf { it.isVisible } ?: return@flatMap emptyList()
            val component = toolWindow.component
            if (SwingUtilities.getWindowAncestor(component) !== frame) return@flatMap emptyList()
            val paneTargets =
                panes(component).mapNotNull { pane ->
                    rectIn(frame, paneHost(pane))?.let { Target(it, null, pane, pane, layer) }
                }
            if (paneTargets.isNotEmpty()) return@flatMap paneTargets
            val containsEnumeratedEditor =
                editors.any { target ->
                    target.editor != null && SwingUtilities.isDescendingFrom(target.editor.component, component)
                }
            if (containsEnumeratedEditor) return@flatMap emptyList()
            val focusable = toolWindow.contentManagerIfCreated?.selectedContent?.preferredFocusableComponent
            rectIn(frame, component)
                ?.let { listOf(Target(it, null, focusable ?: component, component, layer)) }
                .orEmpty()
        }
    }

    private fun panes(root: Component): List<JComponent> =
        visibleComponents(root) { !isPane(it) }
            .filter(::isPane)
            .map { it as JComponent }

    private fun isPane(component: Component): Boolean = component is JTree || component is JTable || component is JList<*>

    private fun paneHost(component: JComponent): Component =
        SwingUtilities.getAncestorOfClass(JScrollPane::class.java, component) ?: component

    private fun visibleComponents(
        root: Component,
        stop: (Component) -> Boolean = { false },
    ): List<Component> {
        val found = mutableListOf<Component>()
        val queue = ArrayDeque<Component>()
        queue += root
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!component.isShowing) continue
            found += component
            if (stop(component)) continue
            if (component is Container) queue += component.components
        }
        return found
    }

    private fun rectIn(
        frame: Window,
        component: Component,
    ): Rectangle? {
        if (!component.isShowing || component.width <= 0 || component.height <= 0) return null
        return SwingUtilities.convertRectangle(component.parent, component.bounds, frame)
    }

    private fun dispatch(event: AWTEvent): Boolean = (event as? KeyEvent)?.let(::dispatchKey) ?: false

    private fun dispatchKey(event: KeyEvent): Boolean =
        when (event.id) {
            KeyEvent.KEY_TYPED -> session?.let { consume(event) } ?: false
            KeyEvent.KEY_PRESSED -> session?.let { dispatchPressed(event) } ?: false
            else -> false
        }

    private fun dispatchPressed(event: KeyEvent): Boolean =
        event
            .takeIf { it.keyCode == KeyEvent.VK_ESCAPE && it.modifiersEx == 0 }
            ?.let { consume(it) { cancel() } }
            ?: consume(event) { keyChar(event)?.let(::handleTyped) }

    private fun keyChar(event: KeyEvent): Char? =
        event
            .takeIf { it.keyCode in KeyEvent.VK_A..KeyEvent.VK_Z }
            ?.takeIf { it.modifiersEx == 0 }
            ?.let { ('a'.code + it.keyCode - KeyEvent.VK_A).toChar() }

    private fun consume(
        event: KeyEvent,
        action: () -> Unit = {},
    ): Boolean {
        event.consume()
        action()
        return true
    }

    private fun pick(
        active: Session,
        char: Char,
    ): Boolean {
        val candidate = active.prefix + char.lowercaseChar()
        val labels = labelsFor(active.targets.size)
        val matches = labels.filterValues { label -> label.startsWith(candidate) }
        if (matches.isEmpty()) return true
        val index = matches.entries.singleOrNull { (_, label) -> label == candidate }?.key
        if (index == null) {
            active.prefix = candidate
            paintLabels()
        } else {
            val target = active.targets[index]
            val swap = active.swap
            val current = active.current
            cancel()
            ApplicationManager.getApplication().invokeLater { perform(swap, target, current) }
        }
        return true
    }

    private fun perform(
        swap: Boolean,
        target: Target,
        current: Target?,
    ) {
        if (target === current) return
        val targetEditor = target.editor
        val currentEditor = current?.editor
        when {
            !swap -> focus(target)
            targetEditor == null || currentEditor == null -> Unit
            else -> swapWith(currentEditor, targetEditor)
        }
    }

    private fun focus(target: Target) {
        val project = target.editor?.project
        if (project != null) {
            IdeFocusManager.getInstance(project).requestFocus(target.focusComponent, true)
        } else {
            target.focusComponent.requestFocusInWindow()
        }
    }

    @Suppress("UnstableApiUsage")
    private fun swapWith(
        editor: Editor,
        target: Editor,
    ) {
        val project = editor.project ?: return
        val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
        val current = fileEditorManager.currentWindow ?: return
        val targetWindow =
            fileEditorManager.windows
                .firstOrNull { SwingUtilities.isDescendingFrom(target.component, it.tabbedPane.component) }
                ?.takeIf { it !== current } ?: return
        val currentFile = current.selectedComposite?.file ?: return
        val targetFile = targetWindow.selectedComposite?.file ?: return
        if (currentFile != targetFile) {
            val options = FileEditorOpenOptions(requestFocus = false)
            fileEditorManager.openFile(currentFile, targetWindow, options)
            fileEditorManager.openFile(targetFile, current, options)
            current.closeFile(currentFile)
            targetWindow.closeFile(targetFile)
        }
        targetWindow.setAsCurrentWindow(true)
    }

    private fun paintLabels() {
        val active = session ?: return
        active.canvases.forEach(::detach)
        active.canvases.clear()
        labelsFor(active.targets.size).forEach { (index, label) ->
            if (label.startsWith(active.prefix)) {
                val target = active.targets[index]
                val layer = target.layer ?: return@forEach
                val rect = targetScreenRect(target, layer) ?: return@forEach
                active.canvases += layerCanvas(layer, WindowBadge(rect, label.removePrefix(active.prefix)))
            }
        }
    }

    private fun targetScreenRect(
        target: Target,
        layer: JLayeredPane,
    ): Rectangle? {
        val frame = SwingUtilities.getWindowAncestor(layer) ?: return null
        return SwingUtilities.convertRectangle(frame, target.rect, layer)
    }

    private fun labelsFor(count: Int): Map<Int, String> = labels(tree((0 until count).toList()))

    private fun tree(candidates: List<Int>): LabelBranch {
        if (candidates.size < LABEL_KEYS.length) {
            return LabelBranch(LABEL_KEYS.toList().zip(candidates.map(::LabelLeaf)))
        }
        var remaining = candidates
        val children = mutableListOf<Pair<Char, LabelNode>>()
        for ((index, size) in subdiv(candidates.size).withIndex()) {
            val taken = remaining.take(size)
            remaining = remaining.drop(size)
            children += LABEL_KEYS[index] to if (size == 1) LabelLeaf(taken.first()) else tree(taken)
        }
        return LabelBranch(children)
    }

    private fun subdiv(count: Int): List<Int> {
        val base = LABEL_KEYS.length
        val depth = kotlin.math.floor(ln(count.toDouble()) / ln(base.toDouble()) + LABEL_EPSILON).toInt() - 1
        var shallowWidth = 1
        repeat(depth) { shallowWidth *= base }
        val deepWidth = base * shallowWidth
        val delta = count - deepWidth
        val deepBuckets = delta / (deepWidth - shallowWidth)
        val shallowBuckets = base - deepBuckets - 1
        return List(shallowBuckets) { shallowWidth } +
            listOf(count - shallowBuckets * shallowWidth - deepBuckets * deepWidth) +
            List(deepBuckets) { deepWidth }
    }

    private fun labels(root: LabelBranch): Map<Int, String> {
        val labels = mutableMapOf<Int, String>()

        fun visit(
            node: LabelNode,
            prefix: String,
        ) {
            when (node) {
                is LabelLeaf -> labels[node.index] = prefix
                is LabelBranch -> node.children.forEach { (key, child) -> visit(child, prefix + key) }
            }
        }
        visit(root, "")
        return labels
    }

    private sealed class LabelNode

    private class LabelLeaf(
        val index: Int,
    ) : LabelNode()

    private class LabelBranch(
        val children: List<Pair<Char, LabelNode>>,
    ) : LabelNode()

    private fun layerCanvas(
        layer: JLayeredPane,
        canvas: JComponent,
    ): JComponent {
        canvas.isOpaque = false
        canvas.bounds = Rectangle(0, 0, layer.width, layer.height)
        layer.setLayer(canvas, JLayeredPane.DRAG_LAYER)
        layer.add(canvas)
        layer.repaint()
        return canvas
    }

    private fun detach(canvas: JComponent) {
        canvas.parent?.let { parent ->
            parent.remove(canvas)
            parent.repaint()
        }
    }

    private class WindowBadge(
        private val rect: Rectangle,
        private val label: String,
    ) : JComponent() {
        override fun contains(
            x: Int,
            y: Int,
        ): Boolean = false

        override fun paintComponent(graphics: Graphics) {
            val g = graphics as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = g.font.deriveFont(Font.BOLD)
            val bounds = g.font.createGlyphVector(g.fontRenderContext, label).visualBounds
            val width = kotlin.math.ceil(bounds.width).toInt() + LABEL_PADDING * 2
            val height = kotlin.math.ceil(bounds.height).toInt() + LABEL_PADDING * 2
            val x = rect.x
            val y = rect.y
            g.color = java.awt.Color(COLOR_RED, COLOR_GREEN, COLOR_BLUE)
            g.fillRect(x, y, width, height)
            g.color = java.awt.Color.WHITE
            g.drawString(label, (x + LABEL_PADDING - bounds.x).toFloat(), (y + LABEL_PADDING - bounds.y).toFloat())
        }
    }
}
