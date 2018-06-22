/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure.lang

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.CharsetToolkit
import org.intellij.clojure.ClojureConstants
import java.io.File
import java.io.InputStreamReader
import java.nio.file.*

/**
 * @author gregsh
 */
val TEST_DATA_PATH = FileUtil.toSystemIndependentName(File("testData").absolutePath)

val CLJ_LIB_FS = resourceFs(ClojureConstants.CLJ_CORE_PATH)
val CLJS_LIB_FS = resourceFs(ClojureConstants.CLJS_CORE_PATH)
val KNOWN_LIB_FS = resourceFsn(ClojureConstants.LEIN_CONFIG)

fun walkClojureLang(block: (Path, String) -> Unit) = walkFs(CLJ_LIB_FS, block)
fun walkClojureScriptLang(block: (Path, String) -> Unit) = walkFs(CLJS_LIB_FS, block)
fun walkKnownLibs(block: (Path, String) -> Unit) = KNOWN_LIB_FS.forEach { walkFs(it, block) }

fun resourceFs(resource: String) = ClojureLanguage.javaClass.getResource(resource)!!
    .let { FileSystems.newFileSystem(it.toURI(), emptyMap<String, Any>()) }!!

fun resourceFsn(resource: String) = ClojureLanguage.javaClass.classLoader.getResources(resource)
    .asSequence().sortedBy { it.file }.map { FileSystems.newFileSystem(it.toURI(), emptyMap<String, Any>()) }.toList()

fun walkFs(fs: FileSystem, block: (Path, String) -> Unit) = walkFs(fs, "/", block)
fun walkFs(fs: FileSystem, root: String, block: (Path, String) -> Unit) = Files.walk(fs.getPath(root))
    .filter { FileUtilRt.getExtension(it.fileName?.toString() ?: "").startsWith("clj") }
    .sorted()
    .forEach {
      val stream = fs.provider().newInputStream(it, StandardOpenOption.READ)
      val textRaw = FileUtil.loadTextAndClose(InputStreamReader(stream, CharsetToolkit.UTF8)).trim { it <= ' ' }
      val text = StringUtilRt.convertLineSeparators(textRaw)
      block(it, text)
    }
