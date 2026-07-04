plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.ktor.plugin") version "3.5.0"
    application
}

group = "com.nervs.wantly"
version = "1.0.0"

application {
    mainClass.set("com.nervs.wantly.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// Flyway (и любые другие ServiceLoader-based библиотеки) обнаруживают свои
// реализации через META-INF/services. При сборке fat-jar без merge —
// несколько файлов с одним именем затирают друг друга → Flyway.configure().load()
// падает с "no database support" в java -jar, но работает в IDE/Gradle run.
// Ktor plugin применяет Shadow transitively; настраиваем merge.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    mergeServiceFiles()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-cors")

    // Database (Exposed 0.61 — стабильный API с org.jetbrains.exposed.sql)
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.61.0")
    implementation("org.postgresql:postgresql:42.7.11")

    // Connection pool
    implementation("com.zaxxer:HikariCP:6.2.0")

    // Миграции схемы (вместо SchemaUtils.create)
    implementation("org.flywaydb:flyway-core:11.7.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.7.0")

    // Auth
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("io.ktor:ktor-server-auth-jwt")

    // Link parsing
    implementation("com.microsoft.playwright:playwright:1.60.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

repositories {
    mavenCentral()
}
