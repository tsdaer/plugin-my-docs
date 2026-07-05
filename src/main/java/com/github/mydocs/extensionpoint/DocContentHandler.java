package com.github.mydocs.extensionpoint;

import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Mono;

/**
 * <p>文档正文内容后处理扩展点（MULTI_INSTANCE）。</p>
 * <p>在文档正文 HTML 生成之后、返回给主题模板展示之前，对内容进行链式改写，
 * 例如图片懒加载、代码高亮容器包裹、内容注入等。语义类比 Halo 官方的
 * {@code ReactivePostContentHandler}。</p>
 * <p>实现方式：其他插件将实现类注册为 Spring bean（{@code @Component}），并在其
 * {@code plugin.yaml} 中通过 {@code pluginDependencies} 依赖 {@code my-docs}，即可被自动发现。
 * my-docs 内置的短链改写与大纲提取始终作为链的最后一环执行，保证其基于最终 HTML。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
public interface DocContentHandler extends ExtensionPoint {

    /**
     * 处理当前正文内容。实现应读取 {@code context.getContent()}，改写后通过
     * {@code context.setContent(...)} 写回，并返回携带该 context 的 {@link Mono}。
     *
     * @param context 内容处理上下文，非空
     * @return 处理后的上下文
     */
    Mono<DocContentContext> handle(DocContentContext context);

    /**
     * 执行顺序，值越小越先执行。默认 0。
     *
     * @return 排序权重
     */
    default int getOrder() {
        return 0;
    }
}
