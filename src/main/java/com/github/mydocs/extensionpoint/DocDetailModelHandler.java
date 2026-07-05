package com.github.mydocs.extensionpoint;

import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Mono;

/**
 * <p>文档阅读页 model 注入扩展点（MULTI_INSTANCE）。</p>
 * <p>在阅读页（索引 / 库 / 详情）内置 model 组装完成后被调用，允许其他插件向 model 注入
 * 额外的数据或片段，例如相关文档、上一篇/下一篇、自定义侧栏块，供主题模板渲染。
 * 通过 {@link DocModelContext#getPageType()} 区分页面类型，对三个阅读页均生效。</p>
 * <p>实现方式：其他插件将实现类注册为 Spring bean（{@code @Component}），并在其
 * {@code plugin.yaml} 中通过 {@code pluginDependencies} 依赖 {@code my-docs}，即可被自动发现。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
public interface DocDetailModelHandler extends ExtensionPoint {

    /**
     * 向 {@code context.getModel()} 注入所需属性。多个 handler 按 {@link #getOrder()} 顺序执行，
     * 共享同一个 model。
     *
     * @param context 注入上下文，非空
     * @return 注入完成信号
     */
    Mono<Void> handle(DocModelContext context);

    /**
     * 执行顺序，值越小越先执行。默认 0。
     *
     * @return 排序权重
     */
    default int getOrder() {
        return 0;
    }
}
