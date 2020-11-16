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

package org.intellij.clojure.psi.stubs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.*
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.TreeTraversal
import com.intellij.util.indexing.FileContent
import com.intellij.util.text.nullize
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.util.cljTraverser
import org.intellij.clojure.util.filter
import org.intellij.clojure.util.jbIt
import org.intellij.clojure.util.role
import kotlin.reflect.jvm.internal.impl.utils.SmartList

/**
 * @author gregsh
 */
val VERSION: Int = 7

class ClojureStubBuilder : BinaryFileStubBuilder {
  override fun getStubVersion() = VERSION
  override fun acceptsFile(file: VirtualFile): Boolean = file.fileType == ClojureFileType
  override fun buildStubTree(fileContent: FileContent): Stub? {
    return buildStubTree(fileContent.psiFile as? CFile ?: return null)
  }

  @Suppress("unused")
  private interface Holder {
    companion object {
      @JvmField val FILE = CFileStub.SERIALIZER
      @JvmField val LIST = CListStub.SERIALIZER
      @JvmField val PROTO = CPrototypeStub.SERIALIZER
      @JvmField val META = CMetaStub.SERIALIZER
      @JvmField val IMPORT = CImportStub.SERIALIZER
    }
  }
}

abstract class CStub(parent: CStub?) : ObjectStubBase<CStub>(parent) {
  init {
    @Suppress("LeakingThis")
    parent?.children?.add(this)
  }

  private val children = SmartList<CStub>()

  override fun getChildrenStubs() = children
}

class CFileStub(val namespace: String) : CStub(null) {

  private val childMap = linkedMapOf<SymKey, CListStub>()

  fun registerListStub(child: CListStub) { childMap[child.key] = child }
  fun findChildStub(key: SymKey) = childMap[key]

  override fun getStubType() = SERIALIZER as ObjectStubSerializer<*, Stub>

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CFileStub, CStub> {
      override fun getExternalId() = "clojure.FILE"
      override fun indexStub(stub: CFileStub, sink: IndexSink) = Unit

      override fun serialize(stub: CFileStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.namespace)
      }

      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?): CFileStub {
        return CFileStub(dataStream.readName()?.string ?: ClojureConstants.NS_USER)
      }
    }
  }
}

class CListStub(val key: SymKey,
                parent: CStub?) :
    CStub(parent), IDef by key {
  val meta: Map<String, String> get() = (childrenStubs.firstOrNull() as? CMetaStub)?.map ?: emptyMap()

  init {
    JBIterable.generate(parent) { it.parentStub }
        .filter(CFileStub::class)
        .first()
        ?.registerListStub(this)
  }

  override fun getStubType() = SERIALIZER
  override fun toString() = key.toString()

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CListStub, CStub> {
      override fun getExternalId() = "clojure.LIST"
      override fun indexStub(stub: CListStub, sink: IndexSink) = Unit

      override fun serialize(stub: CListStub, dataStream: StubOutputStream) {
        dataStream.writeKey(stub.key)
      }

      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?): CListStub {
        return CListStub(dataStream.readKey(), parentStub)
      }
    }
  }
}

class CPrototypeStub(val args: List<Arg>, val typeHint: String?, parent: CStub?) : CStub(parent) {

  override fun getStubType() = SERIALIZER
  override fun toString() = args.toString()

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CPrototypeStub, CStub> {
      override fun getExternalId() = "clojure.PROTO"
      override fun indexStub(stub: CPrototypeStub, sink: IndexSink) = Unit

      override fun serialize(stub: CPrototypeStub, dataStream: StubOutputStream) {
        val argText = stub.args.map { it.name.escBar() + "|" + (it.typeHint?.escBar() ?: "") }
        dataStream.writeName(argText.joinToString("|"))
        dataStream.writeName(stub.typeHint)
      }

      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?): CPrototypeStub {
        return CPrototypeStub(
            dataStream.readName()?.string?.split("|").jbIt().split(2, true).map {
              Arg(it[0]!!.unescBar(), it[1].nullize()?.unescBar()) }.toList(),
            dataStream.readName()?.string,
            parentStub)
      }
    }
  }
}

class CMetaStub(val map: Map<String, String>, parent: CStub?) : CStub(parent) {

  override fun getStubType() = SERIALIZER
  override fun toString() = map.toString()

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CMetaStub, CStub> {
      override fun getExternalId() = "clojure.META"
      override fun indexStub(stub: CMetaStub, sink: IndexSink) = Unit

      override fun serialize(stub: CMetaStub, dataStream: StubOutputStream) = dataStream.writeMap(stub.map)
      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?) = CMetaStub(dataStream.readMap(), parentStub)
    }
  }
}

internal class CImportStub(val import: Import, val dialect: Dialect, parent: CStub?) : CStub(parent) {
  override fun getStubType() = SERIALIZER

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CImportStub, CStub> {
      override fun getExternalId() = "clojure.IMPORT"
      override fun indexStub(stub: CImportStub, sink: IndexSink) = Unit

      override fun serialize(stub: CImportStub, dataStream: StubOutputStream) {
        val import = stub.import
        dataStream.writeName(stub.dialect.name)
        dataStream.writeName(import.nsType)
        dataStream.writeName(import.namespace)
        dataStream.writeName(import.alias)
        dataStream.writeSet(import.refer)
        dataStream.writeSet(import.only)
        dataStream.writeSet(import.exclude)
        @Suppress("UNCHECKED_CAST")
        dataStream.writeMap(import.rename as Map<String, String>)
      }

      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?): CImportStub {
        val langKind = Dialect.valueOf(dataStream.readName()!!.string)
        return CImportStub(Import(
            dataStream.readName()!!.string,
            dataStream.readName()!!.string,
            dataStream.readName()!!.string,
            null,
            dataStream.readSet(),
            dataStream.readSet(),
            dataStream.readSet(),
            dataStream.readMap()), langKind, parentStub)
      }
    }
  }
}


private fun StubOutputStream.writeKey(key: SymKey) = with(key) {
  writeName(name); writeName(namespace); writeName(type)
}

private fun StubInputStream.readKey(): SymKey =
    SymKey(readName()?.string ?: "", readName()?.string ?: "", readName()?.string ?: "")

private val BUILDING_STUBS: ThreadLocal<Boolean> = object : ThreadLocal<Boolean>() {
  override fun initialValue() = false
}

internal fun isBuildingStubs() = BUILDING_STUBS.get()

internal fun buildStubTree(file: CFile): CFileStub {
  BUILDING_STUBS.set(true)
  return try {
    buildStubTreeImpl(file)
  }
  finally {
    BUILDING_STUBS.set(false)
  }
}

private fun buildStubTreeImpl(file: CFile): CFileStub {
  val fileStub = CFileStub(file.namespace)
  val map = mutableMapOf<PsiElement, CStub>(file to fileStub)
  val s = file.cljTraverser().regard {
    it is CFile || it is CList && (it.role == Role.DEF || it.role == Role.NS) }.traverse().skip(1)
  val traceIt: TreeTraversal.TracingIt<PsiElement> = s.typedIterator()
  for (e in traceIt) when (e.role) {
    Role.DEF -> {
      map[e] = buildDefStub(e as CComposite, map[traceIt.parent()]!!)
    }
    Role.NS -> {
      buildNSStub(e as CComposite, map[traceIt.parent()]!!)
    }
    else -> Unit
  }
  (file as? CFileImpl)?.fileStub = fileStub
  return fileStub
}

private fun buildDefStub(e: CComposite, parentStub: CStub): CListStub {
  val def = e.def!!
  val key = SymKey(def)
  val stub = CListStub(key, parentStub)
  if (def is Def) {
    if (def.meta.isNotEmpty()) {
      CMetaStub(def.meta, stub)
    }
    def.protos.forEach {
      CPrototypeStub(it.args, it.typeHint, stub)
    }
  }
  return stub
}

private fun buildNSStub(e: CComposite, parentStub: CStub) {
  val def = e.def
  if (def != null) {
    buildDefStub(e, parentStub)
  }
  val imports = (def as? NSDef)?.imports ?: JBIterable.of(e.data as? Imports)
  imports.forEach { o -> o.imports.forEach { o1 -> CImportStub(Import(
      o1.nsType, o1.namespace, o1.alias, null, o1.refer, o1.only, o1.exclude,
      o1.rename.entries.jbIt().reduce(HashMap()) { map, e ->
        map[(e.value as CSymbol).name] = e.key; map}), o.dialect, parentStub) } }
}

fun StubOutputStream.writeMap(o: Map<String, String>) {
  writeInt(o.size)
  o.keys.stream().sorted().forEach { writeName(it); writeName(o[it]) }
}

fun StubInputStream.readMap(): Map<String, String> {
  val size = readInt()
  if (size == 0) return emptyMap()
  val o = HashMap<String, String>()
  for (i in 1..size) {
    o[readName()?.string ?: ""] = readName()?.string ?: ""
  }
  return o
}

fun StubOutputStream.writeSet(o: Set<String>) {
  writeInt(o.size)
  o.stream().sorted().forEach { writeName(it) }
}

fun StubInputStream.readSet(): Set<String> {
  val size = readInt()
  if (size == 0) return emptySet()
  val map = HashSet<String>()
  for (i in 1..size) {
    map.add(readName()?.string ?: "")
  }
  return map
}

private fun String.escBar() = replace("|", "&bar;")
private fun String.unescBar() = replace("&bar;", "|")