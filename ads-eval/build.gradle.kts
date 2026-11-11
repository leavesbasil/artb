dependencies {
    api(project(":ads-matching"))
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    runtimeOnly("ch.qos.logback:logback-classic")
}

// Прогон golden set на живых моделях (только синтетические данные). По умолчанию — GigaChat API:
// ключ авторизации в GIGACHAT_CREDENTIALS (в окружении или в файле .env в корне, он в .gitignore).
//   ./gradlew :ads-eval:eval
//   ./gradlew :ads-eval:eval -Pmodel=GigaChat-2-Pro -PjudgeModel=GigaChat-2-Max -Pembedding=EmbeddingsGigaR
// Через llm-cli-proxy (сейчас не используется):
//   ./gradlew :ads-eval:eval -Pprovider=internal -Pmodel=claude/haiku
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Прогон golden set и отчёт по метрикам"
    classpath = project.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("artb.ads.eval.EvalMain")
    workingDir = rootDir
    listOf("provider", "baseUrl", "model", "judgeModel", "embedding", "recording", "parallelism", "only").forEach { key ->
        project.findProperty(key)?.let { systemProperty("eval.$key", it) }
    }
    val dotenv = rootDir.resolve(".env")
    if (dotenv.isFile) {
        dotenv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                if (System.getenv(key) == null) environment(key, line.substringAfter("=").trim())
            }
    }
}
