0.7.4-snapshot

* Debugger: initial java debugger integration
* QuickDoc: fix specs and show macroexpand from repl
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
