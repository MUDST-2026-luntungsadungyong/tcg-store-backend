package com.mythix.tcg.voucher.api;

import com.mythix.tcg.common.config.AppProperties;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class VoucherLiveUpdates {
    private final Map<Session, AtomicBoolean> sessions = new ConcurrentHashMap<>();

    public record Changed(String type, Long voucherId) {}

    @TransactionalEventListener(fallbackExecution = true)
    public void publish(Changed change) {
        String eventType = change != null && change.type() != null ? change.type() : "CHANGED";
        String idPart = change != null && change.voucherId() != null ? String.valueOf(change.voucherId()) : "null";
        String payload = "{\"type\":\"" + eventType + "\",\"voucherId\":" + idPart + "}";

        sessions.forEach((session, sending) -> {
            if (!session.isOpen()) {
                sessions.remove(session);
                return;
            }
            if (sending.compareAndSet(false, true)) {
                try {
                    session.getAsyncRemote().sendText(payload, result -> sending.set(false));
                } catch (IllegalStateException closed) {
                    sending.set(false);
                    sessions.remove(session);
                }
            }
        });
    }

    public int activeConnectionCount() {
        return sessions.size();
    }

    public class VoucherEndpoint extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.setMaxIdleTimeout(90_000);
            session.getAsyncRemote().setSendTimeout(3_000);
            sessions.put(session, new AtomicBoolean(false));
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            sessions.remove(session);
        }

        @Override
        public void onError(Session session, Throwable error) {
            sessions.remove(session);
            close(session);
        }

        private void close(Session session) {
            try {
                session.close();
            } catch (java.io.IOException ignored) {
                // Peer may already have disconnected.
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!migration & !tidb")
class VoucherWebSocketConfiguration {
    @Bean
    ServletListenerRegistrationBean<ServletContextListener> voucherWebSocket(
            VoucherLiveUpdates updates, AppProperties properties) {
        return new ServletListenerRegistrationBean<>(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                Object container = event.getServletContext().getAttribute(ServerContainer.class.getName());
                if (!(container instanceof ServerContainer server)) {
                    return;
                }
                try {
                    server.addEndpoint(ServerEndpointConfig.Builder
                        .create(VoucherLiveUpdates.VoucherEndpoint.class, "/api/vouchers/live")
                        .configurator(new ServerEndpointConfig.Configurator() {
                            @Override
                            public boolean checkOrigin(String origin) {
                                return origin == null || properties.cors().allowedOrigins().contains(origin);
                            }

                            @Override
                            public <T> T getEndpointInstance(Class<T> type) {
                                return type.cast(updates.new VoucherEndpoint());
                            }
                        }).build());
                } catch (DeploymentException exception) {
                    throw new IllegalStateException("Could not register voucher live updates", exception);
                }
            }
        });
    }
}
