package io.groundedaccess;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled tasks. Each task decides for itself whether it runs, through its own {@code enabled} property.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
}
