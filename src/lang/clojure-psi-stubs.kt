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
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.psi.CFile
import org.intellij.clojure.psi.Defn
import org.intellij.clojure.psi.IDef
import org.intellij.clojure.psi.SymKey
import org.intellij.clojure.psi.impl.CFileImpl
import org.intellij.clojure.util.asDef
import org.intellij.clojure.util.cljTraverser
import org.intellij.clojure.util.filter
import kotlin.reflect.jvm.internal.impl.utils.SmartList

/**
 * @author gregsh
 */
private val VERSION: Int = 1

class ClojureStubBuilder : BinaryFileStubBuilder {
  override fun getStubVersion() = VERSION
  override fun acceptsFile(file: VirtualFile): Boolean = file.fileType == ClojureFileType
  override fun buildStubTree(fileContent: FileContent?): Stub? {
    return buildStubTree(fileContent?.psiFile as? CFile ?: return null)
  }

  companion object {
    init {
      SerializationManager.getInstance().run {
        registerSerializer(CFileStub.SERIALIZER)
        registerSerializer(CListStub.SERIALIZER)
        registerSerializer(CPrototypeStub.SERIALIZER)
      }
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

  fun registerListStub(child: CListStub) { childMap.put(child.key, child) }
  fun findChildStub(key: SymKey) = childMap[key]

  override fun getStubType() = SERIALIZER

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

  init {
    JBIterable.generate(parent, { it -> it.parentStub })
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

class CPrototypeStub(val args: List<String>, parent: CStub?) : CStub(parent) {

  override fun getStubType() = SERIALIZER
  override fun toString() = args.toString()

  companion object {
    val SERIALIZER = object : ObjectStubSerializer<CPrototypeStub, CStub> {
      override fun getExternalId() = "clojure.PROTO"
      override fun indexStub(stub: CPrototypeStub, sink: IndexSink) = Unit

      override fun serialize(stub: CPrototypeStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.args.joinToString(","))
      }

      override fun deserialize(dataStream: StubInputStream, parentStub: CStub?): CPrototypeStub {
        return CPrototypeStub(dataStream.readName()?.string?.split(",") ?: emptyList(), parentStub)
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
  val visited = mutableMapOf<SymKey, CStub>()
  val s = file.cljTraverser().regard { it is CFile || it.asDef != null }.traverse().skip(1)
  val traceIt: TreeTraversal.TracingIt<PsiElement> = s.typedIterator()
  for (e in traceIt) {
    val def = e.asDef!!.def!!
    val key = SymKey(def)
    val parentStub = map[traceIt.parent()]!!
    map[e] = visited.getOrPut(key, {
      val stub = CListStub(key, parentStub)
      if (def is Defn) {
        def.protos.forEach {
          CPrototypeStub(it.args, stub)
        }
      }
      stub
    })
  }
  (file as? CFileImpl)?.fileStub = fileStub
  return fileStub
}


