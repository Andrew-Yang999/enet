package org.xnatural.enet.server.swagger;

import io.swagger.v3.jaxrs2.integration.JaxrsApplicationAndAnnotationScanner;
import io.swagger.v3.jaxrs2.integration.XmlWebOpenApiContext;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.xnatural.enet.core.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.util.*;
import java.util.concurrent.ExecutorService;

public class SwaggerServer extends ServerTpl {

    private String root;
    private Controller ctl;

    public SwaggerServer() {
        setName("swagger");
        setRoot("api-doc");
    }

    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("sys.env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            if (ec.result != null) {
                Map<String, Object> m = (Map) ec.result;
                root = (String) m.getOrDefault("root", getRoot());
                attrs.putAll(m);
            }
        });
        ctl = new Controller(this);
        log.info("创建({})服务.", getName());
        coreEp.fire("server.netty4Resteasy.addResource", EC.of("source", ctl).attr("path", getRoot()));
    }


    @EL(name = "sys.stopping")
    public void stop() {
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = "server.swagger.openApi")
    private void openApi(EC ec) throws Exception {
        // 参照: SwaggerLoader
        HashSet<String> rs = new HashSet<>(1); rs.add(ctl.getClass().getName());
        OpenAPI openApi = new XmlWebOpenApiContext().id(getName()).cacheTTL(0L).resourceClasses(rs).openApiConfiguration(
                new SwaggerConfiguration()
                        .scannerClass(JaxrsApplicationAndAnnotationScanner.class.getName())
                        .resourceClasses(rs).cacheTTL(0L)
        ).init().read();
        if (openApi == null) return;
        Tag t = new Tag(); t.setName(getName()); t.setDescription("swagger rest api");
        openApi.addTagsItem(t);
        Map<String, PathItem> rPaths = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, PathItem>> it = openApi.getPaths().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PathItem> e = it.next();
            PathItem pi = e.getValue();
            if (pi.getGet() != null && pi.getGet().getTags() == null) {
                pi.getGet().setTags(Collections.singletonList(t.getName()));
            }
            if (pi.getPost() != null && pi.getPost().getTags() == null) {
                pi.getPost().setTags(Collections.singletonList(t.getName()));
            }
            rPaths.put(("/" + getRoot() + "/" + e.getKey()).replace("//", "/"), pi);
            it.remove();
        }
        openApi.getPaths().putAll(rPaths);
        ((List) ec.result).add(openApi);
    }


    public SwaggerServer setRoot(String root) {
        if (running.get()) throw new RuntimeException("服务正在运行不能更改");
        this.root = root;
        attrs.put("root", root);
        return this;
    }


    public String getRoot() {
        return root;
    }
}