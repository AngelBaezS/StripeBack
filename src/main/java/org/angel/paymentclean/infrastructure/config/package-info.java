/**
 * Configuración de Infraestructura y Cableado de Dependencias (Wiring).
 * <p>
 * Contiene clases anotadas con {@code @Configuration} de Spring.
 * En una arquitectura con configuración manual, aquí se registran los {@code @Bean} que instancian
 * y conectan los Casos de Uso (servicios de aplicación) con sus respectivos adaptadores de salida (puertos),
 * manteniendo el núcleo completamente libre de anotaciones de framework.
 * También aloja la configuración de clientes HTTP, seguridad, OpenAPI/Scalar y filtros web.
 */
package org.angel.paymentclean.infrastructure.config;
