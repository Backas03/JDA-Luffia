plugins {
    id("java")
    kotlin("jvm").version("1.8.0")
    id("application")
}

group = "kr.kro.backas"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://m2.dv8tion.net/releases")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots") // lavaplayer
}

dependencies {
    implementation("commons-configuration:commons-configuration:1.10")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation("net.dv8tion:JDA:5.0.0-beta.11")
    // unused implementation("com.github.in-seo:univcert:master-SNAPSHOT")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("com.github.JustRed23:lavadsp:0.7.7-1")
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("com.sun.mail:javax.mail:1.6.2")
    // 재생시 400에러 뜨는 이슈 : implementation("com.github.walkyst:lavaplayer-fork:1.4.3")
    implementation("dev.arbjerg:lavaplayer:727959e9f621fc457b3a5adafcfffb55fdeaa538-SNAPSHOT")
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.merakianalytics.orianna:orianna:4.0.0-SNAPSHOT")
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