package com.github.mydocs.web;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocDetailModelHandler;
import com.github.mydocs.extensionpoint.DocModelContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * <p>my-docs 内置的阅读页自定义代码注入器，是 {@link DocDetailModelHandler} 扩展点的首个实现。</p>
 * <p>将三级自定义代码片段（全局设置、文档库、单篇文档）按 全局 → 库 → 文档 的顺序合并，
 * 分别注入到模板的 {@code mdocsCustomHeadHtml}（{@code <head>} 末尾）与
 * {@code mdocsCustomBodyHtml}（{@code <body>} 末尾）两个 model 属性。</p>
 * <p>层级按页面类型生效：全局对三页均生效；库级对 LIBRARY 与 DETAIL 页生效；文档级仅 DETAIL 页生效。
 * 片段以 {@code th:utext} 原样输出，安全责任由填写者（受管理权限保护的站点管理员）承担。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class CustomCodeInjectionHandler implements DocDetailModelHandler {

    static final String HEAD_MODEL_ATTRIBUTE = "mdocsCustomHeadHtml";
    static final String BODY_MODEL_ATTRIBUTE = "mdocsCustomBodyHtml";

    private final DocIndexSettingsService settingsService;

    public CustomCodeInjectionHandler(DocIndexSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public Mono<Void> handle(DocModelContext context) {
        return settingsService.fetch()
            .doOnNext(settings -> {
                var library = context.getLibrary();
                var doc = context.getDoc();

                context.getModel().addAttribute(HEAD_MODEL_ATTRIBUTE, merge(
                    settings.getCustomHeadHtml(),
                    librarySnippet(library, true),
                    docSnippet(doc, true)
                ));
                context.getModel().addAttribute(BODY_MODEL_ATTRIBUTE, merge(
                    settings.getCustomBodyHtml(),
                    librarySnippet(library, false),
                    docSnippet(doc, false)
                ));
            })
            .then();
    }

    private static String librarySnippet(DocLibrary library, boolean head) {
        if (library == null || library.getSpec() == null) {
            return null;
        }
        return head ? library.getSpec().getCustomHeadHtml() : library.getSpec().getCustomBodyHtml();
    }

    private static String docSnippet(Doc doc, boolean head) {
        if (doc == null || doc.getSpec() == null) {
            return null;
        }
        return head ? doc.getSpec().getCustomHeadHtml() : doc.getSpec().getCustomBodyHtml();
    }

    /**
     * 按传入顺序拼接非空片段，以换行分隔；全部为空时返回空串。
     */
    private static String merge(String... snippets) {
        List<String> parts = new ArrayList<>();
        for (String snippet : snippets) {
            if (StringUtils.hasText(snippet)) {
                parts.add(snippet.strip());
            }
        }
        return String.join("\n", parts);
    }
}
