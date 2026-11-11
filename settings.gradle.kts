plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "artb-ads"

include(
    "ads-llm",
    "ads-campaign",
    "ads-targeting",
    "ads-ranking",
    "ads-matching",
    "ads-delivery",
    "ads-eval",
    "ads-demo",
)
