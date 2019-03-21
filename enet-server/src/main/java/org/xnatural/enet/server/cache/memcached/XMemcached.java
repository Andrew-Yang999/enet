package org.xnatural.enet.server.cache.memcached;

import com.google.code.yanf4j.config.Configuration;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.command.BinaryCommandFactory;
import net.rubyeye.xmemcached.utils.AddrUtil;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Memcached
 */
public class XMemcached extends ServerTpl {

    protected MemcachedClient client;

    public XMemcached() {
        setName("memcached");
    }
    public XMemcached(String name) {
        setName(name);
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "cache", getName()));

        try {
            List<InetSocketAddress> as = AddrUtil.getAddresses(getStr("hosts", "localhost:11211"));
            System.setProperty(Configuration.XMEMCACHED_SELECTOR_POOL_SIZE, getInteger("selectorPoolSize", as.size()) + "");
            XMemcachedClientBuilder builder = new XMemcachedClientBuilder(as);
            builder.setConnectionPoolSize(getInteger("poolSize", as.size()));
            builder.setConnectTimeout(getLong("connectTimeout", 5000L));
            builder.setOpTimeout(getLong("opTimeout", 5000L));
            builder.setName(getName());
            if (getBoolean("binaryCommand", false)) builder.setCommandFactory(new BinaryCommandFactory());
            client = builder.build();
            exposeBean(client);
        } catch (Exception ex) {
            log.error(ex);
        }

        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        try {
            client.shutdown();
        } catch (IOException e) {
            log.error(e);
        }
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }



    @EL(name = {"${name}.set", "cache.set"})
    protected void set(String cName, String key, Object value) {
        try {
            client.withNamespace(cName, c -> {
                c.setWithNoReply(key, getInteger("expire." + cName, 60 * 30), value); return null;
            });
        } catch (Exception e) {
            log.error(e);
        }
    }


    @EL(name = {"${name}.get", "cache.get"}, async = false)
    protected Object get(String cName, String key) {
        try {
            return client.withNamespace(cName, c -> c.get(key));
        } catch (Exception e) {
            log.error(e);
        }
        return null;
    }


    @EL(name = {"${name}.evict", "cache.evict"})
    protected void evict(String cName, String key) {
        try {
            client.withNamespace(cName, c -> c.delete(key));
        } catch (Exception e) {
            log.error(e);
        }
    }


    @EL(name = {"${name}.clear", "cache.clear"})
    protected void clear(String cName) {
        try {
            client.invalidateNamespace(cName, 10 * 1000L);
        } catch (Exception e) {
            log.error(e);
        }
    }
}