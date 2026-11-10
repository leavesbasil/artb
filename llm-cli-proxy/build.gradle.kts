apply(plugin = "org.springframework.boot")

// Локальный прокси: OpenAI-совместимый API поверх Claude Code CLI и Qwen Code CLI.
// Основное приложение ходит в него как в обычный OpenAI-совместимый сервер и о CLI не знает.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Тест совместимости: настоящий клиент Spring AI OpenAI против прокси.
    testImplementation("org.springframework.ai:spring-ai-openai")
}
