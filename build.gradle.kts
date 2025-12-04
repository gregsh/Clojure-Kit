import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

val pluginName: String by project
val pluginVersion: String by project
val pluginSinceIdeaBuild: String by project

val ideVersion: String by project
val javaVersion: String by project
val clojureVersion: String by project
val cljsVersion: String by project

val artifactsPath: String by project
val ourGradleVersion: String by project

plugins {
  id("org.jetbrains.intellij.platform") version "2.10.5"
  id("org.jetbrains.changelog") version "2.5.0"
  id("idea")
  id("java")
  kotlin("jvm") version "2.3.0"
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.valueOf("JVM_$javaVersion")
    apiVersion = KotlinVersion.KOTLIN_2_1
  }
}

tasks.withType<JavaCompile> {
  sourceCompatibility = javaVersion
  targetCompatibility = javaVersion
}

version = pluginVersion

repositories {
  mavenCentral()
  maven { url = uri("https://clojars.org/repo") }
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    intellijIdeaCommunity(ideVersion)
    bundledPlugin("com.intellij.copyright")
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
  testRuntimeOnly("org.clojure:clojure:$clojureVersion")
  testRuntimeOnly("org.clojure:clojurescript:$cljsVersion")
}

idea {
  module {
    generatedSourceDirs.add(file("gen"))
  }
}

intellijPlatform {
  pluginConfiguration {
    name = pluginName
    version = ideVersion
    ideaVersion {
      sinceBuild = pluginSinceIdeaBuild
    }}
}

changelog {
  header.set("${{ -> version.get() }}")
  headerParserRegex.set("""(\d+(\.\d+)+)""")
  itemPrefix.set("*")
  unreleasedTerm.set("Unreleased")
}

val buildClojureKitJar = tasks.register<Jar>("buildClojureKitJar") {
  dependsOn("assemble")
  archiveBaseName.set("Clojure-kit")
  destinationDirectory.set(file(artifactsPath))
  manifest {
    from("$rootDir/resources/META-INF/MANIFEST.MF")
  }
  from(sourceSets.main.get().output)
}

tasks {

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
    changeNotes = provider {
      changelog.renderItem(
        changelog
          .getUnreleased()
          .withHeader(false)
          .withEmptySections(false),
        Changelog.OutputType.HTML
      ) + "<a href=\"https://github.com/gregsh/Clojure-Kit/blob/master/CHANGELOG.md\">Full change log...</a>"
    }
  }

  val buildClojureKitZip = register<Zip>("buildClojureKitZip") {
    dependsOn(buildClojureKitJar)
    archiveBaseName.set("ClojureKit")
    destinationDirectory.set(file(artifactsPath))
    from(buildClojureKitJar.get().outputs) {
      into("/ClojureKit/lib")
    }
  }

  register("artifacts") {
    dependsOn(buildClojureKitJar, buildClojureKitZip)
  }

  defaultTasks("clean", "artifacts", "test")
}