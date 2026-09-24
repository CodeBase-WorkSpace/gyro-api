package com.gyro.api.architecture

import jakarta.persistence.Entity
import jakarta.persistence.Version
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.*
import kotlin.test.assertTrue

class JpaPersistenceArchitectureTest {
    @Test
    fun `JPA entities are proxy compatible regular classes`() {
        val entities = annotatedClasses(Entity::class.java)
        assertTrue(entities.isNotEmpty(), "Entity scan must discover the application's JPA model.")

        val dataClasses = entities.filter { it.kotlin.isData }
        assertTrue(
            dataClasses.isEmpty(),
            "JPA entities must not be Kotlin data classes: ${dataClasses.joinToString { it.name }}",
        )

        val finalClasses = entities.filter { Modifier.isFinal(it.modifiers) }
        assertTrue(
            finalClasses.isEmpty(),
            "JPA entities must remain open for Hibernate proxies: ${finalClasses.joinToString { it.name }}",
        )

        val missingNoArgConstructor = entities.filter { entity ->
            entity.declaredConstructors.none { constructor -> constructor.parameterCount == 0 }
        }
        assertTrue(
            missingNoArgConstructor.isEmpty(),
            "JPA entities must expose a no-argument constructor: ${missingNoArgConstructor.joinToString { it.name }}",
        )
    }

    @Test
    fun `lock version fields participate in optimistic locking`() {
        val missingVersion = annotatedClasses(Entity::class.java).filter { entity ->
            entity.declaredFields.any { it.name == "lockVersion" } &&
                    entity.declaredFields.none { it.isAnnotationPresent(Version::class.java) }
        }

        assertTrue(
            missingVersion.isEmpty(),
            "Entity lockVersion fields must be annotated with @Version: ${missingVersion.joinToString { it.name }}",
        )
    }

    @Test
    fun `controllers do not use JPA entities as external contracts`() {
        val entityNames = annotatedClasses(Entity::class.java).mapTo(mutableSetOf()) { it.name }
        val violations = annotatedClasses(RestController::class.java).flatMap { controller ->
            controller.declaredMethods
                .asSequence()
                .filterNot { it.isBridge || it.isSynthetic }
                .filter { AnnotatedElementUtils.hasAnnotation(it, RequestMapping::class.java) }
                .filter { method ->
                    method.genericReturnType.referencesEntity(entityNames) ||
                            method.genericParameterTypes.any { it.referencesEntity(entityNames) }
                }
                .map { method -> "${controller.name}.${method.name}" }
                .toList()
        }

        assertTrue(
            violations.isEmpty(),
            "Controller request and response contracts must not expose JPA entities: ${violations.joinToString()}",
        )
    }

    private fun annotatedClasses(annotation: Class<out Annotation>): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(annotation))
        }
        return scanner.findCandidateComponents("com.gyro.api")
            .map { definition -> Class.forName(requireNotNull(definition.beanClassName)) }
            .sortedBy { it.name }
    }

    private fun Type.referencesEntity(entityNames: Set<String>): Boolean = when (this) {
        is Class<*> -> name in entityNames || (isArray && componentType.referencesEntity(entityNames))
        is ParameterizedType ->
            rawType.referencesEntity(entityNames) || actualTypeArguments.any { it.referencesEntity(entityNames) }

        is GenericArrayType -> genericComponentType.referencesEntity(entityNames)
        is WildcardType ->
            upperBounds.any { it.referencesEntity(entityNames) } || lowerBounds.any { it.referencesEntity(entityNames) }

        is TypeVariable<*> -> bounds.any { it.referencesEntity(entityNames) }
        else -> false
    }
}
