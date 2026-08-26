plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.owasp.dependency.check) apply false
    alias(libs.plugins.stability.analyzer) apply false
    alias(libs.plugins.sonarqube)
}

val sonarProjectProperties =
    java.util.Properties().apply {
        val propertiesFile = rootProject.file("sonar-project.properties")
        if (propertiesFile.isFile) {
            propertiesFile.inputStream().use(::load)
        }
    }

sonar {
    properties {
        sonarProjectProperties.forEach { key, value ->
            property(key.toString(), value.toString())
        }
    }
}

project(":app") {
    sonar {
        properties {
            property(
                "sonar.coverage.jacoco.xmlReportPaths",
                layout.buildDirectory
                    .file("reports/coverage/test/debug/report.xml")
                    .get()
                    .asFile
                    .absolutePath,
            )
        }
    }
}

tasks.named("sonar") {
    dependsOn(":app:assembleDebug", ":app:createDebugUnitTestCoverageReport")
}

fun org.gradle.api.artifacts.DependencyResolveDetails.enforceSecureTransitiveVersion() {
    val secureVersion =
        when {
            requested.group == "io.netty" &&
                requested.name.startsWith("netty-") &&
                !requested.name.startsWith("netty-tcnative") -> "4.1.137.Final"

            requested.group == "org.bouncycastle" &&
                requested.name in setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") -> "1.84"
            requested.group == "ch.qos.logback" &&
                requested.name in setOf("logback-classic", "logback-core") -> "1.5.34"
            requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> "0.9.6"
            requested.group == "org.jdom" && requested.name == "jdom2" -> "2.0.6.1"
            requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> "3.20.0"
            requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> "4.5.14"
            else -> null
        }
    secureVersion?.let {
        useVersion(it)
        because("GitHub Dependabot security remediation")
    }
}

buildscript {
    configurations.classpath {
        resolutionStrategy.eachDependency {
            val secureVersion =
                when {
                    requested.group == "io.netty" &&
                        requested.name.startsWith("netty-") &&
                        !requested.name.startsWith("netty-tcnative") -> "4.1.137.Final"

                    requested.group == "org.bouncycastle" &&
                        requested.name in setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") -> "1.84"
                    requested.group == "ch.qos.logback" &&
                        requested.name in setOf("logback-classic", "logback-core") -> "1.5.34"
                    requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> "0.9.6"
                    requested.group == "org.jdom" && requested.name == "jdom2" -> "2.0.6.1"
                    requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> "3.20.0"
                    requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> "4.5.14"
                    else -> null
                }
            secureVersion?.let {
                useVersion(it)
                because("GitHub Dependabot security remediation")
            }
        }
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            enforceSecureTransitiveVersion()
        }
    }
}
