package com.github.mydocs.importer;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>GitHub Wiki（Gollum）Markdown 到 my-docs Markdown 的纯文本转换器。</p>
 * <p>负责两类转换：</p>
 * <ul>
 *   <li>页面文件名 → 访问别名（slug）与同库短链接所需的页面名映射；</li>
 *   <li>Wiki 链接语法 {@code [[Page]]} / {@code [[Page|文字]]} / {@code [[Page#锚点]]}
 *       → my-docs 同库短链接 {@code [文字](./slug#锚点)}（由前台
 *       {@code DocDetailContentBuilder} 解析为 {@code /docs/{librarySlug}/{slug}}）。</li>
 * </ul>
 * <p>锚点格式与 commonmark {@code heading-anchor} 扩展保持一致：
 * 先小写、ASCII 空格转连字符，再仅保留 Unicode 字母数字、下划线与连字符。</p>
 *
 * @author tsdaer
 * @since 1.3.0
 */
public final class GithubWikiMarkdownConverter {

    // 显示文字允许包含单个 ]（如 [注意]），仅以 ]] 结束整个链接；目标页面名不允许括号与竖线。
    private static final Pattern WIKI_LINK_PATTERN =
        Pattern.compile("\\[\\[([^\\[\\]|]+)(?:\\|((?:[^\\]]|\\](?!\\]))+))?\\]\\]");
    private static final Pattern EXTERNAL_LINK_PATTERN =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    private static final Pattern FENCE_OPEN_PATTERN =
        Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final int MAX_SLUG_LENGTH = 100;

    private GithubWikiMarkdownConverter() {
    }

    /**
     * 判断是否为 GitHub Wiki 的特殊文件（侧栏 / 页眉 / 页脚），导入时应跳过。
     */
    public static boolean isSpecialFile(String baseName) {
        if (baseName == null) {
            return false;
        }
        var lower = baseName.toLowerCase(Locale.ROOT);
        return lower.equals("_sidebar") || lower.equals("_header") || lower.equals("_footer");
    }

    /**
     * 将页面文件名（不含扩展名）转为库内访问别名：小写、连续非字母数字段折叠为单个连字符，
     * 保留 Unicode 字母数字（如中文），截断至 100 个字符；结果为空时回退 {@code wiki-page}。
     */
    public static String slugify(String baseName) {
        var slug = (baseName == null ? "" : baseName).trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "-");
        if (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        if (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
            // 截断可能产生悬挂连字符，收尾再清理一次。
            if (slug.endsWith("-")) {
                slug = slug.substring(0, slug.length() - 1);
            }
        }
        return slug.isEmpty() ? "wiki-page" : slug;
    }

    /**
     * 与 commonmark {@code heading-anchor} 扩展的 id 生成规则对齐：先小写、空格转连字符，
     * 再仅保留 Unicode 字母数字、下划线与连字符（其余字符直接丢弃）。
     */
    public static String anchorSlug(String headingText) {
        if (headingText == null) {
            return "";
        }
        var normalized = headingText.toLowerCase(Locale.ROOT).replace(" ", "-");
        StringBuilder builder = new StringBuilder(normalized.length());
        normalized.chars().forEach(c -> {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                builder.append((char) c);
            }
        });
        return builder.toString();
    }

    /**
     * 规范化页面名作为链接解析 key：小写、空格视同连字符（Gollum 的页面文件名以连字符替代空格）。
     */
    public static String normalizePageKey(String pageName) {
        return (pageName == null ? "" : pageName).trim()
            .replace(' ', '-')
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 转换一篇 Wiki 正文中的 Wiki 链接。围栏代码块（``` / ~~~）内的链接保持原样，
     * 行内代码（反引号包裹段）内的链接同样不转换。
     *
     * @param markdown       Wiki 正文 Markdown
     * @param pageSlugLookup 由规范化的页面名（{@link #normalizePageKey}）解析到最终 slug 的函数，
     *                       解析不到返回 null
     * @return 转换结果：正文与未解析链接数
     */
    public static Conversion convertLinks(String markdown,
        Function<String, String> pageSlugLookup) {
        var context = new LinkContext(pageSlugLookup);
        StringBuilder out = new StringBuilder(markdown.length());
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;

        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE_OPEN_PATTERN.matcher(line);
            if (fence.matches()) {
                String fenceText = fence.group(1);
                char currentChar = fenceText.charAt(0);
                int currentLength = fenceText.length();
                if (!inFence) {
                    inFence = true;
                    fenceChar = currentChar;
                    fenceLength = currentLength;
                } else if (currentChar == fenceChar
                    && currentLength >= fenceLength
                    && isPlainClosingFence(line, fenceChar)) {
                    inFence = false;
                }
                out.append(line);
            } else if (inFence) {
                out.append(line);
            } else {
                out.append(convertLineLinks(line, context));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return new Conversion(out.toString(), context.unresolvedLinks);
    }

    /**
     * 关闭围栏所在行只允许缩进 + 围栏字符 + 尾随空白（CommonMark 规则），避免把
     * {@code ```code```} 这类行内围栏误判为代码块边界。
     */
    private static boolean isPlainClosingFence(String line, char fenceChar) {
        String fenceStr = String.valueOf(fenceChar);
        return line.strip().replace(fenceStr, "").isEmpty();
    }

    private static String convertLineLinks(String line, LinkContext context) {
        // 按行内代码段（反引号包裹）切分：偶数段为普通文本，奇数段为行内代码，保持原样。
        String[] segments = line.split("`", -1);
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < segments.length; i++) {
            if (i % 2 == 1) {
                out.append('`').append(segments[i]);
                if (i < segments.length - 1) {
                    out.append('`');
                }
                continue;
            }
            out.append(replaceWikiLinks(segments[i], context));
        }
        return out.toString();
    }

    private static String replaceWikiLinks(String text, LinkContext context) {
        Matcher matcher = WIKI_LINK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            out.append(convertLink(matcher.group(1), matcher.group(2), context));
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String convertLink(String target, String display, LinkContext context) {
        String trimmedTarget = target == null ? "" : target.trim();

        if (EXTERNAL_LINK_PATTERN.matcher(trimmedTarget).matches()) {
            String displayText = (display == null || display.isBlank())
                ? trimmedTarget : display.trim();
            return "[" + escapeLabel(displayText) + "](" + trimmedTarget + ")";
        }

        String pageName = trimmedTarget;
        String anchor = null;
        int hashIndex = trimmedTarget.indexOf('#');
        if (hashIndex >= 0) {
            pageName = trimmedTarget.substring(0, hashIndex);
            anchor = trimmedTarget.substring(hashIndex + 1);
        }
        // 无显式显示文字时默认使用完整目标；锚点为空（如 [[Home#]]）则退回页面名，避免悬挂 #。
        String displayText = (display == null || display.isBlank())
            ? (anchor != null && !anchor.isBlank() ? trimmedTarget : pageName.trim())
            : display.trim();
        String slug = context.pageSlugLookup.apply(normalizePageKey(pageName));
        if (slug == null) {
            context.unresolvedLinks++;
            return escapeLabel(displayText);
        }
        StringBuilder link = new StringBuilder("[").append(escapeLabel(displayText))
            .append("](./").append(slug);
        if (anchor != null && !anchor.isBlank()) {
            link.append('#').append(anchorSlug(anchor));
        }
        return link.append(')').toString();
    }

    private static String escapeLabel(String label) {
        return label.replace("[", "\\[").replace("]", "\\]");
    }

    /**
     * 单次正文转换过程中共享的解析上下文。
     */
    private static final class LinkContext {
        final Function<String, String> pageSlugLookup;
        int unresolvedLinks;

        LinkContext(Function<String, String> pageSlugLookup) {
            this.pageSlugLookup = pageSlugLookup;
        }
    }

    /**
     * 单篇正文的转换结果。
     *
     * @param markdown        转换后的 Markdown
     * @param unresolvedLinks 未解析（找不到对应页面）的 Wiki 链接数量
     */
    public record Conversion(String markdown, int unresolvedLinks) {
    }
}
