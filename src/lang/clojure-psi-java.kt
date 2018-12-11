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

package org.intellij.clojure.java

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.NotNullLazyKey
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider
import com.intellij.psi.meta.PsiMetaOwner
import com.intellij.psi.meta.PsiPresentableMetaData
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ArrayUtil
import com.intellij.util.ConcurrencyUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ObjectUtils
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.ContainerUtilRt
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.JBTreeTraverser
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.psi.impl.ClojureDefinitionService
import org.intellij.clojure.util.EachNth
import org.intellij.clojure.util.jbIt
import org.intellij.clojure.util.notNulls
import org.intellij.clojure.util.withPackage
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.signature.SignatureReader
import org.jetbrains.org.objectweb.asm.signature.SignatureVisitor
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentMap

/**
 * @author gregsh
 */
abstract class JavaHelper {

  companion object {

    private val INSTANCE_KEY: NotNullLazyKey<JavaHelper, Project> = NotNullLazyKey.create(
        "Service: " + JavaHelper::class.qualifiedName) {
      ServiceManager.getService(it, JavaHelper::class.java) ?: AsmHelper(it)
    }

    fun getJavaHelper(project: Project): JavaHelper = INSTANCE_KEY.getValue(project)


    private fun acceptsName(expected: String?, actual: String?) =
        "*" == expected || expected != null && expected == actual

    private fun acceptsModifiers(modifiers: Int) =
        Modifier.isPublic(modifiers) || !(Modifier.isPrivate(modifiers) || Modifier.isProtected(modifiers))
  }

  enum class Scope {
    STATIC, INSTANCE, INIT
  }

  enum class ElementType {
    PACKAGE, CLASS, CONSTRUCTOR, STATIC_METHOD, STATIC_FIELD, INSTANCE_METHOD, INSTANCE_FIELD
  }

  open fun getElementType(element: PsiElement?): ElementType? = null

  open fun findClass(className: String?): NavigatablePsiElement? = null

  open fun findClassMethods(className: String?,
                            scope: Scope,
                            name: String?,
                            paramCount: Int,
                            vararg paramTypes: String): List<NavigatablePsiElement> = emptyList()

  open fun findClassFields(className: String?,
                           scope: Scope,
                           name: String?): List<NavigatablePsiElement> = emptyList()

  open fun getSuperClassName(className: String?): String? = null
  open fun getMemberTypes(member: NavigatablePsiElement?): List<String> = emptyList()
  open fun getDeclaringClass(member: NavigatablePsiElement?): String = ""
  open fun getAnnotations(element: NavigatablePsiElement?): List<String> = emptyList()
  open fun getClassReferenceProvider(): PsiReferenceProvider? = null
  open fun findPackage(packageName: String?, withClass: String?): NavigatablePsiElement? = null

  private class PsiHelper(private val myFacade: JavaPsiFacade, private val myElementFactory: PsiElementFactory) : JavaHelper() {
    val asm = AsmHelper(myFacade.project)

    override fun getElementType(element: PsiElement?): ElementType? = when (element) {
      is PsiPackage -> ElementType.PACKAGE
      is PsiClass -> ElementType.CLASS
      is PsiMethod -> when {
        element.isConstructor -> ElementType.CONSTRUCTOR
        element.modifierList.hasModifierProperty(PsiModifier.STATIC) -> ElementType.STATIC_METHOD
        else -> ElementType.INSTANCE_METHOD
      }
      is PsiField -> when {
        element.modifierList?.hasModifierProperty(PsiModifier.STATIC) ?: false -> ElementType.STATIC_FIELD
        else -> ElementType.INSTANCE_FIELD
      }
      else -> asm.getElementType(element)
    }

    override fun getClassReferenceProvider(): PsiReferenceProvider? {
      val provider = JavaClassReferenceProvider()
      provider.isSoft = false
      return provider
    }

    override fun findClass(className: String?) : NavigatablePsiElement? =
        className?.let { myFacade.findClass(it, GlobalSearchScope.allScope(myFacade.project)) ?: asm.findClass(it) }

    override fun findPackage(packageName: String?, withClass: String?): NavigatablePsiElement? =
        myFacade.findPackage(packageName!!) as? NavigatablePsiElement ?: asm.findPackage(packageName, withClass)

    internal fun superclasses(className: String?) = JBTreeTraverser<PsiClass> { o ->
      JBIterable.of(o.superClass).append(o.interfaces)
    }
        .withRoot(findClass(className) as? PsiClass)
        .unique()
        .traverse()


    override fun findClassMethods(className: String?,
                                  scope: Scope,
                                  name: String?,
                                  paramCount: Int,
                                  vararg paramTypes: String): List<NavigatablePsiElement> =
        if (findClass(className) !is PsiClass) {
          asm.findClassMethods(className, scope, name, paramCount, *paramTypes)
        }
        else {
          superclasses(className)
              .flatten {
                if (scope == Scope.INIT) it.constructors.jbIt() else it.methods.jbIt()
              }
              .filter {
                acceptsName(name, it.name) &&
                    acceptsMethod(it, scope == Scope.STATIC) &&
                    acceptsMethod(myElementFactory, it, paramCount, *paramTypes)
              }
              .notNulls()
              .toList()
        }

    override fun findClassFields(className: String?, scope: Scope, name: String?): List<NavigatablePsiElement> =
        if (findClass(className) !is PsiClass) {
          asm.findClassFields(className, scope, name)
        }
        else superclasses(className)
            .flatten {
              it.fields.jbIt()
            }
            .filter {
              acceptsName(name, it.name) &&
                  acceptsMethod(it, scope == Scope.STATIC)
            }
            .notNulls().toList()

    override fun getSuperClassName(className: String?): String? {
      val aClass = findClass(className) as? PsiClass ?: return asm.getSuperClassName(className)
      return aClass.superClass?.qualifiedName
    }

    private fun acceptsMethod(elementFactory: PsiElementFactory,
                              method: PsiMethod,
                              paramCount: Int,
                              vararg paramTypes: String): Boolean {
      val parameterList = method.parameterList
      if (paramCount >= 0 && paramCount != parameterList.parametersCount) return false
      if (paramTypes.size == 0) return true
      if (parameterList.parametersCount < paramTypes.size) return false
      val psiParameters = parameterList.parameters
      for (i in paramTypes.indices) {
        val paramType = paramTypes[i]
        val parameter = psiParameters[i]
        val psiType = parameter.type
        if (acceptsName(paramType, psiType.canonicalText)) continue
        try {
          if (psiType.isAssignableFrom(elementFactory.createTypeFromText(paramType, parameter))) continue
        }
        catch (ignored: IncorrectOperationException) {
        }

        return false
      }
      return true
    }

    private fun acceptsMethod(method: PsiModifierListOwner, staticMethods: Boolean) = method.modifierList!!.run {
      staticMethods == hasModifierProperty(PsiModifier.STATIC) &&
          (hasModifierProperty(PsiModifier.PUBLIC) ||
              !(hasModifierProperty(PsiModifier.PROTECTED) || hasModifierProperty(PsiModifier.PRIVATE)))
    }

    override fun getMemberTypes(member: NavigatablePsiElement?): List<String> {
      if (member is PsiField) return listOf(member.type.canonicalText)
      if (member !is PsiMethod) return asm.getMemberTypes(member)
      val returnType = member.returnType
      val strings = ArrayList<String>()
      strings.add(if (returnType == null) "" else returnType.canonicalText)
      for (parameter in member.parameterList.parameters) {
        val type = parameter.type
        val generic = type is PsiClassType && type.resolve() is PsiTypeParameter
        strings.add((if (generic) "<" else "") + type.getCanonicalText(false) + if (generic) ">" else "")
        strings.add(parameter.name ?: "_")
      }
      return strings
    }

    override fun getDeclaringClass(member: NavigatablePsiElement?): String {
      if (member !is PsiMethod) return asm.getDeclaringClass(member)
      val aClass = member.containingClass
      return if (aClass == null) "" else StringUtil.notNullize(aClass.qualifiedName)
    }

    override fun getAnnotations(element: NavigatablePsiElement?): List<String> {
      if (element !is PsiModifierListOwner) return asm.getAnnotations(element)
      val modifierList = element.modifierList ?: return ContainerUtilRt.emptyList<String>()
      val strings = ArrayList<String>()
      modifierList.annotations.forEach { o ->
        if (o.parameterList.attributes.size == 0) {
          strings.add(o.qualifiedName ?: "_")
        }
      }
      return strings
    }
  }

  class AsmDocumentationProvider : DocumentationProviderEx() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
      val wrapper = ((element as? PomTargetPsiElement)?.navigationElement ?: element)
          as? MyElement<*> ?: return null
      val info = wrapper.delegate
      val pack = (info as? MemberInfo)?.declaringClass?.let { "".withPackage(StringUtil.getPackageName(it.name)) } ?: ""
      return when (info) {
        is ClassInfo -> "class ${info.name}<br><br><i>${info.url}</i>"
        is MethodInfo -> ("method ${info.types[0]} ${info.name}" +
            "(${info.types.jbIt().skip(1).filter(EachNth(2))
                .map { StringUtil.escapeXml(it) }
                .joinToString(",<br>&nbsp;&nbsp;&nbsp;&nbsp;")})").replace(pack, "") +
            "<br>in class ${info.declaringClass.name}" +
            "<br><br><i>${info.declaringClass.url}</i>"
        is FieldInfo -> "field ${info.type} ${info.name}<br>".replace(pack, "") +
            "in class ${info.declaringClass.name}" +
            "<br><br><i>${info.declaringClass.url}</i>"
        else -> null
      }.let { it?.replace("java.lang.", "") }
    }
  }

  class AsmFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean) = object : FindUsagesHandler(element) {}
    override fun canFindUsages(element: PsiElement) = element is MyElement<*>
  }

  private class AsmHelper(val project: Project) : JavaHelper() {
    val c_nulls: ConcurrentMap<String, Boolean> = ContainerUtil.createConcurrentWeakKeySoftValueMap()
    val p_nulls: ConcurrentMap<String, Boolean> = ContainerUtil.createConcurrentWeakKeySoftValueMap()
    val map: ConcurrentMap<String, MyElement<*>> = ContainerUtil.createConcurrentWeakValueMap()

    override fun getElementType(element: PsiElement?): ElementType? {
      val delegate = (element as? MyElement<*>)?.delegate
      return when (delegate) {
        is PackageInfo -> ElementType.PACKAGE
        is ClassInfo -> ElementType.CLASS
        is MethodInfo -> when (delegate.scope) {
          Scope.STATIC -> ElementType.STATIC_METHOD
          Scope.INSTANCE -> ElementType.INSTANCE_METHOD
          Scope.INIT -> ElementType.CONSTRUCTOR
        }
        is FieldInfo -> when (delegate.scope) {
          Scope.STATIC -> ElementType.STATIC_FIELD
          Scope.INSTANCE -> ElementType.INSTANCE_FIELD
          else -> null
        }
        else -> null
      }
    }

    override fun findPackage(packageName: String?, withClass: String?) =
        if (packageName == null || withClass == null) null
        else (findClass("$packageName.$withClass")?.delegate as? ClassInfo)?.url?.let { url ->
          lazyCached(packageName, p_nulls) {
            val path = VirtualFileManager.getInstance().findFileByUrl(VfsUtil.convertFromUrl(URL(url)))?.parent?.path
            if (path == null) null else PackageInfo(packageName, path)
          }
        }

    override fun findClass(className: String?) = lazyCached(className, c_nulls) { findClassSafe(className) }

    internal fun lazyCached(id: String?, nulls: ConcurrentMap<String, Boolean>, info: () -> Any?): MyElement<*>? {
      return map[id ?: return null] ?: run { if (nulls[id] == true) null else info().let {
        if (it == null) { nulls.put(id, true); null } else
          ConcurrencyUtil.cacheOrGet(map, id, MyElement(project, it))
      } }
    }

    internal fun cached(id: String, info: Any) =
        map[id] ?: ConcurrencyUtil.cacheOrGet(map, id, MyElement(project, info))

    internal fun superclasses(name: String?) = JBTreeTraverser<MyElement<*>> { o ->
      JBIterable.of((o.delegate as? ClassInfo)?.superClass)
          .append((o.delegate as? ClassInfo)?.interfaces)
          .map(this@AsmHelper::findClass)
          .notNulls()
    }
        .withRoot(findClass(name))
        .unique()
        .traverse()

    override fun findClassMethods(className: String?,
                                  scope: Scope,
                                  name: String?,
                                  paramCount: Int,
                                  vararg paramTypes: String): List<NavigatablePsiElement> {
      return superclasses(className).flatten {
        (it.delegate as ClassInfo).methods.jbIt().transform { o ->
          if (acceptsName(name, o.name) &&
              acceptsMethod(o, scope) &&
              acceptsMethod(o, paramCount, *paramTypes)) cached(o.name + o.signature + className, o) else null
        }
      }.notNulls().toList()
    }

    override fun findClassFields(className: String?,
                                 scope: Scope,
                                 name: String?): List<NavigatablePsiElement> {
      return superclasses(className).flatten {
        (it.delegate as ClassInfo).fields.jbIt().transform { o ->
          if (acceptsName(name, o.name) &&
              acceptsField(o, scope)) cached(o.name + o.signature + className, o)
          else null
        }
      }.notNulls().toList()
    }

    override fun getSuperClassName(className: String?) = (findClass(className)?.delegate as? ClassInfo)?.superClass

    override fun getMemberTypes(member: NavigatablePsiElement?): List<String> = (member as? MyElement<*>)?.delegate.let { when(it) {
      is ClassInfo -> Collections.singletonList(ClojureConstants.J_CLASS)
      is MethodInfo -> it.types
      is FieldInfo -> Collections.singletonList(it.type)
      else -> emptyList()
    }}

    override fun getDeclaringClass(member: NavigatablePsiElement?) =
        ((member as? MyElement<*>)?.delegate as? MemberInfo)?.declaringClass?.name ?: ""

    override fun getAnnotations(element: NavigatablePsiElement?): List<String> {
      val delegate = if (element == null) null else (element as MyElement<*>).delegate
      return when (delegate) {
        is ClassInfo -> return delegate.annotations
        is MethodInfo -> return delegate.annotations
        else -> emptyList()
      }
    }

    private class MyClassVisitor(val info: ClassInfo) : ClassVisitor(Opcodes.ASM5) {

      override fun visit(version: Int,
                         access: Int,
                         name: String,
                         signature: String?,
                         superName: String?,
                         interfaces: Array<String>) {
        info.superClass = fixClassName(superName)
        for (s in interfaces) {
          info.interfaces.add(fixClassName(s))
        }
      }

      override fun visitEnd() {
      }

      override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
        val sig = ObjectUtils.chooseNotNull(signature, desc)
        val methodInfo = MethodInfo(name, info, access, sig)
        processSignature(methodInfo.types, methodInfo.signature, "${methodInfo.declaringClass}#${methodInfo.name}(..)")
        if (name != "<clinit>") info.methods.add(methodInfo)
        return object : MethodVisitor(Opcodes.ASM5) {
          override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
            return object : MyAnnotationVisitor() {
              override fun visitEnd() {
                if (annoParamCounter == 0) {
                  methodInfo.annotations.add(fixClassName(desc.substring(1, desc.length - 1)))
                }
              }
            }
          }
        }
      }

      override fun visitField(access: Int, name: String?, desc: String?, signature: String?, value: Any?): FieldVisitor? {
        if (name == null) return null
        val sig = ObjectUtils.chooseNotNull(signature, desc)
        val types = processSignature(ContainerUtil.newSmartList(), sig, "${info.name}.$name}")
        val field = FieldInfo(name, info, access, if (types.isEmpty()) sig else types[0], sig)
        info.fields.add(field)
        return super.visitField(access, name, desc, signature, value)
      }

      inner open class MyAnnotationVisitor : AnnotationVisitor(Opcodes.ASM5) {
        var annoParamCounter: Int = 0

        override fun visit(s: String, o: Any) {
          annoParamCounter++
        }

        override fun visitEnum(s: String, s2: String, s3: String) {
          annoParamCounter++
        }

        override fun visitAnnotation(s: String, s2: String): AnnotationVisitor? {
          annoParamCounter++
          return null
        }

        override fun visitArray(s: String): AnnotationVisitor? {
          annoParamCounter++
          return null
        }
      }
    }

    private class MySignatureVisitor(val types: MutableList<in String>) : SignatureVisitor(Opcodes.ASM5) {
      enum class State {
        PARAM, RETURN, CLASS, ARRAY, GENERIC, BOUNDS, EXCEPTION
      }

      val states: Deque<State> = ArrayDeque()

      val sb = StringBuilder()

      override fun visitFormalTypeParameter(s: String?) {
        // collect them
      }

      override fun visitInterfaceBound(): SignatureVisitor {
        finishElement(null)
        states.push(State.BOUNDS)
        return this
      }

      override fun visitSuperclass(): SignatureVisitor {
        finishElement(null)
        states.push(State.BOUNDS)
        return this
      }

      override fun visitInterface(): SignatureVisitor {
        finishElement(null)
        states.push(State.BOUNDS)
        return this
      }

      override fun visitParameterType(): SignatureVisitor {
        finishElement(null)
        states.push(State.PARAM)
        return this
      }

      override fun visitReturnType(): SignatureVisitor {
        finishElement(null)
        states.push(State.RETURN)
        return this
      }

      override fun visitExceptionType(): SignatureVisitor? {
        finishElement(null)
        states.push(State.EXCEPTION)
        return this
      }

      override fun visitBaseType(c: Char) {
        sb.append(org.jetbrains.org.objectweb.asm.Type.getType(c.toString()).className)
      }

      override fun visitTypeVariable(s: String?) {
        sb.append("<").append(s).append(">")
      }

      override fun visitArrayType(): SignatureVisitor {
        states.push(State.ARRAY)
        return this
      }

      override fun visitClassType(s: String?) {
        states.push(State.CLASS)
        sb.append(fixClassName(s))
      }

      override fun visitInnerClassType(s: String?) {
      }

      override fun visitTypeArgument() {
        states.push(State.GENERIC)
        sb.append("<")
      }

      override fun visitTypeArgument(c: Char): SignatureVisitor {
        if (states.peekFirst() == State.CLASS) {
          states.push(State.GENERIC)
          sb.append("<")
        }
        else {
          finishElement(State.GENERIC)
          sb.append(", ")
        }
        return this
      }

      override fun visitEnd() {
        finishElement(State.CLASS)
        states.pop()
      }

      fun finishElement(finishState: State?) {
        if (sb.length == 0) return
        loop@ while (!states.isEmpty()) {
          if (finishState == states.peekFirst()) break
          val state = states.pop()
          when (state) {
            JavaHelper.AsmHelper.MySignatureVisitor.State.PARAM -> {
              types.add(sb.toString())
              types.add("p" + types.size / 2)
              sb.setLength(0)
              break@loop
            }
            JavaHelper.AsmHelper.MySignatureVisitor.State.RETURN -> {
              types.add(0, sb.toString())
              sb.setLength(0)
              break@loop
            }
            JavaHelper.AsmHelper.MySignatureVisitor.State.ARRAY -> sb.append("[]")
            JavaHelper.AsmHelper.MySignatureVisitor.State.GENERIC -> sb.append(">")
            else -> {
            }
          }
        }
      }
    }

    private fun acceptsMethod(method: MethodInfo, paramCount: Int, vararg paramTypes: String): Boolean {
      if (paramCount >= 0 && paramCount + 1 != method.types.size) return false
      if (paramTypes.size == 0) return true
      if (paramTypes.size + 1 > method.types.size) return false
      for (i in paramTypes.indices) {
        val paramType = paramTypes[i]
        val parameter = method.types[i + 1]
        if (acceptsName(paramType, parameter)) continue
        val info = (findClass(paramType) as? MyElement)?.delegate as? ClassInfo
        if (info != null) {
          if (Comparing.equal(info.superClass, parameter)) continue
          if (info.interfaces.contains(parameter)) continue
        }
        return false
      }
      return true
    }

    private fun findClassSafe(className: String?): ClassInfo? {
      if (className == null) return null
      try {
        var lastDot = className.lastIndexOf('.')
        var url: String? = null
        var stream: InputStream? = null
        while (stream == null && lastDot > 0) {
          val pkgName = className.substring(0, lastDot).replace('.', '/')
          val clzName = className.substring(lastDot + 1).replace('.', '$') + ".class"
          val bundledUrl = JavaHelper::class.java.classLoader.getResource("$pkgName/$clzName")
          stream = try { bundledUrl?.openStream() } catch (e: Exception) { null }
          url = bundledUrl?.toExternalForm()
          if (stream != null) break
          for (psiFile in FilenameIndex.getFilesByName(project, clzName, GlobalSearchScope.allScope(project))) {
            url = "jar:file://" + psiFile.virtualFile.presentableUrl
            if (url.endsWith("!/$pkgName/$clzName")) {
              stream = try { psiFile.virtualFile.inputStream } catch (e: Exception) { null }
              break
            }
          }
          lastDot = className.lastIndexOf('.', lastDot - 1)
        }

        if (url == null || stream == null) return null
        val bytes = FileUtil.loadBytes(stream)
        stream.close()
        val info = ClassInfo(className, url)
        processClassBytes(info, bytes)
        return info
      }
      catch (e: Exception) {
        reportException(e, className, null)
      }

      return null
    }

    companion object {

      private fun acceptsMethod(method: MethodInfo, scope: Scope): Boolean {
        return method.scope == scope && acceptsModifiers(method.modifiers)
      }

      private fun acceptsField(field: FieldInfo, scope: Scope): Boolean {
        return field.scope == scope && acceptsModifiers(field.modifiers)
      }

      private fun processClassBytes(info: ClassInfo, bytes: ByteArray) {
        try {
          ClassReader(bytes).accept(MyClassVisitor(info), 0)
        }
        catch(e: Exception) {
          reportException(e, info.name, "<bytes>")
        }
      }

      private fun processSignature(result: MutableList<String>, signature: String, targetName: String): List<String> {
        try {
          val visitor = MySignatureVisitor(result)
          SignatureReader(signature).accept(visitor)
          visitor.finishElement(null)
          if (result.isEmpty()) { // field
            result.add(visitor.sb.toString())
          }
        }
        catch (e: Exception) {
          reportException(e, targetName, signature)
        }
        return result
      }

      private fun reportException(e: Exception, target: String, signature: String?) {
        if (e is ProcessCanceledException) return
        System.err.println(e.javaClass.simpleName + " while reading " + target +
            if (signature == null) "" else " signature " + signature)
      }

      private fun fixClassName(s: String?) = s?.replace('/', '.')?.replace('$', '.') ?: "null"
    }
  }

  private class MyElement<out T>(private val project: Project, val delegate: T) :
      FakePsiElement(), NavigatablePsiElement, PsiQualifiedNamedElement, PsiMetaOwner, PsiPresentableMetaData {
    override fun getParent() = null
    override fun getProject() = project
    override fun getManager() = PsiManager.getInstance(project)
    override fun isValid() = project.isOpen
    override fun getContainingFile() = null
    override fun canNavigate() = true
    override fun canNavigateToSource() = true

    override fun getName() = when (delegate) {
      is PackageInfo -> StringUtil.getShortName(delegate.name)
      is ClassInfo -> StringUtil.getShortName(delegate.name)
      is MethodInfo -> delegate.name
      is FieldInfo -> delegate.name
      else -> null
    }

    override fun getQualifiedName() = when (delegate) {
      is PackageInfo -> delegate.name
      is ClassInfo -> delegate.name
      else -> name
    }

    override fun processDeclarations(processor: PsiScopeProcessor, state: ResolveState, lastParent: PsiElement?, place: PsiElement): Boolean {
      val classInfo = delegate as? ClassInfo ?: return true
      val asmHelper = ClojureDefinitionService.getInstance(project).java.let { (it as? PsiHelper)?.asm ?: it as AsmHelper }
      classInfo.methods.forEach { o ->
        if (o.scope == Scope.INIT || !acceptsModifiers(o.modifiers)) return@forEach
        if (!processor.execute(asmHelper.cached(o.name + o.signature + classInfo.name, o), state)) return false
      }
      classInfo.fields.forEach { o ->
        if (!acceptsModifiers(o.modifiers)) return@forEach
        if (!processor.execute(asmHelper.cached(o.name + classInfo.name, o), state)) return false
      }
      return true
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other == null || javaClass != other.javaClass) return false

      val element = other as? MyElement<*> ?: return false
      if (delegate != element.delegate) return false

      return true
    }

    override fun getIcon(open: Boolean) = when (delegate) {
      is PackageInfo -> AllIcons.Nodes.Package
      is ClassInfo -> AllIcons.Nodes.Class
      is MethodInfo -> AllIcons.Nodes.Method
      is FieldInfo -> AllIcons.Nodes.Field
      else -> null
    }

    override fun getPresentableText() = when (delegate) {
      is PackageInfo -> delegate.name
      is ClassInfo -> delegate.name
      is MethodInfo -> delegate.signature
      is FieldInfo -> delegate.name
      else -> null
    }

    override fun getLocationString() = when (delegate) {
      is MethodInfo -> delegate.declaringClass.name
      is FieldInfo -> delegate.declaringClass.name
      else -> null
    }

    override fun getName(context: PsiElement?) = getName()
    override fun getIcon() = getIcon(false)
    override fun getMetaData() = this
    override fun getDependences(): Array<out Any> = ArrayUtil.EMPTY_OBJECT_ARRAY
    override fun init(element: PsiElement?) = Unit
    override fun getDeclaration() = this

    override fun getTypeName() = when (delegate) {
      is PackageInfo -> "package"
      is ClassInfo -> "class"
      is MethodInfo -> "method"
      is FieldInfo -> "field"
      else -> null
    }

    override fun hashCode() = delegate!!.hashCode()
    override fun toString() = delegate.toString()
  }

  private class PackageInfo(val name: String, val url: String) {
    override fun toString() = "Package {$name}"
  }

  private class ClassInfo(val name: String, val url: String) {
    var superClass: String = CommonClassNames.JAVA_LANG_OBJECT
    val interfaces: MutableList<String> = ContainerUtil.newSmartList()
    val annotations: MutableList<String> = ContainerUtil.newSmartList()
    val methods: MutableList<MethodInfo> = ContainerUtil.newSmartList()
    val fields: MutableList<FieldInfo> = ContainerUtil.newSmartList()

    override fun toString() = "Class {$name}"
  }

  private open class MemberInfo(val name: String, val declaringClass: ClassInfo)

  private class MethodInfo(name: String, declaringClass: ClassInfo, val modifiers: Int, val signature: String) :
      MemberInfo(name, declaringClass) {
    val scope = if ("<init>" == name) Scope.INIT else if (Modifier.isStatic(modifiers)) Scope.STATIC else Scope.INSTANCE
    val annotations: MutableList<String> = ContainerUtil.newSmartList()
    val types: MutableList<String> = ContainerUtil.newSmartList()

    override fun toString() = "Method {$name(), $types, @$annotations}"
  }

  private class FieldInfo(name: String, declaringClass: ClassInfo, val modifiers: Int, val type: String, val signature: String) :
      MemberInfo(name, declaringClass) {
    val scope = if (Modifier.isStatic(modifiers)) Scope.STATIC else Scope.INSTANCE
    val annotations: MutableList<String> = ContainerUtil.newSmartList()

    override fun toString() = "Field {$name, $type, @$annotations}"
  }

}
