dependencies {
    api("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.ai:spring-ai-openai")
    // GigaChat API: OAuth с обновлением токена, TLS с сертификатом Минцифры, чат и эмбеддинги.
    implementation("chat.giga:spring-ai-gigachat:2.0.2")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-json")
    api("org.slf4j:slf4j-api")
}
