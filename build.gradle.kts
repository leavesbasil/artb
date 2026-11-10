plugins {
    id("org.springframework.boot") version "4.1.2" apply false
}

val springBootVersion = "4.1.2"
val springAiVersion = "2.0.2"
val testcontainersVersion = "2.0.5"

subprojects {
    apply(plugin = "java-library")

    group = "artb.ads"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "implementation"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
        "implementation"(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Тесты на живом Claude CLI: ./gradlew test -PllmCli
            if (!project.hasProperty("llmCli")) {
                excludeTags("llm-cli")
            }
        }
        systemProperty("file.encoding", "UTF-8")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Скачивает все зависимости заранее (удобно перед работой офлайн): ./gradlew resolveDependencies
tasks.register("resolveDependencies") {
    val configs = subprojects.flatMap { p ->
        listOf("runtimeClasspath", "testRuntimeClasspath").mapNotNull { p.configurations.findByName(it) }
    }
    doLast { configs.forEach { it.resolve() } }
}
