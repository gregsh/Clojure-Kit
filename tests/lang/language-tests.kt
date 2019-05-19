package org.intellij.clojure.lang

import com.intellij.lang.LanguageASTFactory
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.testFramework.LexerTestCase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.ParsingTestCase
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import com.intellij.util.indexing.FileBasedIndex
import org.intellij.clojure.inspections.ClojureResolveInspection
import org.intellij.clojure.parser.*
import org.intellij.clojure.util.elementType
import org.intellij.clojure.util.jbIt
import java.io.File
import java.nio.file.Path

/**
 * @author gregsh
 */
abstract class ClojureLexerTestCase(val lexer: Lexer) : LexerTestCase() {
  override fun getDirPath() = "$TEST_DATA_PATH/lexer"
  override fun createLexer() = lexer
  override fun doTest(text: String?) = assertSameLinesWithFile(
      "$dirPath/${getTestName(false)}.txt", printTokens(text, 0, createLexer()))
}

class ClojureLexerTest : ClojureLexerTestCase(ClojureLexer(ClojureLanguage)) {
  fun testLiterals() = doTest("""
    |"a" "\"" "\\\\\"" "new
    |line"
    |42 -2 0x12 42N 0x12N 23M 023N 023M
    |2r101010, 8r52, 36r16,
    |-2/3
    |+2.2 23.4 34. 34.e2 2e3 2e+3 2e-4 2.0e3 3.3e-3M 99.99M
    |22/7
    |\c, \newline, \space, \tab, \formfeed, \backspace, and \return
    |\u89AF \u03A9 \o677
    |nil true false
    |
    |2.2N +0x23M 023abc \uTRYY \uFFFFuuu \o1234
    |"unclosed
  """.trimMargin())

  fun testSymbolsAndKeywords() = doTest("""
    |a :a ::a :a: :a:a ::a:a :a:a:
    |$ % & # %2 a& -a +a *a* a#
    |a.b :a.b a/b :a/b ::a/b a.b/c :a.b/c ::a.b/c
    |'a'b '. '/ a''' / ('/) a// /a a/ a/b/c
    |:fred :person/name ::rect
    |:1 :- :* :| :& .1 a|b
    |. .a a. a.b a/b. a.b/c.
    |.- -a
    |-. a- a-b .a/c .a.b/c
    |.. ..a a.. ..a/b a..b
  """.trimMargin())

  fun testDispatchAndQuote() = doTest("""
    |#{} #"\s*\d+" #'x #( ) #_
    |'quote `qualify ~unquote ~@unquote-splicing
    |#?(:clj     Double/NaN :cljs    js/NaN :default nil)
    |#?@(:clj [3 4] :cljs [5 6])
  """.trimMargin())
}

class ClojureHighlightingLexerTest : ClojureLexerTestCase(ClojureHighlightingLexer(ClojureLanguage)) {
  fun testHighlightForm() = doTest("(abc :kwd '(quoted xyz) (some.ns/fn :some.ns/kwd ::user-kwd (.-x (.y z))) #_(comment )")
  fun testHighlightSingleSharp() = doTest("#")
}

class ClojureParsingTest : ClojureParsingTestCase(ClojureParserDefinition()) {
  fun testFirstAndSimple() = doCodeTest(";line\n(+ 1 2 3)\n(clojure.core/str \"a\" '.. '.-a val 123 :key)")
  fun testSimpleRecover() = doCodeTest("//// 42 : x (abc [: x : y z] 2/3) )1 sym)")
  fun testSimpleRecover2() = doCodeTest("(a))(b)")
  fun testSimpleFixes() = doCodeTest(".1 x .-;comment\n1;unclosed eof\n\"x")
  fun testMapPrefix() = doCodeTest("#:asd{:a 1 :b #::{:c 2}  #::as {} :s1 #:: {} :s2 #:a {} :s3 #: a{} :s4 #:: a{} ")
  fun testCommentedForms() = doCodeTest("(def ^int #_cc n 0) {#_0 #_1 :a '#_'(xxx) a :b 'b #_2 #_3} # #_asd dbg 10")
  fun testInterOp() = doCodeTest("(a.) (a/b.)")

  fun testParseClojureLang() = walkAndParse(::walkClojureLang)
//  fun testParseWellKnownLibs() = walkAndParse(::walkKnownLibs)
}

class ClojureScriptParsingTest : ClojureParsingTestCase(ClojureScriptParserDefinition()) {
  fun testParseClojureScript() = walkAndParse(::walkClojureScriptLang)
}

abstract class ClojureParsingTestCase(o: ClojureParserDefinitionBase) : ParsingTestCase(
    "parser", if (o.fileNodeType == ClojureTokens.CLJ_FILE_TYPE) "clj" else "cljs", o) {
  override fun getTestDataPath() = TEST_DATA_PATH
  override fun setUp() {
    super.setUp()
    addExplicitExtension(LanguageASTFactory.INSTANCE, ClojureLanguage, ClojureASTFactory())
    addExplicitExtension(LanguageBraceMatching.INSTANCE, ClojureLanguage, ClojureBraceMatcher())
  }

  fun walkAndParse(walker: ((Path, String) -> Unit) -> Unit) {
    val stat = object {
      var duration = System.currentTimeMillis()
      var files: Int = 0
      var chars: Long = 0
      var nodes: Long = 0
      var errors: Long = 0
    }
    walker { path: Path, text: String ->
      stat.files++
      stat.chars += text.length
      val psiFile = createFile(path.toString(), text)
      for (o in SyntaxTraverser.psiTraverser(psiFile)) {
        stat.nodes++
        val elementType = o.elementType
        val message = when {
          elementType == TokenType.BAD_CHARACTER -> "bad char '${o.text}'"
          o is PsiErrorElement -> "error: ${o.errorDescription}"
          else -> null
        }
        if (message != null) {
          stat.errors++
          println("${psiFile.name} [${o.textRange.startOffset}]: $message")
        }
      }
    }
    stat.duration = System.currentTimeMillis() - stat.duration
    println(stat.run { "${getTestName(false)}\nTotal: $errors errors and ${StringUtil.formatFileSize(nodes)} nodes" +
        " in $files files (${StringUtil.formatFileSize(chars)} chars)" })
    println("Processed in ${StringUtil.formatDuration(stat.duration)}\n")
    ParsingTestCase.assertEquals("${stat.errors} errors", 0L, stat.errors)
    ParsingTestCase.assertTrue("${stat.nodes} nodes", stat.nodes > 1000)
  }
}

class ClojureHighlightingTest : LightPlatformCodeInsightFixtureTestCase() {
  override fun getTestDataPath() = "$TEST_DATA_PATH/highlighting"
  override fun setUp() {
    super.setUp()
    FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, myFixture.project, null)
    myFixture.enableInspections(ClojureResolveInspection::class.java)
  }

  fun testClojureFixes() = doTest("clj")

  fun testClojureLang() = walkAndHighlight(::walkClojureLang)
  fun testClojureScript() = walkAndHighlight(::walkClojureScriptLang)

  private fun doTest(extension: String) {
    myFixture.configureByFile(getTestName(false) + ".$extension")
    myFixture.checkHighlighting()
  }

//  fun testClojureFile() = "/clojure/core.clj".let { file ->
//    walkAndHighlight { block -> walkFs(CLJ_LIB_FS, file, block) } }

  private fun walkAndHighlight(walker: ((Path, String) -> Unit) -> Unit) {
    val ignoreInCljs = arrayOf("goog", "gobj", "gstring", "garray", "gdom", "gjson")
    val stat = object {
      var duration = System.currentTimeMillis()
      var files: Int = 0
      var chars: Long = 0
      var warnings: Long = 0
      var dynamic: Long = 0
    }
    val report = StringBuilder()
    walker { path: Path, text: String ->
      stat.files++
      stat.chars += text.length
      val lightVirtualFile = LightVirtualFile(path.toString(), ClojureLanguage, text)
      val isCljs = lightVirtualFile.name.run { endsWith(".cljc") || endsWith(".cljs") }
      myFixture.configureFromExistingVirtualFile(lightVirtualFile)
      val infos = myFixture.doHighlighting().jbIt()
      val errors = infos.filter { it.severity == HighlightSeverity.ERROR }.size()
      val warnings = infos.filter { it.severity == HighlightSeverity.WARNING }.size()
      val dynamic = infos.filter { it.forcedTextAttributesKey == ClojureColors.DYNAMIC }.size()
      stat.warnings += warnings
      stat.dynamic += dynamic
      "${path.toString().run { this + StringUtil.repeat(" ", Math.max(0, 40 - length)) }} $errors errors, $warnings warnings, $dynamic dynamic".run {
        report.append(this).append("\n")
        println(this)
      }
      for (info in infos.filter { it.severity == HighlightSeverity.ERROR || it.severity == HighlightSeverity.WARNING }) {
        report.append("      ${info.startOffset}: ${info.description}").append("\n")
      }
      for (info in infos.filter { it.forcedTextAttributesKey == ClojureColors.DYNAMIC }) {
        val sym = text.subSequence(info.startOffset, info.endOffset)
        if (isCljs && (sym == "js" || ignoreInCljs.find { sym.startsWith(it) } != null)) continue
        report.append("      ${info.startOffset}: dynamic '$sym'").append("\n")
      }
    }
    stat.duration = System.currentTimeMillis() - stat.duration
    stat.run { "Total: $warnings warnings, $dynamic dynamic in $files files (${StringUtil.formatFileSize(chars)})".run {
      report.append(this).append("\n")
      println("${getTestName(false)}\n${this}")
    } }
    println("Processed in ${StringUtil.formatDuration(stat.duration)}\n")
    UsefulTestCase.assertSameLinesWithFile("$testDataPath/${getTestName(false)}.txt".replace("/", File.separator), report.toString())
  }
}