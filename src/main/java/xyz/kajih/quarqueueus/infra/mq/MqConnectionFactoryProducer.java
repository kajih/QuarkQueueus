package xyz.kajih.quarqueueus.infra.mq;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import io.quarkiverse.messaginghub.pooled.jms.PooledJmsWrapper;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

@ApplicationScoped
public class MqConnectionFactoryProducer {

    @Produces
    @ApplicationScoped
    @Unremovable
    ConnectionFactory mqConnectionFactory(MqConfig mq, PooledJmsWrapper pool) throws JMSException {
        var cf = new MQConnectionFactory();
        cf.setHostName(mq.host());
        cf.setPort(mq.port());
        cf.setQueueManager(mq.queueManager());
        cf.setChannel(mq.channel());
        cf.setTransportType(WMQConstants.WMQ_CM_CLIENT);   // TCP client, not local bindings
        return pool.wrapConnectionFactory(cf);
    }
}