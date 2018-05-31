
Clojure-Kit 
==================
[![Build Status](https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:IntellijIdeaPlugins_ClojureKit_Build)/statusIcon.svg?guest=1)](https://teamcity.jetbrains.com/viewType.html?buildTypeId=IntellijIdeaPlugins_ClojureKit_Build&guest=1)
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

Clojure and ClojureScript [plugin](https://plugins.jetbrains.com/plugin/8636) for IntelliJ-based tools.

Quick links: [Latest dev build](https://teamcity.jetbrains.com/guestAuth/app/rest/builds/buildType:IntellijIdeaPlugins_ClojureKit_Build,status:SUCCESS/artifacts/content/ClojureKit*.zip),
[Changelog](CHANGELOG.md).


* Aims to be as zero configuration as possible
* Adds color options, basic formatter, documentation, structure view and breadcrumbs   
* Provides `Analyze data flow [to | from] here` functionality   
* Features basic [Leiningen](https://github.com/technomancy/leiningen) and [Boot](https://github.com/boot-clj/boot) support
* Plays nice with `IDE Scripting Console`
* Written in [Kotlin](https://github.com/JetBrains/kotlin) with [Grammar-Kit](https://github.com/JetBrains/Grammar-Kit)

FAQ
==========

Q. How to install the plugin?<br/>
A. Go to `Settings | Plugins`, press `Browse Repositories` button, search for `Clojure-Kit`, install, relaunch.

Q. How to open my project?<br/>
A. Use whichever way IDE provides to add a project directory to **Project View**.

Q. Where are my dependencies?<br/>
A. Dependencies are collected from `lein` or `boot` on project load. There's no dedicated UI to view or configure them.
   Use **Sync [All] Dependencies** actions for the corresponding `project.clj` or `build.boot`
   available in editor floating toolbar (upper-right corner), **Project View** and **Goto Action** popup (`ctrl-shift-A` or `cmd-shift-A`).
    
Q. How to launch REPL?<br/>
A. Use **Execute in REPL** action (`ctrl-enter` or `cmd-enter`) to send the selected text or a form under caret to REPL.
   Either a new or existing nREPL server console UI will show up. Then forms can be sent right from project files or the console editor. 
      
Q. How to connect to remote REPL or ClojureScript REPL on a different port?<br/>
A. Use **Connect to REPL** action (`ctrl-shift-P` or `cmd-shift-P`) to enter host, port or nrepl URL and create a new remote console. 
Remote consoles are not mapped to project files, use **Exclusive Mode** toolbar toggle or popup (`ctrl-shift-L` or `cmd-shift-L`)
to redirect all commands to one specific REPL.
