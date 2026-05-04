plugins {
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(fileTree("/opt/cirrina/lib") { include("*.jar") })
    implementation("io.javalin:javalin:6.5.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "at.ac.uibk.dps.cirrina.execution.object.BuildingServiceKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
