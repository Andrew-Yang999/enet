package org.xnatural.enet.modules.mvc.anno;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
@Documented
public @interface PathVariable {
    /**
     * 参数名
     * @return
     */
    String name() default "";

    /**
     * 参数说明
     * @return
     */
    String desc() default "";
}
