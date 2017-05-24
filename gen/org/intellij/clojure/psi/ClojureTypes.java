// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import org.intellij.clojure.psi.impl.*;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashMap;

public interface ClojureTypes {

  IElementType C_ACCESS = new ClojureNodeType("C_ACCESS");
  IElementType C_CONSTRUCTOR = new ClojureNodeType("C_CONSTRUCTOR");
  IElementType C_FORM = new ClojureNodeType("C_FORM");
  IElementType C_FUN = new ClojureNodeType("C_FUN");
  IElementType C_KEYWORD = new ClojureNodeType("C_KEYWORD");
  IElementType C_LIST = new ClojureNodeType("C_LIST");
  IElementType C_LITERAL = new ClojureNodeType("C_LITERAL");
  IElementType C_MAP = new ClojureNodeType("C_MAP");
  IElementType C_METADATA = new ClojureNodeType("C_METADATA");
  IElementType C_READER_MACRO = new ClojureNodeType("C_READER_MACRO");
  IElementType C_REGEXP = new ClojureNodeType("C_REGEXP");
  IElementType C_SET = new ClojureNodeType("C_SET");
  IElementType C_SYMBOL = new ClojureNodeType("C_SYMBOL");
  IElementType C_VEC = new ClojureNodeType("C_VEC");

  IElementType C_AT = new ClojureTokenType("@");
  IElementType C_BOOL = new ClojureTokenType("bool");
  IElementType C_BRACE1 = new ClojureTokenType("{");
  IElementType C_BRACE2 = new ClojureTokenType("}");
  IElementType C_BRACKET1 = new ClojureTokenType("[");
  IElementType C_BRACKET2 = new ClojureTokenType("]");
  IElementType C_CHAR = new ClojureTokenType("char");
  IElementType C_COLON = new ClojureTokenType(":");
  IElementType C_COLONCOLON = new ClojureTokenType("::");
  IElementType C_COMMA = new ClojureTokenType(",");
  IElementType C_DOT = new ClojureTokenType(".");
  IElementType C_DOTDASH = new ClojureTokenType(".-");
  IElementType C_HAT = new ClojureTokenType("^");
  IElementType C_HEXNUM = new ClojureTokenType("hexnum");
  IElementType C_NIL = new ClojureTokenType("nil");
  IElementType C_NUMBER = new ClojureTokenType("number");
  IElementType C_PAREN1 = new ClojureTokenType("(");
  IElementType C_PAREN2 = new ClojureTokenType(")");
  IElementType C_QUOTE = new ClojureTokenType("'");
  IElementType C_RATIO = new ClojureTokenType("ratio");
  IElementType C_RDXNUM = new ClojureTokenType("rdxnum");
  IElementType C_SHARP = new ClojureTokenType("#");
  IElementType C_SHARP_COMMENT = new ClojureTokenType("#_");
  IElementType C_SHARP_EQ = new ClojureTokenType("#=");
  IElementType C_SHARP_HAT = new ClojureTokenType("#^");
  IElementType C_SHARP_NS = new ClojureTokenType("#:");
  IElementType C_SHARP_QMARK = new ClojureTokenType("#?");
  IElementType C_SHARP_QMARK_AT = new ClojureTokenType("#?@");
  IElementType C_SHARP_QUOTE = new ClojureTokenType("#'");
  IElementType C_SLASH = new ClojureTokenType("/");
  IElementType C_STRING = new ClojureTokenType("string");
  IElementType C_STRING_UNCLOSED = new ClojureTokenType("string_unclosed");
  IElementType C_SYM = new ClojureTokenType("sym");
  IElementType C_SYNTAX_QUOTE = new ClojureTokenType("`");
  IElementType C_TILDE = new ClojureTokenType("~");
  IElementType C_TILDE_AT = new ClojureTokenType("~@");

  class Classes {
    public static Class<?> findClass(IElementType elementType) {
      return ourMap.get(elementType);
    }

    public static Set<IElementType> elementTypes() {
      return Collections.unmodifiableSet(ourMap.keySet());
    }

    private static final LinkedHashMap<IElementType, Class<?>> ourMap = new LinkedHashMap<IElementType, Class<?>>();

    static {
      ourMap.put(C_ACCESS, CAccessImpl.class);
      ourMap.put(C_CONSTRUCTOR, CConstructorImpl.class);
      ourMap.put(C_FORM, CFormImpl.class);
      ourMap.put(C_FUN, CFunImpl.class);
      ourMap.put(C_KEYWORD, CKeywordImpl.class);
      ourMap.put(C_LIST, CListImpl.class);
      ourMap.put(C_LITERAL, CLiteralImpl.class);
      ourMap.put(C_MAP, CMapImpl.class);
      ourMap.put(C_METADATA, CMetadataImpl.class);
      ourMap.put(C_READER_MACRO, CReaderMacroImpl.class);
      ourMap.put(C_REGEXP, CRegexpImpl.class);
      ourMap.put(C_SET, CSetImpl.class);
      ourMap.put(C_SYMBOL, CSymbolImpl.class);
      ourMap.put(C_VEC, CVecImpl.class);
    }
  }
}
