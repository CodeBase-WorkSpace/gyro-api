import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

val jooqCodegen: Configuration by configurations.creating

group = "com.gyro"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

kotlin {
	jvmToolchain {
		languageVersion.set(JavaLanguageVersion.of(25))
	}
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_24)
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
	implementation("io.jsonwebtoken:jjwt-api:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
	implementation("com.squareup.okhttp3:okhttp:5.3.2")
	implementation("nl.martijndwars:web-push:5.1.1") {
		exclude(group = "com.beust", module = "jcommander")
		exclude(group = "org.asynchttpclient", module = "async-http-client")
	}
	implementation("org.bitbucket.b_c:jose4j:0.9.6")
	implementation("org.bouncycastle:bcprov-jdk18on:1.84")

	jooqCodegen("org.jooq:jooq-codegen")
	jooqCodegen("org.jooq:jooq-meta-extensions")
	jooqCodegen("org.postgresql:postgresql")
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	maxHeapSize = "2g"
}

val verifyFlywayMigrationImmutability by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject edits to existing versioned Flyway migrations."
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/verify-flyway-migration-immutability.sh")
}

tasks.named("bootRun") {
    dependsOn(verifyFlywayMigrationImmutability)
}

tasks.named("test") {
    dependsOn(verifyFlywayMigrationImmutability)
}

fun Test.configureOpenApiArtifactTest(updateArtifact: Boolean) {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.gyro.api.contract.OpenApiArtifactIntegrationTest")
    }
    systemProperty("gyro.openapi.update", updateArtifact)
}

val generateOpenApi by tasks.registering(Test::class) {
    group = "documentation"
    description = "Generate the canonical OpenAPI artifact from the running application contract."
    configureOpenApiArtifactTest(updateArtifact = true)
    outputs.file(layout.projectDirectory.file("openapi/openapi.json"))
    outputs.upToDateWhen { false }
}

val verifyOpenApi by tasks.registering(Test::class) {
    group = "verification"
    description = "Verify that the committed OpenAPI artifact matches the running application contract."
    configureOpenApiArtifactTest(updateArtifact = false)
}

tasks.withType<JavaCompile> {
	options.release.set(24)
}

sourceSets {
	main {
		java {
			srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
		}
	}
}

val generateJooq by tasks.registering(JavaExec::class) {
	group = "jOOQ"
	description = "Generate jOOQ classes from the codegen schema projection."
	classpath = jooqCodegen
	mainClass.set("org.jooq.codegen.GenerationTool")
	args(layout.projectDirectory.file("src/main/resources/jooq-codegen.xml").asFile.absolutePath)
	inputs.file("src/main/resources/db/jooq/schema.sql")
	inputs.file("src/main/resources/jooq-codegen.xml")
	outputs.dir(layout.buildDirectory.dir("generated-src/jooq/main"))
}

tasks.named("compileKotlin") {
	dependsOn(generateJooq)
}
