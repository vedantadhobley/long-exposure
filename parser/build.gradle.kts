plugins {
    application
    java
}

group = "com.longexposure"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // pcap parsing is implemented in-tree (com.longexposure.pcap.PcapReader)
    // against the libpcap file format directly — no pcap4j dependency, no
    // libpcap native runtime required. Streams from .pcap.gz via GZIPInputStream.

    // Postgres driver (TimescaleDB speaks PG wire)
    implementation("org.postgresql:postgresql:42.7.4")

    // Temporal Java SDK
    implementation("io.temporal:temporal-sdk:1.25.0")

    // HTTP client for IEX HIST + LLM
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")

    // Logging — JSON encoder so promtail picks up structured logs cleanly
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("com.longexposure.Main")
    // Hard heap cap so a runaway scorer's buffer can't OOM the host.
    // 4 GB is generous for the workloads we have (cluster buffers are
    // capped at 10K records per scorer; typical COPY buffer is a few MB).
    // -XX:+ExitOnOutOfMemoryError fails the JVM fast instead of
    // thrashing GC indefinitely when we do hit the cap.
    applicationDefaultJvmArgs = listOf(
            "-Xmx4g",
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/tmp"
    )
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
