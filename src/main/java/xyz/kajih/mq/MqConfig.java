package xyz.kajih.mq;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mq")
public interface MqConfig {
    String host();
    int port();
    String queueManager();   // mq.queue-manager
    String channel();
}
