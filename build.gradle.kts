group = "io.jsonfastlane"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }

            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }

        tasks.withType<Javadoc>().configureEach {
            (options as org.gradle.external.javadoc.StandardJavadocDocletOptions)
                .addStringOption("Xdoclint:none", "-quiet")
        }
    }

    plugins.withId("java-library") {
        apply(plugin = "maven-publish")

        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            publications {
                create<org.gradle.api.publish.maven.MavenPublication>("mavenJava") {
                    from(components["java"])

                    pom {
                        name.set(project.name)
                        description.set("Profile-guided JSON serialization primitives for JVM services.")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                    }
                }
            }

            repositories {
                val publishRepositoryUrl = providers.gradleProperty("publishRepositoryUrl").orNull
                    ?: System.getenv("PUBLISH_REPOSITORY_URL")
                if (!publishRepositoryUrl.isNullOrBlank()) {
                    maven {
                        name = "remote"
                        url = uri(publishRepositoryUrl)
                        credentials {
                            username = providers.gradleProperty("publishRepositoryUsername").orNull
                                ?: System.getenv("PUBLISH_REPOSITORY_USERNAME")
                            password = providers.gradleProperty("publishRepositoryPassword").orNull
                                ?: System.getenv("PUBLISH_REPOSITORY_PASSWORD")
                        }
                    }
                }
            }
        }
    }
}
