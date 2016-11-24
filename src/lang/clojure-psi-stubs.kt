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

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.NS_USER
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.CDefImpl
import org.intellij.clojure.psi.impl.CKeywordImpl
import org.intellij.clojure.psi.impl.CMDefImpl

/**
 * @author gregsh
 */
private val VERSION: Int = 6
private val NS_VERSION: Int = 1
private val DEF_VERSION: Int = 1
private val KEYWORD_VERSION: Int = 1

val NS_INDEX_KEY: StubIndexKey<String, ClojureFile> = StubIndexKey.createIndexKey("clj.namespaces")
val DEF_INDEX_KEY: StubIndexKey<String, CDef> = StubIndexKey.createIndexKey("clj.definitions")
val KEYWORD_INDEX_KEY: StubIndexKey<String, CKeyword> = StubIndexKey.createIndexKey("clj.keywords")

class ClojureNSIndex : StringStubIndexExtension<ClojureFile>() {
  override fun getKey() = NS_INDEX_KEY
  override fun getVersion() = NS_VERSION
}

class ClojureDefsIndex : StringStubIndexExtension<CDef>() {
  override fun getKey() = DEF_INDEX_KEY
  override fun getVersion() = DEF_VERSION
}

class ClojureKeywordIndex : StringStubIndexExtension<CKeyword>() {
  override fun getKey() = KEYWORD_INDEX_KEY
  override fun getVersion() = KEYWORD_VERSION
}

class CKeywordStub(override val name: String, override val namespace: String, stub: StubElement<*>?) :
    StubBase<CKeyword>(stub, ClojureTypes.C_KEYWORD as IStubElementType<out StubElement<*>, *>), DefInfo {
  override val type = "keyword"
}

class CListStub(override val type: String,
                override val name: String,
                override val namespace: String,
                stub: StubElement<*>?) :
    StubBase<CList>(stub, ClojureTypes.C_LIST as IStubElementType<out StubElement<*>, *>), DefInfo

class CFileStub private constructor(val namespace: String, file: ClojureFile?) : PsiFileStubImpl<ClojureFile>(file) {
  constructor(file: ClojureFile) : this(file.namespace, file)
  constructor(namespace: String) : this(namespace, null)
  override fun getType() = ClojureTokens.CLJ_FILE_TYPE
}

class ClojureFileElementType(name: String, language: Language) : IStubFileElementType<CFileStub>(name, language) {
  override fun getExternalId() = "${language.id.toLowerCase()}.FILE"
  override fun getStubVersion() = super.getStubVersion() + VERSION
  override fun getBuilder() = object: DefaultStubBuilder() {
    override fun createStubForFile(file: PsiFile): StubElement<*> {
      return CFileStub(file as ClojureFile)
    }
  }

  override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): CFileStub =
      CFileStub(dataStream.readName()?.string?: NS_USER)

  override fun serialize(stub: CFileStub, dataStream: StubOutputStream) {
    dataStream.writeName(stub.namespace)
  }

  override fun indexStub(stub: PsiFileStub<*>, sink: IndexSink) {
    val o = stub as? CFileStub ?: return
    sink.occurrence(NS_INDEX_KEY, o.namespace)
  }
}

class CListElementType(name: String) : IStubElementType<CListStub, CDef>(name, ClojureLanguage), ClojureElementType {
  override fun getExternalId(): String = "clj." + super.toString()
  override fun createPsi(stub: CListStub) =
      if (stub.type == ClojureConstants.TYPE_PROTOCOL_METHOD) CMDefImpl(stub) else CDefImpl(stub)

  override fun createStub(psi: CDef, parentStub: StubElement<*>?): CListStub {
    val def = psi.def
    return CListStub(def.type, def.name, def.namespace, parentStub)
  }

  override fun serialize(stub: CListStub, dataStream: StubOutputStream) {
    dataStream.writeName(stub.type)
    dataStream.writeName(stub.name)
    dataStream.writeName(stub.namespace)
  }

  override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
      CListStub(
          dataStream.readName()?.string ?: "",
          dataStream.readName()?.string ?: "",
          dataStream.readName()?.string ?: "", parentStub)

  override fun indexStub(stub: CListStub, sink: IndexSink) {
    sink.occurrence(DEF_INDEX_KEY, stub.name)
  }

  override fun shouldCreateStub(node: ASTNode?): Boolean {
    val psi = node?.psi ?: return false
    return psi is CDef && psi.firstChild.node.elementType == ClojureTypes.C_PAREN1
  }
}

class CKeywordElementType(name: String) : IStubElementType<CKeywordStub, CKeyword>(name, ClojureLanguage), ClojureElementType {
  override fun getExternalId(): String = "clj." + super.toString()
  override fun createPsi(stub: CKeywordStub) = CKeywordImpl(stub)
  override fun createStub(psi: CKeyword, parentStub: StubElement<*>?) = CKeywordStub(psi.name, psi.namespace, parentStub)
  override fun serialize(stub: CKeywordStub, dataStream: StubOutputStream) {
    dataStream.writeName(stub.name)
    dataStream.writeName(stub.namespace)
  }
  override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
      CKeywordStub(dataStream.readName()?.string ?: "", dataStream.readName()?.string ?: "", parentStub)
  override fun indexStub(stub: CKeywordStub, sink: IndexSink) = sink.occurrence(KEYWORD_INDEX_KEY, stub.name)
}