package com.github.mydocs.extensionpoint;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import lombok.Builder;
import lombok.Value;
import org.springframework.ui.Model;
import org.springframework.web.server.ServerWebExchange;

/**
 * <p>{@link DocDetailModelHandler} 的上下文，用于向阅读页模板注入额外数据/片段。</p>
 * <p>handler 通过 {@link #getModel()} 向模板 model 添加属性（建议使用带插件前缀的键名以避免冲突，
 * 如 {@code model.addAttribute("myPlugin_relatedDocs", ...)}）。</p>
 * <p>{@code pageType} 指示当前页面类型；{@code library} 与 {@code doc} 按页面类型可能为空：
 * INDEX 页两者皆空，LIBRARY 页有 {@code library}、无 {@code doc}，DETAIL 页两者皆有。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Value
@Builder
public class DocModelContext {

    /**
     * 当前请求交换对象。
     */
    ServerWebExchange exchange;

    /**
     * 待注入的模板 model。
     */
    Model model;

    /**
     * 当前页面类型。
     */
    DocPageType pageType;

    /**
     * 当前文档库，INDEX 页为 {@code null}。
     */
    DocLibrary library;

    /**
     * 当前文档，仅 DETAIL 页非空。
     */
    Doc doc;
}
