plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.spring.boot.starter)
    implementation(libs.paho.mqtt)
    implementation(libs.jackson.yaml)
    testImplementation(libs.spring.boot.starter.test)
}
