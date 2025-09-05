plugins {
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "canaryprism"
version = "2.0.1"

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

    // https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
    implementation("org.apache.commons:commons-lang3:3.18.0")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveClassifier = null
}

tasks.test {
    useJUnitPlatform()
}