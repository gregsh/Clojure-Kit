package org.intellij.clojure.lang

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import org.intellij.clojure.actions.*

/**
 * @author gregsh
 */
class StructuralEditingTest : LightPlatformCodeInsightFixtureTestCase() {

  // basic tests
  fun testSlurpFwd1() = doTest("(a b (c d <caret>e f) g h)", "(a b (c d e f g) h)")
  fun testSlurpBwd1() = doTest("(a b (c d <caret>e f) g h)", "(a (b c d e f) g h)")
  fun testBarfFwd1() = doTest("(a b (c d <caret>e f) g h)", "(a b (c d e) f g h)")
  fun testBarfBwd1() = doTest("(a b (c d <caret>e f) g h)", "(a b c (d e f) g h)")
  fun testSplice1() = doTest("(a b (c d <caret>e f) g h)", "(a b c d e f g h)")
  fun testRise1() = doTest("(a b (c d <caret>e f) g h)", "(a b (c d (e) f) g h)")
  fun testKill1() = doTest("(a b (c d <caret>e f) g h)", "(a b g h)")

  fun testKillNL1() = doTest("(a b\n (c d <caret>e f)\n g h)", "(a b\n g h)")

  fun testRiseMulti1() = doTest("((<caret>a) (<caret>b))", "(((a)) ((b)))")
  fun testKillMulti1() = doTest("((<caret>a) (<caret>b))", "()")

  fun testSlurpFwdMeta1() = doTest("(a b (c d <caret>e f) ^{ } g h)", "(a b (c d e f ^{ } g) h)")
  fun testSlurpBwdMeta1() = doTest("(a ^{ } b (c d <caret>e f) g h)", "(a (^{ } b c d e f) g h)")
  fun testBarfFwdMeta1() = doTest("(a b (c d <caret>e ^{ } f) g h)", "(a b (c d e) ^{ } f g h)")
  fun testBarfBwdMeta1() = doTest("(a b (^{ } c d <caret>e f) g h)", "(a b ^{ } c (d e f) g h)")
  fun testSpliceMeta1() = doTest("(a b (^{ } c d <caret>e ^{ } f) g h)", "(a b ^{ } c d e ^{ } f g h)")
  fun testRiseMeta1() = doTest("(a b (c d ^{ } <caret>e f) g h)", "(a b (c d (^{ } e) f) g h)")
  fun testKillMeta1() = doTest("(a b ^{ } (c d <caret>^{ } e f) g h)", "(a b g h)")

  fun testBackspace1() = doTest("(a b (c d e f)<caret> g h)", "(a b g h)")
  fun testBackspace2() = doTest("(a b (<caret>c d e f) g h)", "(a b c d e f g h)")
  fun testBackspace3() = doTest("(a b (<selection>c</selection> d e f) g h)", "(a b ( d e f) g h)")
  fun testBackspace4() = doTest("(a b (c d e f)<caret>) g h)", "(a b (c d e f) g h)")
  fun testDelete1() = doTest("(a b <caret>(c d e f) g h)", "(a b g h)")
  fun testDelete2() = doTest("(a b (c d e f<caret>) g h)", "(a b c d e f g h)")
  fun testDelete3() = doTest("(a b (c d e <selection>f</selection>) g h)", "(a b (c d e ) g h)")
  fun testDelete4() = doTest("(a b (<caret>(c d e f) g h)", "(a b (c d e f) g h)")

  fun testDeleteMeta1() = doTest("(a b <caret>^{:x 1} (c d e f) g h)", "(a b (c d e f) g h)")
  fun testDeleteMeta2() = doTest("(a b ^{:x 1<caret>} (c d e f) g h)", "(a b :x 1 (c d e f) g h)")
  fun testDeleteMeta3() = doTest("(a b ^{:x 1} <caret>(c d e f) g h)", "(a b g h)")
  fun testDeleteMeta4() = doTest("(a b ^{:x 1} (c d e f<caret>) g h)", "(a b c d e f g h)")
  fun testBackspaceMeta1() = doTest("(a b ^{:x 1} (c d e f)<caret> g h)", "(a b g h)")
  fun testBackspaceMeta2() = doTest("(a b ^{:x 1} (<caret>c d e f) g h)", "(a b c d e f g h)")
  fun testBackspaceMeta3() = doTest("(a b ^{:x 1}<caret> (c d e f) g h)", "(a b (c d e f) g h)")
  fun testBackspaceMeta4() = doTest("(a b ^{<caret>:x 1} (c d e f) g h)", "(a b :x 1 (c d e f) g h)")

  fun testAllActionsEmpty() = allActions.forEach { it.run("", "") }
  fun testAllActionsSym01() = allActions.forEach { it.run("<caret>a", null) }
  fun testAllActionsSym02() = allActions.forEach { it.run("a<caret>", null) }
  fun testAllActionsPar03() = allActions.forEach { it.run("<caret>(", null) }
  fun testAllActionsPar04() = allActions.forEach { it.run("(<caret>", null) }
  fun testAllActionsPar05() = allActions.forEach { it.run("<caret>)", null) }
  fun testAllActionsPar06() = allActions.forEach { it.run(")<caret>", null) }
  fun testAllActionsPar07() = allActions.forEach { it.run("(<caret>)", null) }
  fun testAllActionsPar08() = allActions.forEach { it.run("<caret>()", null) }
  fun testAllActionsPar09() = allActions.forEach { it.run("()<caret>", null) }
  fun testAllActionsPar10() = allActions.forEach { it.run("(<caret>a)", null) }

  // basic fixes
  fun testSlurpFwdFix1() = doTest("(a (<caret>) b c)", "(a (b) c)")
  fun testSlurpFwdFix2() = doTest("(a (<caret>b.) c)", "(a (b. c))")

  fun testBarfFwdFix1() = doTest("(a (<caret>b) c)", "(a () b c)")
  fun testBarfFwdFix2() = doTest("(a (<caret>b. c))", "(a (b.) c)")
  fun testBarfBwdFix1() = doTest("(<caret>x)", "x ()")

  fun testRiseFix1() = doTest("a :<caret>ns/kwd b", "a (:ns/kwd) b")
  fun testRiseFix2() = doTest("a <selection>:ns/kwd b</selection>", "a (:ns/kwd b)")
  fun testRiseFix3() = doTest("a :<selection>ns/kwd b</selection>", "a (:ns/kwd b)")


  fun doTest(before: String, after: String) = testAction.run(before, after)

  private val testAction: AnAction
    get() = getTestName(false).let { name ->
      allActions.find { name.contains(StringUtil.trimEnd(it::class.simpleName!!, "Action")) }!!
    }

  private fun AnAction.run(before: String, after: String?) {
    myFixture.configureByText(ClojureFileType, before)
    myFixture.testAction(this)
    if (after != null) UsefulTestCase.assertSameLines(after, myFixture.editor.document.text)
  }

  private val allActions: List<AnAction>
    get() {
      val actionMan = ActionManager.getInstance()
      return listOf<AnAction>(
          SlurpFwdAction(), SlurpBwdAction(),
          BarfFwdAction(), BarfBwdAction(),
          SpliceAction(), RiseAction(),
          KillAction(),
          actionMan.getAction(IdeActions.ACTION_EDITOR_BACKSPACE),
          actionMan.getAction(IdeActions.ACTION_EDITOR_DELETE))
    }
}