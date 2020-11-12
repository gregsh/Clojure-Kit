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

import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.io.InputStreamReader

/**
 * @author gregsh
 */
val TEST_DATA_PATH = FileUtil.toSystemIndependentName(File("testData").absolutePath)
val CLOJURE_LIB = "Clojure"
val CLOJURE_SCRIPT_LIB = "ClojureScript"

fun getLibrarySources(libName: String) = getLibraryUrls(libName)
    .mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) }
    .flatMap { VfsUtil.collectChildrenRecursively(it) }
    .filter { FileUtilRt.getExtension(it.name).startsWith("clj") }
    .sortedBy { it.path }

fun getLibraryUrls(libName: String) = JDOMUtil.load(File(".idea/libraries/$libName.xml"))
    .getChild("library").getChild("CLASSES").getChildren("root")
    .mapNotNull { it.getAttributeValue("url") }
    .map { StringUtil.trimEnd(it, "!/") }
    .map { it.substring(it.lastIndexOf("/") + 1) }
    .mapNotNull {
      val cp = System.getProperty("java.class.path")
      val idx = cp.indexOf(it)
      if (idx == -1) return@mapNotNull null
      val path = cp.substring(cp.substring(0, idx).lastIndexOf(File.pathSeparator) + 1, idx + it.length)
      "jar://$path!/"
    }

fun getFileText(vFile: VirtualFile) : Pair<String, String> {
  val path = vFile.path.substring(vFile.path.indexOf("!/") + 2)
  val text = StringUtilRt.convertLineSeparators(FileUtil.loadTextAndClose(
      InputStreamReader(vFile.inputStream, CharsetToolkit.UTF8)).trim { it <= ' ' })
  return Pair(path, text)
}