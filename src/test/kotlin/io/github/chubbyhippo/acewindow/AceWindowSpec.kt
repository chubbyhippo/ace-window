package io.github.chubbyhippo.acewindow

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle
import javax.swing.JPanel

class AceWindowSpec : BasePlatformTestCase() {
    private fun target(
        x: Int,
        y: Int,
    ): AceWindow.Target {
        val panel = JPanel()
        return AceWindow.Target(Rectangle(x, y, 10, 10), null, panel, panel, null)
    }

    private fun <T> privateCall(
        name: String,
        vararg arguments: Any,
    ): T {
        val method =
            AceWindow::class.java.getDeclaredMethod(
                name,
                *arguments
                    .map { argument ->
                        when (argument.javaClass) {
                            java.lang.Character::class.java -> Char::class.javaPrimitiveType
                            java.lang.Integer::class.java -> Int::class.javaPrimitiveType
                            else -> argument.javaClass
                        }
                    }.toTypedArray(),
            )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AceWindow, *arguments) as T
    }

    fun `test given window rectangles then ace-window orders them left to right then top down`() {
        val r = target(40, 0)
        val l1 = target(0, 0)
        val l2 = target(0, 12)

        val ordered = AceWindow.ordered(listOf(r, l2, l1))

        assertEquals(listOf(l1, l2, r), ordered)
    }

    fun `test given the current window then ace-window other-target picks the first non-current`() {
        val a = target(0, 0)
        val b = target(40, 0)
        val targets = listOf(a, b)

        assertSame(b, AceWindow.otherTarget(targets, a))
        assertSame(a, AceWindow.otherTarget(targets, b))
    }

    fun `test given a single window then begin plans none and starts no session`() {
        AceWindow.begin(swap = false, targets = listOf(target(0, 0)), current = null)

        assertFalse(AceWindow.handleTyped('a'))
    }

    fun `test given two windows then begin plans other and performs without a label session`() {
        val a = target(0, 0)
        val b = target(40, 0)

        AceWindow.begin(swap = false, targets = listOf(a, b), current = a)

        assertFalse(AceWindow.handleTyped('a'))
    }

    fun `test given three or more windows then begin plans labels and starts a session`() {
        val targets = listOf(target(0, 0), target(40, 0), target(80, 0))

        AceWindow.begin(swap = false, targets = targets, current = null)

        assertTrue(AceWindow.handleTyped('a'))
        AceWindow.cancel()
    }

    fun `test given an active session then cancel clears it`() {
        val targets = listOf(target(0, 0), target(40, 0), target(80, 0))
        AceWindow.begin(swap = false, targets = targets, current = null)

        AceWindow.cancel()

        assertFalse(AceWindow.handleTyped('a'))
    }

    fun `test given target positions then labels follow ace subdivision`() {
        val labels = privateCall<Map<Int, String>>("labelsFor", 3)

        assertEquals(setOf("a", "s", "d"), labels.values.toSet())
    }

    fun `test given no active window session then typed characters are delegated`() {
        AceWindow.cancel()

        assertFalse(AceWindow.handleTyped('a'))
    }

    fun `test given no editor then ace window action is a no op`() {
        AceWindowAction().actionPerformed(
            AnActionEvent.createFromDataContext("AceWindowSpec", null, DataContext.EMPTY_CONTEXT),
        )

        assertNull(ApplicationManager.getApplication().getServiceIfCreated(AceWindowLifecycle::class.java))
    }

    fun `test given no editor then ace swap window action is a no op`() {
        AceSwapWindowAction().actionPerformed(
            AnActionEvent.createFromDataContext("AceWindowSpec", null, DataContext.EMPTY_CONTEXT),
        )

        assertNull(ApplicationManager.getApplication().getServiceIfCreated(AceWindowLifecycle::class.java))
    }

    fun `test given no active window session then typed handler delegates to its original handler`() {
        var delegated = false
        val handler =
            AceWindowTypedHandler(
                object : TypedActionHandler {
                    override fun execute(
                        editor: Editor,
                        charTyped: Char,
                        dataContext: DataContext,
                    ) {
                        delegated = true
                    }
                },
            )

        myFixture.configureByText("typed-handler.txt", "text")
        AceWindow.cancel()
        handler.execute(myFixture.editor, 'a', DataContext.EMPTY_CONTEXT)

        assertTrue(delegated)
    }
}
