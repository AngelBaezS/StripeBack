/**
 * Servicios de Aplicación (Implementación de Casos de Uso).
 * <p>
 * Implementan las interfaces definidas en {@link org.angel.paymentclean.application.ports.in}
 * e interactúan con {@link org.angel.paymentclean.application.ports.out}.
 * <p>
 * En una arquitectura hexagonal pura con configuración manual, estas clases son POJOs de Java puro
 * sin anotaciones de Spring (como {@code @Service} o {@code @Autowired}), permitiendo que se configuren
 * e inyecten limpiamente mediante clases de configuración en infraestructura.
 */
package org.angel.paymentclean.application.service;
