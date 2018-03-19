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

package org.intellij.clojure.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import org.intellij.clojure.ClojureConstants
import java.io.File

/**
 * @author gregsh
 */
object Repo {
  val path = File(File(com.intellij.util.SystemProperties.getUserHome(), ".m2"), "repository")
}


interface Tool {
  fun getDeps(): GeneralCommandLine
  fun getRepl(): GeneralCommandLine

  companion object {
    fun choose(file: File) = choose(file.name)
    fun choose(fileName: String) = when (fileName) {
      Lein.projectFile -> Lein
      Boot.projectFile -> Boot
      else -> null
    }

    fun find(dir: File) = when {
      File(dir, Lein.projectFile).exists() -> Lein
      File(dir, Boot.projectFile).exists() -> Boot
      else -> null
    }
  }
}

object Lein : Tool {
  val projectFile = ClojureConstants.LEIN_PROJECT_CLJ
  val path = (EnvironmentUtil.getValue("PATH") ?: "").split(File.pathSeparator).mapNotNull {
    val path = "$it${File.separator}lein${if (SystemInfo.isWindows) ".bat" else ""}"
    if (File(path).exists()) path else null
  }.firstOrNull() ?: "lein"

  override fun getDeps() = GeneralCommandLine(path,
      "deps", ":tree")

  override fun getRepl() = GeneralCommandLine(path,
      "update-in", ":dependencies", "conj", "[org.clojure/tools.nrepl \"RELEASE\"]", "--",
      "update-in", ":plugins", "conj", "[cider/cider-nrepl \"RELEASE\"]", "--",
      "update-in", ":nrepl-middleware", "conj ", "[cider-nrepl.plugin/middleware \"RELEASE\"]", "--",
      "repl", ":headless")

}

object Boot : Tool {
  val projectFile = ClojureConstants.BOOT_BUILD_BOOT
  val path = (EnvironmentUtil.getValue("PATH") ?: "").split(File.pathSeparator).mapNotNull {
    val path = "$it${File.separator}boot${if (SystemInfo.isWindows) ".bat" else ""}"
    if (File(path).exists()) path else null
  }.firstOrNull() ?: "boot"

  override fun getDeps() = GeneralCommandLine(path,
      "--no-colors", "show", "-d")

  override fun getRepl() = GeneralCommandLine(path,
      "--no-colors",
      "-d", "org.clojure/tools.nrepl",
      "-d", "cider/cider-nrepl",
      "repl", "-m", "cider.nrepl/cider-middleware", "-s", "wait")
}
