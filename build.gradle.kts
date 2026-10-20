plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.example"
version = "0.0.1"

repositories {
    mavenCentral()
}
tasks.shadowJar {
    manifest {
        attributes("Main-Class" to  "net.shchoo.scafe.scafe.Main")
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}