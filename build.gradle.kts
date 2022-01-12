import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = project.findProperty(key)?.toString()

plugins {
  id("org.jetbrains.intellij") version "1.2.1"
  id("org.jetbrains.changelog") version "1.3.1"
  id("idea")
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.4.10"
}

version = properties("pluginVersion").toString()

repositories {
  mavenCentral()
  maven { url = uri("https://clojars.org/repo") }
}

dependencies {
  compileOnly("org.jetbrains:annotations:22.0.0")
  testRuntimeOnly("org.clojure:clojure:${properties("clojureVersion")}")
  testRuntimeOnly("org.clojure:clojurescript:${properties("cljsVersion")}")
  compileOnly(files("${System.getProperties()["java.home"]}/../lib/tools.jar"))
}

idea {
  module {
    generatedSourceDirs.add(file("gen"))
  }
}

intellij {
  pluginName.set(properties("pluginName"))
  version.set(properties("ideaVersion"))
  type.set("IC")
  plugins.set(listOf("copyright", "java"))
  updateSinceUntilBuild.set(false)
}

changelog {
  header.set("${{ -> version.get() }}")
  headerParserRegex.set("""(\d+(\.\d+)+)""")
  itemPrefix.set("*")
  unreleasedTerm.set("Unreleased")
}

val artifactsPath = properties("artifactsPath").toString()

val buildClojureKitJar = tasks.create<Jar>("buildClojureKitJar") {
  dependsOn("assemble")
  archiveBaseName.set("Clojure-kit")
  destinationDirectory.set(file(artifactsPath))
  manifest {
    from("$rootDir/resources/META-INF/MANIFEST.MF")
  }
  from(sourceSets.main.get().output)
}

tasks {
  withType<JavaCompile> {
    sourceCompatibility = properties("javaVersion")
    targetCompatibility = properties("javaVersion")
  }

  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = properties("javaVersion")!!
      apiVersion = properties("kotlinApiVersion")!!
    }
  }

  withType<Test> {
    useJUnit()
    include("**/*Test.class")
    isScanForTestClasses = false
    ignoreFailures = true
  }

  withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
      addStringOption("Xdoclint:none", "-quiet")
    }
  }

  wrapper {
    gradleVersion = properties("gradleVersion")
  }

  sourceSets {
    main {
      java.srcDirs("src", "gen")
      resources.srcDirs("resources")
    }
    test {
      java.srcDirs("tests")
    }
  }

  buildSearchableOptions {
    enabled = false
  }

  patchPluginXml {
    sinceBuild.set(properties("pluginSinceIdeaBuild"))
    changeNotes.set(provider {
      changelog.run {
        getOrNull(project.version.toString()) ?: getLatest()
      }.toHTML() + "<a href=\"https://github.com/gregsh/Clojure-Kit/blob/master/CHANGELOG.md\">Full change log...</a>"
    })
  }

  val buildClojureKitZip = create<Zip>("buildClojureKitZip") {
    dependsOn(buildClojureKitJar)
    archiveBaseName.set("ClojureKit")
    destinationDirectory.set(file(artifactsPath))
    from(buildClojureKitJar.outputs) {
      into("/ClojureKit/lib")
    }
  }

  create("artifacts") {
    dependsOn(buildClojureKitJar, buildClojureKitZip)
  }

  defaultTasks("clean", "artifacts", "test")
}