package com.aiwatchspaces.infrastructure.logging;

import com.aiwatchspaces.common.correlation.CorrelationKeys;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one Micrometer {@link ThreadLocalAccessor} per correlation key so that Reactor context
 * values are restored into the SLF4J MDC on whichever thread runs the work (ADR-003 §7, M1-V10).
 * Together with {@code spring.reactor.context-propagation=auto} this covers Netty threads; the db
 * executor adapter restores the same values explicitly on {@code db-*} threads.
 */
@Configuration(proxyBeanMethods = false)
public class MdcContextPropagation {

    static {
        register(ContextRegistry.getInstance());
    }

    static synchronized void register(ContextRegistry registry) {
        for (String key : CorrelationKeys.PROPAGATED) {
            boolean present = registry.getThreadLocalAccessors().stream().anyMatch(a -> key.equals(a.key()));
            if (!present) {
                registry.registerThreadLocalAccessor(new MdcKeyAccessor(key));
            }
        }
    }

    private record MdcKeyAccessor(String mdcKey) implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return mdcKey;
        }

        @Override
        public String getValue() {
            return MDC.get(mdcKey);
        }

        @Override
        public void setValue(String value) {
            MDC.put(mdcKey, value);
        }

        @Override
        public void setValue() {
            MDC.remove(mdcKey);
        }
    }
}
