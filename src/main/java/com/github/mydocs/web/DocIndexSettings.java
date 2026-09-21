package com.github.mydocs.web;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class DocIndexSettings {

    private Integer libraryIndexDefaultColumns = 2;

    private Integer libraryIndexDefaultMaxRows = 2;

    private List<LibraryPageLayout> libraryIndexPageLayouts = new ArrayList<>();

    private List<LibraryRowLayout> libraryIndexRowLayouts = new ArrayList<>();

    private List<LibraryPlacement> libraryIndexPlacements = new ArrayList<>();

    private List<LibraryFolderTitle> libraryIndexFolderTitles = new ArrayList<>();

    private String renderContentThemeLight;

    private String renderContentThemeDark;

    private String renderContentThemeLightUrl;

    private String renderContentThemeDarkUrl;

    private String renderContentThemeLightClass;

    private String renderContentThemeDarkClass;

    private String renderCodeThemeLight;

    private String renderCodeThemeDark;

    private Boolean renderLineNumber = false;

    private Boolean renderAutoSpace = false;

    private Boolean renderGfmAutoLink = true;

    private Boolean renderFootnotes = true;

    private Boolean renderMark = false;

    private Boolean renderFixTermTypo = false;

    private Boolean renderParagraphBeginningSpace = false;

    private Boolean renderCodeBlockPreview = true;

    private Boolean renderMathBlockPreview = true;

    private Boolean renderCopyButtons = true;

    private Boolean renderImageZoom = true;

    private Boolean renderMediaEmbed = true;

    /**
     * <p>同域媒体反代：把指定主机上的图片 / 音视频源改写成站点自身的
     * {@code /apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy} 地址，由服务端代取。</p>
     * <p>默认关闭。只有列在 {@link #mediaProxyAllowedHosts} 里的主机才会被代理，
     * 避免文档正文把站点变成任意地址的代理。</p>
     */
    private Boolean mediaProxyEnabled = false;

    /**
     * <p>允许代理的主机，每行一条，支持 {@code *.example.com} 这类前缀通配与可选端口。</p>
     * <p>设置页的多行输入框回传的是字符串，Halo 绑定成 List 后每行一个元素；
     * 这里保留 List 形态，由 {@code DocIndexSettingsService} 统一拆行。</p>
     */
    private List<String> mediaProxyAllowedHosts = new ArrayList<>();

    /**
     * 每条形如 {@code media.example.com: Authorization: Bearer xxx}，
     * 命中该主机（含通配）的代理请求在服务端补上这个头，用于私有对象存储。
     */
    private List<String> mediaProxyRequestHeaders = new ArrayList<>();

    /**
     * 需要 SigV4 签名的对象存储凭证，每行 {@code 主机: 键: 值}，
     * 键取 {@code access}、{@code secret} 与可选 {@code region}。
     * 与 {@link #mediaProxyRequestHeaders} 二选一：R2 / S3 不认静态 Bearer，只能用签名。
     */
    private List<String> mediaProxyCredentialRules = new ArrayList<>();

    private Integer mediaProxyMaxBytes = 536870912;

    // 仅用于读取旧版 ConfigMap，规范化后不再写入模板模型。
    private String renderContentTheme;

    private String renderCodeTheme;

    private String customHeadHtml = "";

    private String customBodyHtml = "";

    @Data
    public static class LibraryPageLayout {
        private Integer page;
        private Integer maxRows;
    }

    @Data
    public static class LibraryRowLayout {
        private Integer row;
        private Integer columns;
    }

    @Data
    public static class LibraryPlacement {
        private String libraryName;
        private Integer row;
        private Integer column;
    }

    @Data
    public static class LibraryFolderTitle {
        private Integer row;
        private Integer column;
        private String title;
        private String description;
    }
}
