plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"

kotlin {
    listOf(linuxX64(), macosArm64()).forEach { target ->
        target.binaries.executable {
            baseName = "couchbridge"
            entryPoint = "couchbridge.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-server-core:$ktorVersion")
            implementation("io.ktor:ktor-server-cio:$ktorVersion")
            implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-curl:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("com.charleskorn.kaml:kaml:0.104.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
