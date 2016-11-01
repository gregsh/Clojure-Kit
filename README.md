
Clojure-Kit [[stable]](../../releases) [[dev]](https://teamcity.jetbrains.com/guestAuth/app/rest/builds/buildType:IntellijIdeaPlugins_ClojureKit_Build,status:SUCCESS/artifacts/content/ClojureKit.zip-*.zip)
==================
[![Build Status](https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:IntellijIdeaPlugins_ClojureKit_Build)/statusIcon.svg?guest=1)](https://teamcity.jetbrains.com/viewType.html?buildTypeId=IntellijIdeaPlugins_ClojureKit_Build&guest=1)
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

Clojure and ClojureScript [plugin](https://plugins.jetbrains.com/plugin/8636) for IntelliJ-based tools.

Compatible with versions 2016.2 and up, and:
* Written in [Kotlin](https://github.com/JetBrains/kotlin) with [Grammar-Kit](https://github.com/JetBrains/Grammar-Kit)
* Adds color options, basic formatter, documentation, structure view and breadcrumbs   
* Provides *Analyze data flow to/from here*   
* Features basic [Leiningen](https://github.com/technomancy/leiningen) and [Boot](https://github.com/boot-clj/boot) support
* Aims to be as zero configuration as possible
* Plays nice with Clojure *IDE Scripting Console*


Change log
==========
0.4.4

* ClojureScript: resolve/find ::aliased/keywords
* Editor: (comment), #_ and quoted-symbol literal coloring
* REPL: detect running nrepl via .nrepl-port file

0.4.3

* Language: basic language support for Clojure and ClojureScript
* Editor: colors, completion, navigation, parameter info, quickdoc and live templates
* CodeInsight: *Analyze data flow to/from here*
* Structural editing: slurp/barf/splice/rise/kill and smart delete/backspace
* Leiningen/Boot: resolve project dependencies to local *~/.m2/* repository
* REPL: *lein* and *boot* REPLs
