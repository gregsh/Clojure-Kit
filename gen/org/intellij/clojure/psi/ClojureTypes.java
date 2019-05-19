// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import org.intellij.clojure.psi.impl.*;
import com.intellij.psi.impl.source.tree.CompositePsiElement;

public interface ClojureTypes {

  IElementType C_ACCESS = new ClojureNodeType("C_ACCESS");
  IElementType C_COMMENTED = new ClojureNodeType("C_COMMENTED");
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
  IElementType C_SHARP_SYM = new ClojureTokenType("##");
  IElementType C_SLASH = new ClojureTokenType("/");
  IElementType C_STRING = new ClojureTokenType("string");
  IElementType C_SYM = new ClojureTokenType("sym");
  IElementType C_SYNTAX_QUOTE = new ClojureTokenType("`");
  IElementType C_TILDE = new ClojureTokenType("~");
  IElementType C_TILDE_AT = new ClojureTokenType("~@");

  class Factory {
    public static CompositePsiElement createElement(IElementType type) {
       if (type == C_ACCESS) {
        return new CAccessImpl(type);
      }
      else if (type == C_COMMENTED) {
        return new CCommentedImpl(type);
      }
      else if (type == C_CONSTRUCTOR) {
        return new CConstructorImpl(type);
      }
      else if (type == C_FORM) {
        return new CFormImpl(type);
      }
      else if (type == C_FUN) {
        return new CFunImpl(type);
      }
      else if (type == C_KEYWORD) {
        return new CKeywordImpl(type);
      }
      else if (type == C_LIST) {
        return new CListImpl(type);
      }
      else if (type == C_LITERAL) {
        return new CLiteralImpl(type);
      }
      else if (type == C_MAP) {
        return new CMapImpl(type);
      }
      else if (type == C_METADATA) {
        return new CMetadataImpl(type);
      }
      else if (type == C_READER_MACRO) {
        return new CReaderMacroImpl(type);
      }
      else if (type == C_REGEXP) {
        return new CRegexpImpl(type);
      }
      else if (type == C_SET) {
        return new CSetImpl(type);
      }
      else if (type == C_SYMBOL) {
        return new CSymbolImpl(type);
      }
      else if (type == C_VEC) {
        return new CVecImpl(type);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
