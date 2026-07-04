package com.github.mydocs.service;

import com.github.mydocs.extension.Doc;
import reactor.core.publisher.Mono;

/**
 * <p>文档业务服务。</p>
 * <p>在创建/更新文档时执行应用层校验（如 slug 库内唯一），再委托
 * {@link run.halo.app.extension.ReactiveExtensionClient} 完成持久化。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
public interface DocService {

    /**
     * 创建文档，创建前校验 slug 在所属库内唯一。
     */
    Mono<Doc> create(Doc doc);

    /**
     * 更新文档，更新前校验 slug 在所属库内唯一（排除自身）。
     */
    Mono<Doc> update(Doc doc);
}
