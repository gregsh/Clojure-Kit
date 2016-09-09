
Clojure-Kit
==================
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

An [IntelliJ Platform plugin](http://plugins.jetbrains.com) for Clojure and ClojureScript.

Compatible with all IntelliJ-based products ver. 2016.2 and up, and:
* Written in [Kotlin](https://github.com/JetBrains/kotlin) with [Grammar-Kit](https://github.com/JetBrains/Grammar-Kit)
* Adds color options, basic formatter, documentation, structure view and breadcrumbs   
* Provides *Analyze data flow to/from here*   
* Features basic [Leiningen](https://github.com/technomancy/leiningen) and [Boot](https://github.com/boot-clj/boot) support
* Aims to be as zero configuration as possible
* Plays nice with Clojure *IDE Scripting Console*


Change log
==========
0.4.2 (initial)

* Language: basic language support for Clojure and ClojureScript
* Editor: colors, completion, navigation, parameter info, quickdoc and live templates
* CodeInsight: *Analyze data flow to/from here*
* Structural editing: slurp/barf/splice/rise/kill and smart delete/backspace
* Leiningen/Boot: resolve project dependencies to local *~/.m2/* repository
* REPL: *lein* and *boot* REPLs
