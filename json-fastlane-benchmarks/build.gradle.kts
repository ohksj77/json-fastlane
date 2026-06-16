plugins {
    application
}

dependencies {
    implementation(project(":json-fastlane-core"))
    implementation(project(":json-fastlane-spring"))
    implementation(project(":json-fastlane-netty"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor(project(":json-fastlane-processor"))
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

application {
    mainClass.set("io.jsonfastlane.RealisticLoadSimulation")
}

val jmhSourceSet = sourceSets.create("jmh") {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[jmhSourceSet.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[jmhSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])

dependencies {
    add(jmhSourceSet.implementationConfigurationName, project(":json-fastlane-core"))
    add(jmhSourceSet.implementationConfigurationName, project(":json-fastlane-netty"))
    add(jmhSourceSet.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.21.2")
    add(jmhSourceSet.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmhSourceSet.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

val jmh by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs JMH microbenchmarks for JSON fast paths."
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args(
        findProperty("jmhInclude")?.toString() ?: "io.jsonfastlane.bench.*",
        "-wi", findProperty("jmhWarmups")?.toString() ?: "2",
        "-i", findProperty("jmhIterations")?.toString() ?: "3",
        "-f", findProperty("jmhForks")?.toString() ?: "1"
    )
}

val smokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dependency-free smoke checks for the JSON fastlane prototype."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.SmokeChecks")
}

val springAdapterSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs smoke checks for the Spring MVC Jackson profiling adapter."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.SpringAdapterSmokeChecks")
}

val nettySmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs smoke checks for Netty ByteBuf writer routing."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.NettySmokeChecks")
}

val generatedWriterSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs smoke checks for annotation-processor generated JSON writers."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.GeneratedWriterSmokeChecks")
}

val transportLaneSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs smoke checks for experimental transport-lane JSON sinks."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.TransportLaneSmokeChecks")
}

val fastPathCandidateReport by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Prints a sample json-fastlane fast-path candidate report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.FastPathCandidateReport")
    systemProperty("jsonfastlane.report.samples", findProperty("candidateSamples") ?: "")
    systemProperty("jsonfastlane.report.config", findProperty("candidateConfig") ?: "")
    systemProperty("jsonfastlane.report.output", findProperty("candidateOutput") ?: "text")
    systemProperty("jsonfastlane.report.stableSamples", findProperty("candidateStableSamples") ?: "250")
    systemProperty("jsonfastlane.report.driftingSamples", findProperty("candidateDriftingSamples") ?: "60")
}

val compareFastPathCandidateReports by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compares two json-fastlane JSON candidate reports for CI regressions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.FastPathReportComparator")
    systemProperty("jsonfastlane.compare.baseline", findProperty("baselineReport") ?: "")
    systemProperty("jsonfastlane.compare.current", findProperty("currentReport") ?: "")
    systemProperty("jsonfastlane.compare.maxScoreDrop", findProperty("maxScoreDrop") ?: "20")
    systemProperty("jsonfastlane.compare.maxHotOrderRatioDrop", findProperty("maxHotOrderRatioDrop") ?: "0.20")
}

val realisticLoadTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs a realistic in-process JSON serialization load simulation."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.RealisticLoadSimulation")
    systemProperty("jsonfastlane.load.threads", findProperty("loadThreads") ?: "8")
    systemProperty("jsonfastlane.load.iterations", findProperty("loadIterations") ?: "12000")
    jvmArgs("-Xms512m", "-Xmx512m")
}

val jfrRealisticLoadTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the realistic load simulation with Java Flight Recorder enabled."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.jsonfastlane.RealisticLoadSimulation")
    systemProperty("jsonfastlane.load.threads", findProperty("loadThreads") ?: "8")
    systemProperty("jsonfastlane.load.iterations", findProperty("loadIterations") ?: "50000")
    jvmArgs("-Xms512m", "-Xmx512m")

    val jfrOutput = layout.buildDirectory.file("reports/json-fastlane/realistic-load.jfr")
    outputs.file(jfrOutput)
    doFirst {
        val output = jfrOutput.get().asFile
        output.parentFile.mkdirs()
        jvmArgs("-XX:StartFlightRecording=filename=${output.absolutePath},settings=profile,dumponexit=true")
    }
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.named("check") {
    dependsOn(smokeTest)
    dependsOn(springAdapterSmokeTest)
    dependsOn(nettySmokeTest)
    dependsOn(generatedWriterSmokeTest)
    dependsOn(transportLaneSmokeTest)
}
