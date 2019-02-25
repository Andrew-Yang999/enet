package org.xnatural.enet.server;

import org.xnatural.enet.common.Context;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 模块 模板 代码.
 * 自定义模块时, 可参考此类代码按需copy
 */
public class ServerTpl {
    protected Log                 log;
    /**
     * 服务名字标识.(保证唯一)
     * 可用于命名空间:
     *      1. 可用于属性配置前缀
     *      2. 可用于事件名字前缀
     */
    private   String              name;
    /**
     * 可配置属性集.
     */
    protected Map<String, Object> attrs   = new HashMap<>();
    /**
     * 此服务执行器
     */
    protected Executor            coreExec;
    /**
     * 1. 当此服务被加入核心时, 此值会自动设置为核心的EP.
     * 2. 如果要服务独立运行时, 请手动设置
     */
    protected EP                  coreEp;
    /**
     * 是否正在运行标志
     */
    protected AtomicBoolean       running = new AtomicBoolean(false);


    public ServerTpl() {
        log = Log.of(getClass());
    }


    /**
     * Server start
     */
    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", getName());
        attrs.putAll(r);
        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    /**
     * Server stop
     */
    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    /**
     * 注册Server本身
     */
    @EL(name = "${name}.started")
    protected void started() {
        exposeBean(this, getName());
    }


    /**
     * 子类应该重新定义自己的重启逻辑
     */
    @EL(name = "sys.restart")
    protected void restart() {
        stop(); start();
    }


    /**
     * bean 容器. {@link #findBean}
     */
    protected Context beanCtx;
    @EL(name = {"bean.get", "${name}.bean.get"}, async = false)
    protected Object findBean(EC ec, Class beanType, String beanName) {
        if (beanCtx == null) return ec.result;
        if (ec.result != null) return ec.result; // 已经找到结果了, 就直接返回

        Object bean = null;

        if (beanName != null && beanType != null) {
            bean = beanCtx.getAttr(beanName);
            if (bean != null && !beanType.isAssignableFrom(bean.getClass())) bean = null;
        } else if (beanName != null && beanType == null) {
            bean = beanCtx.getAttr(beanName);
        } else if (beanName == null && beanType != null) {
            bean = beanCtx.getValue(beanType);
        }
        return bean;
    }


    /**
     * 暴露 bean 给其它模块用. {@link #findBean}
     * @param names bean 名字.
     * @param bean
     */
    protected ServerTpl exposeBean(Object bean, String... names) {
        if (bean == null) {
            log.warn("server '{}' expose bean with null object.", getName()); return this;
        }
        // TODO 验证(相同的bean名字和类型)?
        if (beanCtx == null) beanCtx = new Context();
        if (names != null) {
            for (String n : names) {
                if (beanCtx.getAttr(n) != null) log.warn("override exist bean name '{}'", n);
                beanCtx.attr(n, bean);
            }
        }
        beanCtx.put(bean);
        return this;
    }


    @EL(name = "server.${name}.info")
    protected Map<String, Object> info() throws Exception {
        Map<String, Object> r = new HashMap<>(5);
        r.put("_this", this);

        // 属性
        List<Map<String, Object>> properties = new LinkedList<>(); r.put("properties", properties);
        for (PropertyDescriptor pd : Introspector.getBeanInfo(getClass()).getPropertyDescriptors()) {
            Map<String, Object> prop = new HashMap<>(2); properties.add(prop);
            prop.put("name", pd.getName());
            prop.put("set", pd.getWriteMethod() != null);
            prop.put("type", pd.getPropertyType());
            try {
                prop.put("value", pd.getReadMethod().invoke(this));
            } catch (Exception e) {
                log.warn(e, "属性取值错误. name: {}", pd.getName());
            }
        }

        // 方法
        List<Map<String, Object>> methods = new LinkedList<>(); r.put("methods", methods);
        Class c = getClass();
        do {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() > 0) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (!Modifier.isPublic(m.getModifiers())) continue;
                Map<String, Object> method = new HashMap<>(2); methods.add(method);
                method.put("name", m.getName());
                method.put("annotations", Arrays.stream(m.getAnnotations()).map(a -> "@" + a.getClass().getSimpleName()).collect(Collectors.toList()));
            }
            c = c.getSuperclass();
        } while (c != null);
        return r;
    }


    /**
     * 属性更新触发器
     * @param k
     * @param v
     */
    @EL(name = "env.updateAttr")
    protected void updateAttr(String k, String v) {}


    /**
     * 初始化一个内部 {@link Executor}
     */
    protected void initExecutor() {
        if (coreEp instanceof ExecutorService) {
            log.warn("关闭之前的线程池");
            ((ExecutorService) coreEp).shutdown();
        }
        log.debug("为服务({})创建私有线程池. ", getName());
        ThreadPoolExecutor e = new ThreadPoolExecutor(
                4, 4, 10, TimeUnit.MINUTES, new LinkedBlockingQueue<>(100000),
                new ThreadFactory() {
                    final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, getName() + "-" + count.getAndIncrement());
                    }
                }
        );
        e.allowCoreThreadTimeOut(true);
        coreExec = e;
    }


    public ServerTpl setName(String name) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新服务名");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("服务标识名不能为空");
        this.name = name;
        return this;
    }


    public boolean isRunning() {
        return running.get();
    }


    public String getName() {
        return name;
    }


    public EP getCoreEp() {
        return coreEp;
    }


    public Long getLong(String name, Long defaultValue) {
        return Utils.toLong(attrs.get(name), defaultValue);
    }


    public Integer getInteger(String name, Integer defaultValue) {
        return Utils.toInteger(attrs.get(name), defaultValue);
    }


    public Boolean getBoolean(String name, Boolean defaultValue) {
        return Utils.toBoolean(attrs.get(name), defaultValue);
    }


    public Object getAttr(String name, Object defaultValue) {
        return attrs.getOrDefault(name, defaultValue);
    }
}
