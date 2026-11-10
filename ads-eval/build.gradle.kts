dependencies {
    api(project(":ads-matching"))
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    runtimeOnly("ch.qos.logback:logback-classic")
}

// Прогон golden set на живых моделях (только синтетические данные). Для CLI сначала запустите llm-cli-proxy:
//   ./gradlew :llm-cli-proxy:bootRun
//   ./gradlew :ads-eval:eval -Pmodel=claude/haiku -PjudgeModel=claude/sonnet -Precording=record
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Прогон golden set и отчёт по метрикам"
    classpath = project.the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("artb.ads.eval.EvalMain")
    workingDir = rootDir
    listOf("baseUrl", "model", "judgeModel", "recording", "parallelism", "only").forEach { key ->
        project.findProperty(key)?.let { systemProperty("eval.$key", it) }
    }
}
