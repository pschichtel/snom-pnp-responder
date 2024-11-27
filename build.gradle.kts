plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.shadow)
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.sip)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("tel.schich.snompnpresponder.MainKt")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
}
