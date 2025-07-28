plugins {
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "canaryprism"
version = "1.1.0"

application {
    mainClass = "canaryprism.jbfc.Main"
    mainModule = "canaryprism.jbfc"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(24)

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // https://mvnrepository.com/artifact/info.picocli/picocli
    implementation("info.picocli:picocli:4.7.7")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveClassifier = null
}

tasks.test {
    useJUnitPlatform()
}