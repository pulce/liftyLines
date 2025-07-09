plugins {
    id("java")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:deprecation",  // warn on deprecated API usage
            "-Xlint:unchecked"     // warn on unchecked/generic operations
        )
    )
 }

group = "org.pulce.liftylines"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.locationtech.jts:jts-core:1.16.1")
    implementation("com.google.guava:guava:33.4.8-jre")
    implementation("org.mapsforge:mapsforge-map-writer:0.25.0")
    implementation("org.mapsforge:mapsforge-core:0.25.0")
    implementation("org.openstreetmap.osmosis:osmosis-core:0.49.2")
    implementation("org.openstreetmap.osmosis:osmosis-xml:0.49.2")
    implementation("info.picocli:picocli:4.7.7")
    compileOnly("org.jetbrains:annotations:24.0.1")
 }
application {
    mainClass.set("org.pulce.liftylines.Main")
    applicationName = "liftyLines"
}

//Easy memory tweak for testing
//tasks.withType<JavaExec> {
//    jvmArgs = listOf("-Xmx16g")
//}

tasks.test {
    useJUnitPlatform()
}
tasks.named<Jar>("jar") {
    exclude("lifty-tag-mapping.xml")
}
distributions {
    getByName("main") {
        contents {
            from("src/main/resources/lifty-tag-mapping.xml") {
                into("lib")  // will reside in the lib directory of the distribution
            }
        }
    }
}
val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    val propsFile = outputDir.map { it.file("version.properties") }

    // 👇 Access project.version during configuration
    val versionString = project.version.toString()

    outputs.file(propsFile)

    doLast {
        propsFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$versionString")
        }
    }
}
sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/resources/version"))
    }
}

tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}

tasks.named<Tar>("distTar") {
    // turn the plain .tar into a .tar.gz
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
// make `./gradlew assemble` build both archives
tasks.named("assemble") {
    dependsOn(tasks.named("distZip"), tasks.named("distTar"))
}
