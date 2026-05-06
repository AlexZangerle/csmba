plugins {
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(fileTree("/opt/cirrina/lib") { include("*.jar") })
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "at.ac.uibk.dps.cirrina.execution.object.SimulationKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
