package com.mythix.tcg.auction.api;

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
public class AuctionLiveUpdates {
    private final Map<Session, Subscription> subscriptions = new ConcurrentHashMap<>();

    public record Changed(long auctionId) {}

    private record Subscription(long auctionId, AtomicBoolean sending) {}

    @TransactionalEventListener(fallbackExecution = true)
    public void publish(Changed change) {
        subscriptions.forEach((session, subscription) -> {
            if (subscription.auctionId() == change.auctionId() && session.isOpen()
                    && subscription.sending().compareAndSet(false, true)) {
                try {
                    session.getAsyncRemote().sendText("changed", result -> subscription.sending().set(false));
                } catch (IllegalStateException closed) {
                    subscriptions.remove(session);
                }
            }
        });
    }

    public class AuctionEndpoint extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.setMaxIdleTimeout(90_000);
            session.getAsyncRemote().setSendTimeout(3_000);
            String id = session.getPathParameters().get("auctionId");
            try {
                long auctionId = Long.parseLong(id);
                subscriptions.put(session, new Subscription(auctionId, new AtomicBoolean()));
            } catch (NumberFormatException invalid) {
                close(session);
            }
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            subscriptions.remove(session);
        }

        @Override
        public void onError(Session session, Throwable error) {
            subscriptions.remove(session);
            close(session);
        }

        private void close(Session session) {
            try {
                session.close();
            } catch (java.io.IOException ignored) {
                // The peer may already have disconnected.
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!migration & !tidb")
class AuctionWebSocketConfiguration {
    @Bean
    ServletListenerRegistrationBean<ServletContextListener> auctionWebSocket(
            AuctionLiveUpdates updates, AppProperties properties) {
        return new ServletListenerRegistrationBean<>(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent event) {
                Object container = event.getServletContext().getAttribute(ServerContainer.class.getName());
                if (!(container instanceof ServerContainer server)) {
                    return;
                }
                try {
                    server.addEndpoint(ServerEndpointConfig.Builder
                        .create(AuctionLiveUpdates.AuctionEndpoint.class, "/api/auctions/{auctionId}/live")
                        .configurator(new ServerEndpointConfig.Configurator() {
                            @Override
                            public boolean checkOrigin(String origin) {
                                return origin == null || properties.cors().allowedOrigins().contains(origin);
                            }

                            @Override
                            public <T> T getEndpointInstance(Class<T> type) {
                                return type.cast(updates.new AuctionEndpoint());
                            }
                        }).build());
                } catch (DeploymentException exception) {
                    throw new IllegalStateException("Could not register auction live updates", exception);
                }
            }
        });
    }
}
