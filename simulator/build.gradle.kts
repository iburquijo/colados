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

tasks.named<JavaExec>("bootRun") {
    // 'layoutPath' apunta a infra/plant-layout.yaml, relativo a la raiz del repo. Gradle
    // lanza bootRun con el directorio del modulo como working dir, asi que sin esto el
    // simulador arranca y muere sin encontrar el layout.
    workingDir = rootDir
}
