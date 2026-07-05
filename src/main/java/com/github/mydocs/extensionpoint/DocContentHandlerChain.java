package com.github.mydocs.extensionpoint;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.Comparator;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import reactor.core.publisher.Mono;

/**
 * 聚合并按 {@link DocContentHandler#getOrder()} 顺序执行所有已启用的
 * {@link DocContentHandler}，形成正文 HTML 的责任链后处理。
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocContentHandlerChain {

    private final ExtensionGetter extensionGetter;

    public DocContentHandlerChain(ExtensionGetter extensionGetter) {
        this.extensionGetter = extensionGetter;
    }

    /**
     * 依次经所有已启用的内容处理扩展改写正文，返回最终 HTML。
     * 无任何扩展时为恒等变换，直接返回入参内容。
     *
     * @param content 初始正文 HTML
     * @param doc     所属文档（只读上下文）
     * @param library 所属文档库（只读上下文）
     * @return 处理后的正文 HTML
     */
    public Mono<String> handle(String content, Doc doc, DocLibrary library) {
        var context = DocContentContext.builder()
            .content(content)
            .doc(doc)
            .library(library)
            .build();
        return extensionGetter.getEnabledExtensions(DocContentHandler.class)
            .sort(Comparator.comparingInt(DocContentHandler::getOrder))
            .reduce(Mono.just(context),
                (contextMono, handler) -> contextMono.flatMap(handler::handle))
            .flatMap(contextMono -> contextMono)
            .map(DocContentContext::getContent);
    }
}
