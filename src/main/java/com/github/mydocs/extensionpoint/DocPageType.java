package com.github.mydocs.extensionpoint;

/**
 * 文档阅读页的页面类型，供 {@link DocDetailModelHandler} 区分注入场景。
 *
 * @author tsdaer
 * @since 1.0.0
 */
public enum DocPageType {

    /**
     * 文档总索引页：{@code /docs}。
     */
    INDEX,

    /**
     * 文档库页：{@code /docs/{librarySlug}}。
     */
    LIBRARY,

    /**
     * 文档详情页：{@code /docs/{librarySlug}/{docSlug}}。
     */
    DETAIL
}
