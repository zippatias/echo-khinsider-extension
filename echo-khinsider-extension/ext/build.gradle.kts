plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.0.2"
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    compileOnly("dev.brahmkshatriya.echo:common:1.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}
val extType: String by project
val extId: String by project
val extClass: String by project
val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project
val extAuthor: String by project
val extAuthorUrl: String? by project
val extRepoUrl: String? by project
val extUpdateUrl: String? by project
val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"
publishing {
    publications {
        create("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName
            from(components["java"])
        }
    }
}
tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,
                    "Extension-Version-Code" to gitCount,
                    "Extension-Version-Name" to verName,
                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,
                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,
                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
}
fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
