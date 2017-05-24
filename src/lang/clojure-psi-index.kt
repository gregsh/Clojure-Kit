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

package org.intellij.clojure.psi.impl

import com.intellij.util.indexing.*
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.psi.CFile
import org.intellij.clojure.psi.CKeyword
import org.intellij.clojure.util.cljTraverser
import org.intellij.clojure.util.filter
import org.intellij.clojure.util.qualifiedName
import java.io.DataInput
import java.io.DataOutput

/**
 * @author gregsh
 */
val VERSION = 202

val NS_INDEX = ID.create<String, Unit>("clojure.ns")
val DEF_INDEX = ID.create<String, Unit>("clojure.def")
val DEF_FQN_INDEX = ID.create<String, Unit>("clojure.def.fqn")
val KEYWORD_INDEX = ID.create<String, Unit>("clojure.keyword")
val KEYWORD_FQN_INDEX = ID.create<String, Unit>("clojure.keyword.fqn")

class ClojureNSIndex : ClojureUnitIndex() {
  override fun getName(): ID<String, Unit> = NS_INDEX
  override fun index(file: CFile): MutableMap<String, Unit> {
    return mutableMapOf(file.namespace to Unit)
  }
}

class ClojureDefIndex : ClojureUnitIndex() {
  override fun getName(): ID<String, Unit> = DEF_INDEX
  override fun index(file: CFile): MutableMap<String, Unit> {
    return file.defs().map { it.def!!.name }.toMap { Unit }
  }
}

class ClojureDefFqnIndex : ClojureUnitIndex() {
  override fun getName(): ID<String, Unit> = DEF_FQN_INDEX
  override fun index(file: CFile): MutableMap<String, Unit> {
    return file.defs().map {it.def!!.qualifiedName }.toMap { Unit }
  }
}

class ClojureKeywordIndex : ClojureUnitIndex() {
  override fun getName(): ID<String, Unit> = KEYWORD_INDEX
  override fun index(file: CFile): MutableMap<String, Unit> {
    return file.cljTraverser().traverse()
        .filter(CKeyword::class).map { it.name }.toMap { Unit }
  }
}

class ClojureKeywordFqnIndex : ClojureUnitIndex() {
  override fun getName(): ID<String, Unit> = KEYWORD_FQN_INDEX
  override fun index(file: CFile): MutableMap<String, Unit> {
    return file.cljTraverser().traverse()
        .filter(CKeyword::class).map { it.qualifiedName }.toMap { Unit }
  }
}

abstract class ClojureUnitIndex : ClojureIndexBase<Unit>() {
  override fun readValue(input: DataInput) = Unit
  override fun writeValue(output: DataOutput, value: Unit) = Unit
}

//abstract class ClojureStringIndex : ClojureIndexBase<String>() {
//  override fun readValue(input: DataInput) = IOUtil.readUTF(input)
//  override fun writeValue(output: DataOutput, value: String) = IOUtil.writeUTF(output, value)
//}

abstract class ClojureIndexBase<V> : FileBasedIndexExtension<String, V>() {
  override fun getVersion(): Int = VERSION
  override fun getKeyDescriptor(): EnumeratorStringDescriptor = EnumeratorStringDescriptor.INSTANCE
  override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter (ClojureFileType)
  override fun dependsOnFileContent(): Boolean = true

  override fun getIndexer(): DataIndexer<String, V, FileContent> = DataIndexer { index(it.psiFile as CFile) }

  override fun getValueExternalizer(): DataExternalizer<V> = object : DataExternalizer<V> {
    override fun read(input: DataInput): V = readValue(input)
    override fun save(output: DataOutput, value: V) = writeValue(output, value)
  }

  abstract fun index(file: CFile): MutableMap<String, V>
  abstract fun readValue(input: DataInput): V
  abstract fun writeValue(output: DataOutput, value: V): Unit

}


