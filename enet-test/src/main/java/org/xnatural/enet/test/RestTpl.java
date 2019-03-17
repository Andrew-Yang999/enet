package org.xnatural.enet.test;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.dao.hibernate.TransWrapper;
import org.xnatural.enet.server.resteasy.SessionAttr;
import org.xnatural.enet.server.resteasy.SessionId;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.xnatural.enet.test.ApiResp.ok;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
public class RestTpl {

    private EP ep;
    final Log log = Log.of(getClass());

    private TestRepo     testRepo;
    private TransWrapper tm;


    @EL(name = "sys.started")
    public void init() {
        ep.fire("swagger.addJaxrsDoc", this, null, "tpl", "tpl rest doc");
        testRepo = (TestRepo) ep.fire("dao.bean.get", TestRepo.class);
        tm = (TransWrapper) ep.fire("dao.bean.get", TransWrapper.class);
    }


    @GET @Path("dao")
    @Produces("application/json")
    public Object dao() {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        // return testRepo.tbName();
        return tm.trans(() -> {
            // testRepo.findPage(0, 5, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
            testRepo.saveOrUpdate(e);
            return testRepo.findPage(0, 5, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
        });
        //return testRepo.findPage(0, 10, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
    }


    @GET @Path("get1")
    @Produces("application/json")
    public ApiResp get(@QueryParam("a") String a) {
        return ok("a", a);
    }


    @Operation(summary = "session 测试")
    @GET @Path("session")
    @Produces("application/json")
    public Object session(
        @Parameter(hidden = true) @SessionId String sId,
        @Parameter(hidden = true) @SessionAttr("attr1") Object attr1,
        @Parameter(description = "参数a") @QueryParam("a") String a
    ) {
        // if (true) throw new IllegalArgumentException("xxxxxxxxxxxxx");
        ep.fire("session.set", sId, "attr1", "value1" + System.currentTimeMillis());
        return ok("sId", sId).attr("attr1", attr1).attr("param_a", a);
    }


    @GET @Path("cache")
    @Produces("application/json")
    public ApiResp cache() {
        Object r = ep.fire("cache.get", "test", "key1");
        if (r == null) ep.fire("cache.add", "test", "key1", "qqqqqqqqqq");
        return ok(r);
    }


    @GET @Path("async")
    @Produces("text/plain")
    public CompletionStage<String> async() {
        CompletableFuture<String> f = new CompletableFuture();
        f.complete("ssssssssssss");
        return f;
    }


    @GET @Path("js/lib/{fName}")
    public Response jsLib(@PathParam("fName") String fName) {
        File f = new File(getClass().getClassLoader().getResource("static/js/lib/" + fName).getFile());
        return Response.ok()
                .header("Content-Disposition", "attachment; filename=" + f.getName())
                .header("Cache-Control", "max-age=60")
                .build();
    }
}
