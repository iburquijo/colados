package com.softbenur.colados;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las fronteras entre módulos, defendidas por un test.
 *
 * <p>Sin broker de por medio (ADR-0011), los módulos son paquetes del mismo proceso: una
 * llamada directa compila, funciona y hasta es más rápida. <b>Lo incorrecto pasó a ser lo
 * cómodo</b>, así que esta clase dejó de ser higiene y es lo único que sostiene el diseño.
 *
 * <p>Si esto se relaja "solo por esta vez", en dos semanas los módulos están enredados y
 * nadie se entera, porque nada se rompe. Y el día que se quiera meter un broker o extraer
 * un módulo, deja de ser una tarde y pasa a ser un refactor grande.
 */
class ArquitecturaTest {

    private static final String BASE = "com.softbenur.colados";
    private static JavaClasses clases;

    @BeforeAll
    static void importar() {
        clases = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("nadie entra en el internal de otro módulo")
    void losInternosSonInternos() {
        ArchRule regla = noClasses()
                .that().resideOutsideOfPackage(BASE + ".ingest..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".ingest.internal..")
                .because("cada módulo expone una API pública y oculta el resto (ADR-0003). "
                        + "Si api quiere leer lecturas, pasa por ReadQueries, no por el repositorio");
        regla.check(clases);
    }

    @Test
    @DisplayName("ingest no depende de api: los eventos van hacia arriba, nunca al revés")
    void ingestNoConoceLaApi() {
        noClasses()
                .that().resideInAPackage(BASE + ".ingest..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".api..")
                .check(clases);
    }

    @Test
    @DisplayName("solo los repositorios tocan JdbcTemplate")
    void soloLosRepositoriosTocanLaBaseDeDatos() {
        noClasses()
                .that().haveSimpleNameNotEndingWith("Repository")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .check(clases);
    }

    @Test
    @DisplayName("el dominio no arrastra dependencias de MQTT fuera de la ingesta")
    void mqttSoloEnIngest() {
        noClasses()
                .that().resideOutsideOfPackage(BASE + ".ingest..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.eclipse.paho..", "org.springframework.integration.mqtt..")
                .check(clases);
    }
}
