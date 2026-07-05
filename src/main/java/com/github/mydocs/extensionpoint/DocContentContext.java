package com.github.mydocs.extensionpoint;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import lombok.Builder;
import lombok.Data;

/**
 * <p>{@link DocContentHandler} 责任链的可变载体。</p>
 * <p>每个 handler 读取 {@link #getContent()}（当前正文 HTML），处理后通过
 * {@link #setContent(String)} 写回改写结果，再交给下一个 handler，从而形成链式后处理。</p>
 * <p>{@code doc} 与 {@code library} 为只读上下文，供 handler 依据文档/库信息决定如何改写，
 * handler 不应修改它们。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Data
@Builder
public class DocContentContext {

    /**
     * 当前正文 HTML。handler 处理后应写回此字段。
     */
    private String content;

    /**
     * 正文所属文档（只读上下文）。
     */
    private final Doc doc;

    /**
     * 文档所属的文档库（只读上下文）。
     */
    private final DocLibrary library;
}
