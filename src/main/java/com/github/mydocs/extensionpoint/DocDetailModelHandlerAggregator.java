package com.github.mydocs.extensionpoint;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.server.ServerWebExchange;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import reactor.core.publisher.Mono;

/**
 * 聚合并按 {@link DocDetailModelHandler#getOrder()} 顺序执行所有已启用的
 * {@link DocDetailModelHandler}，将额外数据注入同一个阅读页 {@link Model}。
 * 单个 handler 抛错不会中断整体渲染，仅记录日志后跳过。
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocDetailModelHandlerAggregator {

    private static final Logger log =
        LoggerFactory.getLogger(DocDetailModelHandlerAggregator.class);

    private final ExtensionGetter extensionGetter;

    public DocDetailModelHandlerAggregator(ExtensionGetter extensionGetter) {
        this.extensionGetter = extensionGetter;
    }

    /**
     * 依次调用所有已启用的 model 注入扩展。无任何扩展时立即完成，不改动 model。
     *
     * @param exchange 当前请求交换对象
     * @param model    待注入的模板 model
     * @param pageType 当前页面类型
     * @param library  当前文档库，INDEX 页可为 {@code null}
     * @param doc      当前文档，仅 DETAIL 页非空
     * @return 全部注入完成信号
     */
    public Mono<Void> apply(ServerWebExchange exchange, Model model, DocPageType pageType,
        DocLibrary library, Doc doc) {
        var context = DocModelContext.builder()
            .exchange(exchange)
            .model(model)
            .pageType(pageType)
            .library(library)
            .doc(doc)
            .build();
        return extensionGetter.getEnabledExtensions(DocDetailModelHandler.class)
            .sort(Comparator.comparingInt(DocDetailModelHandler::getOrder))
            .concatMap(handler -> Mono.defer(() -> handler.handle(context))
                .onErrorResume(error -> {
                    log.error("文档阅读页 model 注入扩展 [{}] 执行失败，已跳过。",
                        handler.getClass().getName(), error);
                    return Mono.empty();
                }))
            .then();
    }
}
