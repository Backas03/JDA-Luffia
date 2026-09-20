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
    maven("https://maven.lavalink.dev/snapshots")
    maven("https://maven.topi.wtf/releases")
}

dependencies {
    implementation("commons-configuration:commons-configuration:1.10")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation("net.dv8tion:JDA:6.6.0")
    implementation("club.minnced:jdave-api:0.1.8")
    runtimeOnly("club.minnced:jdave-native-win-x86-64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-linux-x86-64:0.1.8")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("com.github.JustRed23:lavadsp:0.7.7-1")
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("com.sun.mail:javax.mail:1.6.2")

    implementation("dev.arbjerg:lavaplayer:2.2.7")
    implementation("dev.lavalink.youtube:v2:2be8e542d3f6f178e048dca565892684c2e40177-SNAPSHOT")
    implementation("com.github.topi314.lavasrc:lavasrc:4.8.3")

    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.github.meraki-analytics.orianna:orianna:master-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.15.3")
}

application {
    mainClass.set("kr.kro.backas.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("spotifyLogin") {
    group = "application"
    description = "스포티파이 플레이리스트 읽기용 refresh token 을 발급받습니다"
    mainClass.set("kr.kro.backas.music.source.SpotifyLoginTool")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.name()
}
