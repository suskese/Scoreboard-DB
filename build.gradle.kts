plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "me.mklv"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    // Paper API (provided by server)
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")

    // Shaded dependencies
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")
    // JDBC drivers and connection pooling provided by server
    
    compileOnly("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand(mapOf("version" to project.version))
        }
    }

    shadowJar {
        relocate("org.yaml.snakeyaml", "me.mklv.shaded.snakeyaml")
        archiveFileName.set("${project.name}-${project.version}.jar")
    }

    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}
