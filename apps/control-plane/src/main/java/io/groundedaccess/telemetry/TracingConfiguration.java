package io.groundedaccess.telemetry;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Decides what is traced and what a trace may contain. Every span exporter is wrapped in a {@link RedactingSpanExporter}, whichever
 * backend it sends to, so the allow-list cannot be bypassed by adding an exporter.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

    @Bean
    static BeanPostProcessor redactingSpanExporters() {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof SpanExporter exporter && !(bean instanceof RedactingSpanExporter) ? new RedactingSpanExporter(exporter) : bean;
            }
        };
    }

    /**
     * Health probes, the security filter chain and the polling of background jobs would each add spans to every trace without explaining a
     * query, so they are not observed.
     */
    @Bean
    ObservationPredicate pipelineObservationsOnly() {
        return (name, context) -> !name.startsWith("spring.security.") && !name.startsWith("tasks.scheduled.") && !isProbe(context);
    }

    private static boolean isProbe(Observation.Context context) {
        if (context instanceof ServerRequestObservationContext request) {
            HttpServletRequest carrier = request.getCarrier();
            return carrier != null && carrier.getRequestURI().startsWith("/actuator");
        }
        return false;
    }
}
