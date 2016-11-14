// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilder.Marker;
import static org.intellij.clojure.psi.ClojureTypes.*;
import static com.intellij.lang.parser.GeneratedParserUtilBase.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.TokenSet;
import com.intellij.lang.PsiParser;
import com.intellij.lang.LightPsiParser;
import static org.intellij.clojure.parser.ClojureParserUtil.adapt_builder_;
import static org.intellij.clojure.parser.ClojureParserUtil.parseTree;
import static org.intellij.clojure.parser.ClojureParserUtil.nospace;
import static org.intellij.clojure.parser.ClojureParserUtil.formRecover;

@SuppressWarnings({"SimplifiableIfStatement", "UnusedAssignment"})
public class ClojureParser implements PsiParser, LightPsiParser {

  public ASTNode parse(IElementType t, PsiBuilder b) {
    parseLight(t, b);
    return b.getTreeBuilt();
  }

  public void parseLight(IElementType t, PsiBuilder b) {
    boolean r;
    b = adapt_builder_(t, b, this, EXTENDS_SETS_);
    Marker m = enter_section_(b, 0, _COLLAPSE_, null);
    if (t == C_CONSTRUCTOR) {
      r = constructor(b, 0);
    }
    else if (t == C_FORM) {
      r = form(b, 0);
    }
    else if (t == C_FUN) {
      r = fun(b, 0);
    }
    else if (t == C_KEYWORD) {
      r = keyword(b, 0);
    }
    else if (t == C_LIST) {
      r = list(b, 0);
    }
    else if (t == C_LITERAL) {
      r = literal(b, 0);
    }
    else if (t == C_MAP) {
      r = map(b, 0);
    }
    else if (t == C_METADATA) {
      r = metadata(b, 0);
    }
    else if (t == C_READER_MACRO) {
      r = reader_macro(b, 0);
    }
    else if (t == C_REGEXP) {
      r = regexp(b, 0);
    }
    else if (t == C_SET) {
      r = set(b, 0);
    }
    else if (t == C_SYMBOL) {
      r = symbol(b, 0);
    }
    else if (t == C_VEC) {
      r = vec(b, 0);
    }
    else {
      r = parse_root_(t, b, 0);
    }
    exit_section_(b, 0, m, t, r, true, TRUE_CONDITION);
  }

  protected boolean parse_root_(IElementType t, PsiBuilder b, int l) {
    return root(b, l + 1);
  }

  public static final TokenSet[] EXTENDS_SETS_ = new TokenSet[] {
    create_token_set_(C_CONSTRUCTOR, C_FORM, C_FUN, C_KEYWORD,
      C_LIST, C_LITERAL, C_MAP, C_REGEXP,
      C_SET, C_SYMBOL, C_VEC),
  };

  /* ********************************************************** */
  // &('::' sym)
  static boolean alias_condition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "alias_condition")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _AND_);
    r = alias_condition_0(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // '::' sym
  private static boolean alias_condition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "alias_condition_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, C_COLONCOLON, C_SYM);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // '#' symbol (vec | map)
  public static boolean constructor(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "constructor")) return false;
    if (!nextTokenIs(b, C_SHARP)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_CONSTRUCTOR, "<form>");
    r = consumeToken(b, C_SHARP);
    p = r; // pin = 1
    r = r && report_error_(b, symbol(b, l + 1));
    r = p && constructor_2(b, l + 1) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // vec | map
  private static boolean constructor_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "constructor_2")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = vec(b, l + 1);
    if (!r) r = map(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // form_prefix form_prefix * form_upper | form_inner
  public static boolean form(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_, C_FORM, "<form>");
    r = form_0(b, l + 1);
    if (!r) r = form_inner(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // form_prefix form_prefix * form_upper
  private static boolean form_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form_0")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = form_prefix(b, l + 1);
    p = r; // pin = 1
    r = r && report_error_(b, form_0_1(b, l + 1));
    r = p && form_upper(b, l + 1) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // form_prefix *
  private static boolean form_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form_0_1")) return false;
    int c = current_position_(b);
    while (true) {
      if (!form_prefix(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "form_0_1", c)) break;
      c = current_position_(b);
    }
    return true;
  }

  /* ********************************************************** */
  // p_forms
  //   | s_forms | constructor
  static boolean form_inner(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form_inner")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = p_forms(b, l + 1);
    if (!r) r = s_forms(b, l + 1);
    if (!r) r = constructor(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // metadata | reader_macro
  static boolean form_prefix(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form_prefix")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = metadata(b, l + 1);
    if (!r) r = reader_macro(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // form_inner
  public static boolean form_upper(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "form_upper")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_ | _UPPER_, C_FORM, "<form>");
    r = form_inner(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // '#' <<nospace>> '(' list_body ')'
  public static boolean fun(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "fun")) return false;
    if (!nextTokenIs(b, C_SHARP)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_FUN, null);
    r = consumeToken(b, C_SHARP);
    r = r && nospace(b, l + 1);
    r = r && consumeToken(b, C_PAREN1);
    p = r; // pin = '[\(\[\{]'
    r = r && report_error_(b, list_body(b, l + 1));
    r = p && consumeToken(b, C_PAREN2) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // <<items_entry <<recover>> <<param>>>> *
  static boolean items(PsiBuilder b, int l, Parser _recover, Parser _param) {
    if (!recursion_guard_(b, l, "items")) return false;
    Marker m = enter_section_(b, l, _NONE_);
    int c = current_position_(b);
    while (true) {
      if (!items_entry(b, l + 1, _recover, _param)) break;
      if (!empty_element_parsed_guard_(b, "items", c)) break;
      c = current_position_(b);
    }
    exit_section_(b, l, m, true, false, _recover);
    return true;
  }

  /* ********************************************************** */
  // (not_eof <<recover>>) <<param>>
  static boolean items_entry(PsiBuilder b, int l, Parser _recover, Parser _param) {
    if (!recursion_guard_(b, l, "items_entry")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = items_entry_0(b, l + 1, _recover);
    p = r; // pin = 1
    r = r && _param.parse(b, l);
    exit_section_(b, l, m, r, p, form_recover_parser_);
    return r || p;
  }

  // not_eof <<recover>>
  private static boolean items_entry_0(PsiBuilder b, int l, Parser _recover) {
    if (!recursion_guard_(b, l, "items_entry_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = not_eof(b, l + 1);
    r = r && _recover.parse(b, l);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // (':' | '::') <<nospace>> symbol_qualified
  public static boolean keyword(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "keyword")) return false;
    if (!nextTokenIs(b, "<keyword>", C_COLON, C_COLONCOLON)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, C_KEYWORD, "<keyword>");
    r = keyword_0(b, l + 1);
    r = r && nospace(b, l + 1);
    r = r && symbol_qualified(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ':' | '::'
  private static boolean keyword_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "keyword_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_COLON);
    if (!r) r = consumeToken(b, C_COLONCOLON);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // '(' list_body ')'
  public static boolean list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "list")) return false;
    if (!nextTokenIs(b, C_PAREN1)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_LIST, null);
    r = consumeToken(b, C_PAREN1);
    p = r; // pin = '[\(\[\{]'
    r = r && report_error_(b, list_body(b, l + 1));
    r = p && consumeToken(b, C_PAREN2) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // <<items !')' form>>
  static boolean list_body(PsiBuilder b, int l) {
    return items(b, l + 1, list_body_0_0_parser_, form_parser_);
  }

  // !')'
  private static boolean list_body_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "list_body_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !consumeToken(b, C_PAREN2);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // number | hexnum | rdxnum | ratio | bool | nil | string | char
  public static boolean literal(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "literal")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, C_LITERAL, "<literal>");
    r = consumeToken(b, C_NUMBER);
    if (!r) r = consumeToken(b, C_HEXNUM);
    if (!r) r = consumeToken(b, C_RDXNUM);
    if (!r) r = consumeToken(b, C_RATIO);
    if (!r) r = consumeToken(b, C_BOOL);
    if (!r) r = consumeToken(b, C_NIL);
    if (!r) r = consumeToken(b, C_STRING);
    if (!r) r = consumeToken(b, C_CHAR);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // '{' map_body '}'
  public static boolean map(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map")) return false;
    if (!nextTokenIs(b, C_BRACE1)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_MAP, null);
    r = consumeToken(b, C_BRACE1);
    p = r; // pin = '[\(\[\{]'
    r = r && report_error_(b, map_body(b, l + 1));
    r = p && consumeToken(b, C_BRACE2) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // <<items !'}' map_entry>>
  static boolean map_body(PsiBuilder b, int l) {
    return items(b, l + 1, map_body_0_0_parser_, map_entry_parser_);
  }

  // !'}'
  private static boolean map_body_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_body_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !consumeToken(b, C_BRACE2);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // form form
  static boolean map_entry(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_entry")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = form(b, l + 1);
    p = r; // pin = 1
    r = r && form(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // "#:" (':' <<nospace>> symbol_plain
  //   | alias_condition '::' <<nospace>> symbol_plain
  //   | '::' ) &'{'
  static boolean map_ns_prefix(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_ns_prefix")) return false;
    if (!nextTokenIs(b, C_SHARP_NS)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = consumeToken(b, C_SHARP_NS);
    p = r; // pin = 1
    r = r && report_error_(b, map_ns_prefix_1(b, l + 1));
    r = r && map_ns_prefix_2(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // ':' <<nospace>> symbol_plain
  //   | alias_condition '::' <<nospace>> symbol_plain
  //   | '::'
  private static boolean map_ns_prefix_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_ns_prefix_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = map_ns_prefix_1_0(b, l + 1);
    if (!r) r = map_ns_prefix_1_1(b, l + 1);
    if (!r) r = consumeToken(b, C_COLONCOLON);
    exit_section_(b, m, null, r);
    return r;
  }

  // ':' <<nospace>> symbol_plain
  private static boolean map_ns_prefix_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_ns_prefix_1_0")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = consumeToken(b, C_COLON);
    p = r; // pin = 1
    r = r && report_error_(b, nospace(b, l + 1));
    r = p && symbol_plain(b, l + 1) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // alias_condition '::' <<nospace>> symbol_plain
  private static boolean map_ns_prefix_1_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_ns_prefix_1_1")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = alias_condition(b, l + 1);
    p = r; // pin = 1
    r = r && report_error_(b, consumeToken(b, C_COLONCOLON));
    r = p && report_error_(b, nospace(b, l + 1)) && r;
    r = p && symbol_plain(b, l + 1) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // &'{'
  private static boolean map_ns_prefix_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "map_ns_prefix_2")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _AND_);
    r = consumeToken(b, C_BRACE1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // ("^" | "#^") (string | symbol | keyword | map)
  public static boolean metadata(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "metadata")) return false;
    if (!nextTokenIs(b, "<form>", C_SHARP_HAT, C_HAT)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, C_METADATA, "<form>");
    r = metadata_0(b, l + 1);
    r = r && metadata_1(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // "^" | "#^"
  private static boolean metadata_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "metadata_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_HAT);
    if (!r) r = consumeToken(b, C_SHARP_HAT);
    exit_section_(b, m, null, r);
    return r;
  }

  // string | symbol | keyword | map
  private static boolean metadata_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "metadata_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_STRING);
    if (!r) r = symbol(b, l + 1);
    if (!r) r = keyword(b, l + 1);
    if (!r) r = map(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // !<<eof>>
  static boolean not_eof(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "not_eof")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !eof(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // list | set | vec | map | fun
  static boolean p_forms(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "p_forms")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = list(b, l + 1);
    if (!r) r = set(b, l + 1);
    if (!r) r = vec(b, l + 1);
    if (!r) r = map(b, l + 1);
    if (!r) r = fun(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // ('#?' | '#?@') &'('
  static boolean reader_cond(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "reader_cond")) return false;
    if (!nextTokenIs(b, "<form>", C_SHARP_QMARK, C_SHARP_QMARK_AT)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, null, "<form>");
    r = reader_cond_0(b, l + 1);
    p = r; // pin = 1
    r = r && reader_cond_1(b, l + 1);
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // '#?' | '#?@'
  private static boolean reader_cond_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "reader_cond_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_SHARP_QMARK);
    if (!r) r = consumeToken(b, C_SHARP_QMARK_AT);
    exit_section_(b, m, null, r);
    return r;
  }

  // &'('
  private static boolean reader_cond_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "reader_cond_1")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _AND_);
    r = consumeToken(b, C_PAREN1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // "'" | "~" | "~@" | "@" | "`" |  "#_" | "#'" | "#=" | reader_cond | map_ns_prefix
  public static boolean reader_macro(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "reader_macro")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, C_READER_MACRO, "<form>");
    r = consumeToken(b, C_QUOTE);
    if (!r) r = consumeToken(b, C_TILDE);
    if (!r) r = consumeToken(b, C_TILDE_AT);
    if (!r) r = consumeToken(b, C_AT);
    if (!r) r = consumeToken(b, C_SYNTAX_QUOTE);
    if (!r) r = consumeToken(b, C_SHARP_COMMENT);
    if (!r) r = consumeToken(b, C_SHARP_QUOTE);
    if (!r) r = consumeToken(b, C_SHARP_EQ);
    if (!r) r = reader_cond(b, l + 1);
    if (!r) r = map_ns_prefix(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // '#' <<nospace>> string
  public static boolean regexp(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "regexp")) return false;
    if (!nextTokenIs(b, C_SHARP)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_SHARP);
    r = r && nospace(b, l + 1);
    r = r && consumeToken(b, C_STRING);
    exit_section_(b, m, C_REGEXP, r);
    return r;
  }

  /* ********************************************************** */
  // <<parseTree (root_entry)>>
  static boolean root(PsiBuilder b, int l) {
    return parseTree(b, l + 1, root_0_0_parser_);
  }

  // (root_entry)
  private static boolean root_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "root_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = root_entry(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // not_eof form
  static boolean root_entry(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "root_entry")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = not_eof(b, l + 1);
    p = r; // pin = 1
    r = r && form(b, l + 1);
    exit_section_(b, l, m, r, p, root_entry_recover_parser_);
    return r || p;
  }

  /* ********************************************************** */
  // form_recover | ')' | '}' | ']'
  static boolean root_entry_recover(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "root_entry_recover")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = formRecover(b, l + 1);
    if (!r) r = consumeToken(b, C_PAREN2);
    if (!r) r = consumeToken(b, C_BRACE2);
    if (!r) r = consumeToken(b, C_BRACKET2);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // symbol | keyword | literal | regexp
  static boolean s_forms(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "s_forms")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = symbol(b, l + 1);
    if (!r) r = keyword(b, l + 1);
    if (!r) r = literal(b, l + 1);
    if (!r) r = regexp(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // '#' '{' set_body '}'
  public static boolean set(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "set")) return false;
    if (!nextTokenIs(b, C_SHARP)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_SET, null);
    r = consumeTokens(b, 2, C_SHARP, C_BRACE1);
    p = r; // pin = '[\(\[\{]'
    r = r && report_error_(b, set_body(b, l + 1));
    r = p && consumeToken(b, C_BRACE2) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // <<items !'}' form>>
  static boolean set_body(PsiBuilder b, int l) {
    return items(b, l + 1, set_body_0_0_parser_, form_parser_);
  }

  // !'}'
  private static boolean set_body_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "set_body_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !consumeToken(b, C_BRACE2);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // '/' | ('.' | '.-') sym? | symbol_qualified '.'?
  public static boolean symbol(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_, C_SYMBOL, "<symbol>");
    r = consumeToken(b, C_SLASH);
    if (!r) r = symbol_1(b, l + 1);
    if (!r) r = symbol_2(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ('.' | '.-') sym?
  private static boolean symbol_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = symbol_1_0(b, l + 1);
    r = r && symbol_1_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // '.' | '.-'
  private static boolean symbol_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_DOT);
    if (!r) r = consumeToken(b, C_DOTDASH);
    exit_section_(b, m, null, r);
    return r;
  }

  // sym?
  private static boolean symbol_1_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_1_1")) return false;
    consumeToken(b, C_SYM);
    return true;
  }

  // symbol_qualified '.'?
  private static boolean symbol_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_2")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = symbol_qualified(b, l + 1);
    r = r && symbol_2_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // '.'?
  private static boolean symbol_2_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_2_1")) return false;
    consumeToken(b, C_DOT);
    return true;
  }

  /* ********************************************************** */
  // '/' sym
  public static boolean symbol_nsq(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_nsq")) return false;
    if (!nextTokenIs(b, C_SLASH)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _LEFT_, C_SYMBOL, null);
    r = consumeTokens(b, 0, C_SLASH, C_SYM);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // sym
  public static boolean symbol_plain(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_plain")) return false;
    if (!nextTokenIs(b, C_SYM)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, C_SYM);
    exit_section_(b, m, C_SYMBOL, r);
    return r;
  }

  /* ********************************************************** */
  // symbol_plain symbol_nsq?
  static boolean symbol_qualified(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_qualified")) return false;
    if (!nextTokenIs(b, C_SYM)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = symbol_plain(b, l + 1);
    r = r && symbol_qualified_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // symbol_nsq?
  private static boolean symbol_qualified_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "symbol_qualified_1")) return false;
    symbol_nsq(b, l + 1);
    return true;
  }

  /* ********************************************************** */
  // '[' vec_body ']'
  public static boolean vec(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "vec")) return false;
    if (!nextTokenIs(b, C_BRACKET1)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, C_VEC, null);
    r = consumeToken(b, C_BRACKET1);
    p = r; // pin = '[\(\[\{]'
    r = r && report_error_(b, vec_body(b, l + 1));
    r = p && consumeToken(b, C_BRACKET2) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  /* ********************************************************** */
  // <<items !']' form>>
  static boolean vec_body(PsiBuilder b, int l) {
    return items(b, l + 1, vec_body_0_0_parser_, form_parser_);
  }

  // !']'
  private static boolean vec_body_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "vec_body_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !consumeToken(b, C_BRACKET2);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  final static Parser form_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return form(b, l + 1);
    }
  };
  final static Parser form_recover_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return formRecover(b, l + 1);
    }
  };
  final static Parser list_body_0_0_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return list_body_0_0(b, l + 1);
    }
  };
  final static Parser map_body_0_0_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return map_body_0_0(b, l + 1);
    }
  };
  final static Parser map_entry_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return map_entry(b, l + 1);
    }
  };
  final static Parser root_0_0_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return root_0_0(b, l + 1);
    }
  };
  final static Parser root_entry_recover_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return root_entry_recover(b, l + 1);
    }
  };
  final static Parser set_body_0_0_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return set_body_0_0(b, l + 1);
    }
  };
  final static Parser vec_body_0_0_parser_ = new Parser() {
    public boolean parse(PsiBuilder b, int l) {
      return vec_body_0_0(b, l + 1);
    }
  };
}
