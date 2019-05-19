0.7.6

* resolve: fix qualified :keys destructuring
* resolve: support this in (reify), (proxy) and (extend)
* resolve: support (..), (->) and (doto) w/ and w/o parens
* resolve: skip not quoted lists in ns elements
* deps: notify if tool process failed to start
* debugger: handle munged names in evaluator
* debugger: workaround missing autoboxing for primitives eval
* resolve: force JVM name for inner classes; fix (.method class)
* resolve: better dynamic condition in . and ..
* resolve: avoid resetting RESOLVE_SKIPPED in parallel
* rename: fix extra space on rename (sym.)
* rename: handle ::keys destructuring bindings
* resolve: fix alias and some.alias confusion
* resolve: clarify (.call obj) vs (call obj) in various cases
* resolve: support mixed PSI/ASM class hierarchies
* lexer: fix BigInteger numbers and octal chars literals
* lexer: improve bad literals handling
* resolve: improve qualified tags handling
* tools: update nREPL dependencies #30

0.7.5

* Editor: semantic highlighting (rainbow symbols)
* Resolve: resolve java methods from super-interfaces
* Resolve: resolve defrecord, deftype methods and fields
* Usages: search for keywords and namespaces in whole project
* Dependencies: support *.edn files
* Dependencies: show in Project | External Libraries
* Parser: fix #_, (a.b/c.) and \uNNNN parsing
* REPL: fix execution from editor
* UI: new SVG icons

0.7.4

* Debugger: initial java debugger integration
* QuickDoc: fix specs and show macroexpand from repl
* QuickDoc: show info for syntax tokens, e.g. ~@, #^, etc.
* Editor: Context Info and Move Left/Right actions
* Editor: data-reader & name declaration colors
* Editor: support custom folding regions
* Clojure: better support for #_ forms

0.7.3

* REPL: stdin/stdout/stderr support
* REPL: use file ns when evaluating from a file
* Editor: Copy Reference action
* Editor: splice at caret position
* Editor: error recovery in case of extra closing paren

0.7.2

* IntelliJ Platform 2018.1 API compatibility
* Clojure 1.9.0 compatibility (##Inf, ##-Inf, ##NaN) 

0.7.1

* IntelliJ Platform 2017.2.5 and 2017.3 API compatibility
* Misc: use the latest versions of Kotlin & Gradle
  
0.7.0

* Editor: multi-method & protocol method navigation (`ctrl-U`, `ctrl-alt-B`)
* Editor: parameter info inlays; expression type hint
* Formatter: follow bbatsov style guide
* Code style: ;; (double-semicolon) commenting toggle
* QuickDoc: search and display related specs
* REPL: the first repl gets exclusive mode by default
* Internals: redesigned AST/PSI, name resolution, indices and etc.

0.5.0

* REPL: connect to remote REPL
* REPL: send all commands to one specific REPL
* REPL: console history actions
* Editor: complete namespaces and keywords
* Structural editing: improved caret handling
* Main menu: add clojure actions to Edit and Tools

0.4.4

* Editor: improved structural editing actions
* Editor: more items in structure view and breadcrumbs
* Editor: some default colors for **Default** and **Darcula** color schemes
* Editor: `(comment)`, `#_` and quoted-symbol literal coloring
* ClojureScript: resolve and usage search for `::aliased/keywords`
* REPL: detect running nrepl via `.nrepl-port` file

0.4.3

* Language: basic language support for Clojure and ClojureScript
* Editor: colors, completion, navigation, parameter info, quickdoc and live templates
* Code Insight: `Analyze data flow [to | from] here`
* Structural editing: `slurp`, `barf`, `splice`, `rise`, `kill` and smart `delete`, `backspace`
* Dependencies: resolve `lein` and `boot` projects dependencies to `~/.m2/` repository
* REPL: `lein` and `boot` supported
