plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: tipe OkHttp muncul di konstruktor AgentConnection.
    api(libs.okhttp)
    // org.json sudah ada di Android; di sini hanya untuk kompilasi dan test JVM.
    compileOnly(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.test {
    // Uji end-to-end ke agent Mac sungguhan hanya jalan kalau variabel ini di-set (lihat AgentClientE2ETest).
    environment("CURSORCTL_E2E_PAIRING_FILE", System.getenv("CURSORCTL_E2E_PAIRING_FILE") ?: "")
    environment("CURSORCTL_E2E_FOCUS_FILE", System.getenv("CURSORCTL_E2E_FOCUS_FILE") ?: "")
    environment("CURSORCTL_E2E_EXPECT_SCREEN", System.getenv("CURSORCTL_E2E_EXPECT_SCREEN") ?: "1")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // Skrip e2e memakai -q: kegagalan tetap tampil lengkap.
        quiet {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
