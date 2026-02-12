plugins {
    java
    id ("com.gradleup.shadow") version "9.3.0"
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
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Paper API (provided by server)
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")

    // PlaceholderAPI (provided by server)
    compileOnly("me.clip:placeholderapi:2.12.1")

    // Shaded dependencies
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")
    // JDBC drivers and connection pooling provided by server
    
    compileOnly("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    val pluginVersion = project.version.toString()
    processResources {
        filesMatching("plugin.yml") {
            expand(mapOf("version" to pluginVersion))
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
