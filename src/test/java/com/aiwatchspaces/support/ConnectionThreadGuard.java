package com.aiwatchspaces.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Blocking-boundary guard (ADR-003 §10). Wraps the application {@link DataSource} and records the
 * thread of every connection acquisition while armed. Startup (Flyway, Hibernate) runs unarmed on
 * the main thread; tests arm the guard and then assert that only {@code db-*} threads acquired.
 */
public class ConnectionThreadGuard implements BeanPostProcessor {

    private final AtomicBoolean armed = new AtomicBoolean();
    private final List<String> acquiringThreads = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof GuardedDataSource)) {
            return new GuardedDataSource(dataSource);
        }
        return bean;
    }

    public void arm() {
        acquiringThreads.clear();
        armed.set(true);
    }

    public void disarm() {
        armed.set(false);
    }

    public List<String> acquiringThreads() {
        return List.copyOf(acquiringThreads);
    }

    public List<String> violations() {
        return acquiringThreads.stream().filter(name -> !name.startsWith(DbExecutor.THREAD_PREFIX)).toList();
    }

    private void record() {
        if (armed.get()) {
            acquiringThreads.add(Thread.currentThread().getName());
        }
    }

    private final class GuardedDataSource extends DelegatingDataSource {

        GuardedDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            record();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            record();
            return super.getConnection(username, password);
        }
    }
}
