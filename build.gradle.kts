import java.util.Properties

plugins {
    id("java")
    kotlin("jvm").version("1.8.0")
}

group = "kr.kro.backas"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://m2.dv8tion.net/releases")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    implementation("commons-configuration:commons-configuration:1.10")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation("net.dv8tion:JDA:5.0.0-beta.11")
    // unused implementation("com.github.in-seo:univcert:master-SNAPSHOT")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.7")
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("com.github.walkyst:lavaplayer-fork:1.4.3")
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.merakianalytics.orianna:orianna:4.0.0-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.14.1")
}

tasks.test {
    useJUnitPlatform()
}

val targetJavaVersion = 17

tasks {
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(targetJavaVersion)
    }
}