package org.intellij.clojure.lang

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import org.intellij.clojure.actions.*

/**
 * @author gregsh
 */
class StructuralEditingTest : LightPlatformCodeInsightFixtureTestCase() {

  // basic tests
  fun testSlurpFwd1() = doTest("(a b (c d |e f) g h)", "(a b (c d |e f g) h)")
  fun testSlurpBwd1() = doTest("(a b (c d |e f) g h)", "(a (b c d |e f) g h)")
  fun testBarfFwd1() = doTest("(a b (c d |e f) g h)", "(a b (c d |e) f g h)")
  fun testBarfBwd1() = doTest("(a b (c d |e f) g h)", "(a b c (d |e f) g h)")
  fun testSplice1() = doTest("(a b (c d |e f) g h)", "(a b c d |e f g h)")
  fun testRise1() = doTest("(a b (c d |e f) g h)", "(a b (c d (|e) f) g h)")
  fun testKill1() = doTest("(a b (c d |e f) g h)", "(a b |g h)")

  fun testKillNL1() = doTest("(a b\n (c d |e f)\n g h)", "(a b\n| g h)")

  fun testRiseMulti1() = doTest("((|a) (|b))", "((|a) (|b))") // no-op
  fun testKillMulti1() = doTest("((|a) (|b))", "((|a) (|b))") // no-op

  fun testSlurpFwdMeta1() = doTest("(a b (c d |e f) ^{ } g h)", "(a b (c d |e f ^{ } g) h)")
  fun testSlurpBwdMeta1() = doTest("(a ^{ } b (c d |e f) g h)", "(a (^{ } b c d |e f) g h)")
  fun testBarfFwdMeta1() = doTest("(a b (c d |e ^{ } f) g h)", "(a b (c d |e) ^{ } f g h)")
  fun testBarfBwdMeta1() = doTest("(a b (^{ } c d |e f) g h)", "(a b ^{ } c (d |e f) g h)")
  fun testSpliceMeta1() = doTest("(a b ^{ } (c d |e f) ^{ } g h)", "(a b c d |e f ^{ } g h)")
  fun testRiseMeta1() = doTest("(a b (c d ^{ } |e f) g h)", "(a b (c d (^{ } |e) f) g h)")
  fun testKillMeta1() = doTest("(a b ^{ } (c d |^{ } e f) g h)", "(a b |g h)")

  fun testBackspace1() = doTest("(a b (c d e f)| g h)", "(a b |g h)")
  fun testBackspace2() = doTest("(a b (|c d e f) g h)", "(a b |c d e f g h)")
  fun testBackspace3() = doTest("(a b (<selection>c</selection> d e f) g h)", "(a b ( d e f) g h)")
  fun testBackspace4() = doTest("(a b (c d e f)|) g h)", "(a b (c d e f|) g h)")
  fun testBackspace5() = doTest("(a b (c d<selection> e f) </selection>g h)", "(a b (c dg h)")
  fun testDelete1() = doTest("(a b |(c d e f) g h)", "(a b |g h)")
  fun testDelete2() = doTest("(a b (c d e f|) g h)", "(a b c d e f| g h)")
  fun testDelete3() = doTest("(a b (c d e <selection>f</selection>) g h)", "(a b (c d e ) g h)")
  fun testDelete4() = doTest("(a b (|(c d e f) g h)", "(a b (|c d e f) g h)")

  fun testDeleteMeta1() = doTest("(a b |^{:x 1} (c d e f) g h)", "(a b (c d e f) g h)")
  fun testDeleteMeta2() = doTest("(a b ^{:x 1|} (c d e f) g h)", "(a b :x 1 (c d e f) g h)")
  fun testDeleteMeta3() = doTest("(a b ^{:x 1} |(c d e f) g h)", "(a b g h)")
  fun testDeleteMeta4() = doTest("(a b ^{:x 1} (c d e f|) g h)", "(a b c d e f g h)")
  fun testBackspaceMeta1() = doTest("(a b ^{:x 1} (c d e f)| g h)", "(a b |g h)")
  fun testBackspaceMeta2() = doTest("(a b ^{:x 1} (|c d e f) g h)", "(a b |c d e f g h)")
  fun testBackspaceMeta3() = doTest("(a b ^{:x 1}| (c d e f) g h)", "(a b |(c d e f) g h)")
  fun testBackspaceMeta4() = doTest("(a b ^{|:x 1} (c d e f) g h)", "(a b |:x 1 (c d e f) g h)")

  fun testTypeParen1() = doType("|namespace/name", "(", "(|namespace/name)")
  fun testTypeParen2() = doType("|namespace/name)", "(", "(|namespace/name)")
  fun testTypeParen3() = doType("|:namespace/name", "(", "(|:namespace/name)")
  fun testTypeParen4() = doType("|:namespace/name", "()", "(() :namespace/name)")
  fun testTypeParen5() = doType("ab|cd", ")))", "ab () (|) cd")
  fun testTypeParen6() = doType("|abc", "#{", "#{|abc}")
  fun testTypeHParen1() = doType("|abc", "#:foo {", "#:foo {|abc}")
  fun testTypeHParen2() = doType("|abc", "#::{", "#::{|abc}")
  fun testTypeHParen3() = doType("|^{:x ()} (foo) bar", "#::{", "#::{^{:x ()} (foo)} bar")
  fun testTypeHParen4() = doType("| ^{:x ()} (foo) bar", "#::{", "#::{|} ^{:x ()} (foo) bar")

  fun testTypeParenInEmpty1() = doType("", "(", "()")
  fun testTypeParenInEmpty2() = doType("", ")", "()")
  fun testTypeParenInString1() = doType("\"ab |\"", "(", "\"ab (|\"")
  fun testTypeParenInString2() = doType("\"ab |\"", ")", "\"ab )|\"")
  fun testTypeParenInComment1() = doType("; ab |", "(", "; ab (|")
  fun testTypeParenInComment2() = doType("; ab |", ")", "; ab )|")

  fun testAllActionsEmpty() = allActions.forEach { it.run("", "") }
  fun testAllActionsSym01() = allActions.forEach { it.run("|a", null) }
  fun testAllActionsSym02() = allActions.forEach { it.run("a|", null) }
  fun testAllActionsPar03() = allActions.forEach { it.run("|(", null) }
  fun testAllActionsPar04() = allActions.forEach { it.run("(|", null) }
  fun testAllActionsPar05() = allActions.forEach { it.run("|)", null) }
  fun testAllActionsPar06() = allActions.forEach { it.run(")|", null) }
  fun testAllActionsPar07() = allActions.forEach { it.run("(|)", null) }
  fun testAllActionsPar08() = allActions.forEach { it.run("|()", null) }
  fun testAllActionsPar09() = allActions.forEach { it.run("()|", null) }
  fun testAllActionsPar10() = allActions.forEach { it.run("(|a)", null) }

  // basic fixes
  fun testSlurpFwdFix1() = doTest("(a (|) b c)", "(a (|b) c)")
  fun testSlurpFwdFix2() = doTest("(a (|b.) c)", "(a (|b. c))")

  fun testBarfFwdFix1() = doTest("(a (|b) c)", "(a (|) b c)")
  fun testBarfFwdFix2() = doTest("(a (|b. c))", "(a (|b.) c)")
  fun testBarfFwdFix3() = doTest("(a (b|) c)", "(a (|) b c)")
  fun testBarfFwdFix4() = doTest("(a (b|. c))", "(a (b|.) c)")
  fun testBarfBwdFix1() = doTest("(|x)", "x (|)")
  fun testBarfBwdFix2() = doTest("(x|)", "x (|)")

  fun testRiseFix1() = doTest("a :|ns/kwd b", "a (:|ns/kwd) b")
  fun testRiseFix2() = doTest("a <selection>:ns/kwd b</selection>", "a (:ns/kwd b)")
  fun testRiseFix3() = doTest("a :<selection>ns/kwd b</selection>", "a (:ns/kwd b)")


  fun doTest(before: String, after: String) = testAction.run(before, after)
  fun doType(before: String, what: String, after: String) = doTest(before, after) { myFixture.type(what) }

  private val testAction: AnAction
    get() = getTestName(false).let { name ->
      allActions.find { name.contains(StringUtil.trimEnd(it.javaClass.simpleName!!, "Action")) }!!
    }

  private fun AnAction.run(before: String, after: String?) = doTest(before, after) { myFixture.testAction(this@run) }

  private fun doTest(before: String, after: String?, action: () -> Unit) = myFixture.run {
    configureByText(ClojureFileType, before.replace("|", "<caret>"))
    action()
    if (after != null) {
      checkResult(after.replace("|", "<caret>"))
    }
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