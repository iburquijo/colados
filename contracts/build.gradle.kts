plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    testImplementation(libs.spring.boot.starter.test)
}
