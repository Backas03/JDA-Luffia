plugins {
    id("java")
    id("application")
}

group = "kr.kro.backas"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://m2.dv8tion.net/releases")
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.topi.wtf/releases")
}

dependencies {
    implementation("commons-configuration:commons-configuration:1.10")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation("net.dv8tion:JDA:5.0.0-beta.11")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("com.github.JustRed23:lavadsp:0.7.7-1")
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("com.sun.mail:javax.mail:1.6.2")

    implementation("dev.arbjerg:lavaplayer:2.2.7")
    implementation("dev.lavalink.youtube:v2:1.18.2")
    implementation("com.github.topi314.lavasrc:lavasrc:4.8.3")

    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.github.meraki-analytics.orianna:orianna:master-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.15.3")
}

application.mainClass.set("kr.kro.backas.Main")

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
