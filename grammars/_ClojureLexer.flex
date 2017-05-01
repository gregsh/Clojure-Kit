package org.intellij.clojure.parser;

import com.intellij.lang.Language;
import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import org.intellij.clojure.lang.ClojureScriptLanguage;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.intellij.clojure.psi.ClojureTypes.*;
import static org.intellij.clojure.lang.ClojureTokens.LINE_COMMENT;

%%

%{
  private Language myLanguage;

  public _ClojureLexer(Language language) {
    myLanguage = language;
  }
%}

%public
%class _ClojureLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

%state SYMBOL0, SYMBOL1, SYMBOL2
%state DISPATCH

WHITE_SPACE=\s+
LINE_COMMENT=;.*
STR_CHAR=[^\\\"]|\\.|\\\"
STRING=\" {STR_CHAR}* \"
STRING_UNCLOSED=\" {STR_CHAR}*
NUMBER=[+-]? [0-9]+(\.[0-9]*)?([eE][+-]?[0-9]+)?M?
HEXNUM=[+-]? "0x" [\da-fA-F]+
RADIX=[+-]? [0-9]{1,2}r[\da-zA-Z]+
RATIO=[+-]? [0-9]+"/"[0-9]+
CHARACTER=\\([btrnf]|u[0-9]{4}|backspace|tab|newline|formfeed|return|space|.)

SYM_START=[[\w<>$%&=*+\-!?_|]--#\d] | ".."
SYM_CHAR="."? [\w<>$%&=*+\-!?_|'#]
SYM_TAIL={SYM_CHAR}+ (":" {SYM_CHAR}+)?
SYM_CHAR2=[\w<>$%&=*+\-!?_|'#./]

%%
<YYINITIAL> {
  {WHITE_SPACE}          { return WHITE_SPACE; }
  {LINE_COMMENT}         { return LINE_COMMENT; }

  "#"                    { yybegin(DISPATCH); }

  "^"                    { return C_HAT; }
  "~@"                   { return C_TILDE_AT; }
  "~"                    { return C_TILDE; }
  "@"                    { return C_AT; }
  "("                    { return C_PAREN1; }
  ")"                    { return C_PAREN2; }
  "["                    { return C_BRACKET1; }
  "]"                    { return C_BRACKET2; }
  "{"                    { return C_BRACE1; }
  "}"                    { return C_BRACE2; }
  ","                    { return C_COMMA; }
  "'"                    { return C_QUOTE; }
  "`"                    { return C_SYNTAX_QUOTE; }

  "nil"                  { return C_NIL; }
  true|false             { return C_BOOL; }

  {STRING}               { return C_STRING; }
  {STRING_UNCLOSED}      { return C_STRING_UNCLOSED; }
  {NUMBER}               { return C_NUMBER; }
  {HEXNUM}               { return C_HEXNUM; }
  {RADIX}                { return C_RDXNUM; }
  {RATIO}                { return C_RATIO; }
  {CHARACTER}            { return C_CHAR; }

  "::"                   { yybegin(SYMBOL0); return C_COLONCOLON; }
  ":"                    { yybegin(SYMBOL0); return C_COLON; }
  ".-"  /  {SYM_CHAR}    { yybegin(SYMBOL0); return C_DOTDASH; }
  "."   /  {SYM_CHAR}    { yybegin(SYMBOL0); return C_DOT; }

  "/" {SYM_CHAR2} +      { yybegin(YYINITIAL); return BAD_CHARACTER; }
  ".-" | "." | "/"       { return C_SYM; }

  {SYM_START}{SYM_TAIL}? { yybegin(SYMBOL1); return C_SYM; }
}

<SYMBOL0> {
  {SYM_TAIL}             { yybegin(SYMBOL1); return C_SYM; }
  [^]                    { yybegin(YYINITIAL); yypushback(yylength()); }
}

<SYMBOL1> {
  ":"                    { yybegin(YYINITIAL); return BAD_CHARACTER; }
  "/"                    { yybegin(SYMBOL2); return C_SLASH; }
  "."                    { yybegin(YYINITIAL); return C_DOT; }
  [^]                    { yybegin(YYINITIAL); yypushback(yylength()); }
}

<SYMBOL2>  {
  ":"                    { yybegin(YYINITIAL); return BAD_CHARACTER; }
  "/" {SYM_CHAR2} +      { yybegin(YYINITIAL); return BAD_CHARACTER; }
  "/"                    { yybegin(YYINITIAL); return C_SYM; }
  "."                    { yybegin(YYINITIAL); return C_SYM; }
  {SYM_TAIL}             { yybegin(YYINITIAL); return C_SYM; }
  [^]                    { yybegin(YYINITIAL); yypushback(yylength()); }
}

<DISPATCH> {
  "^"                    { yybegin(YYINITIAL); return C_SHARP_HAT; }  // Meta
  "'"                    { yybegin(YYINITIAL); return C_SHARP_QUOTE; }  // Var
  "\""                   { yybegin(YYINITIAL); yypushback(1); return C_SHARP; }  // Regex
  "("                    { yybegin(YYINITIAL); yypushback(1); return C_SHARP; }  // Fn
  "{"                    { yybegin(YYINITIAL); yypushback(1); return C_SHARP; }  // Set
  "="                    { yybegin(YYINITIAL); return C_SHARP_EQ; }  // Eval
  "!"                    { yybegin(YYINITIAL); return C_SHARP_COMMENT; }  // Comment
  "<"                    { yybegin(YYINITIAL); return BAD_CHARACTER; }  // Unreadable
  "_"                    { yybegin(YYINITIAL); return C_SHARP_COMMENT; }  // Discard
  "?@"                   { yybegin(YYINITIAL); return C_SHARP_QMARK_AT; }  // Conditional w/ Splicing
  "?"                    { yybegin(YYINITIAL); return C_SHARP_QMARK; }  // Conditional
  ":"                    { yybegin(YYINITIAL); yypushback(yylength()); return C_SHARP_NS; }  // Map ns prefix
  [\s\w]                 { yybegin(YYINITIAL); yypushback(yylength()); return C_SHARP; }
  [^]                    { yybegin(YYINITIAL); yypushback(yylength()); return BAD_CHARACTER; }

  <<EOF>>                { yybegin(YYINITIAL); return BAD_CHARACTER; }
}

[^] { return BAD_CHARACTER; }
