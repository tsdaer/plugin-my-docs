package com.github.mydocs.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * <p>从 GitHub 拉取 Wiki 仓库内容：浅克隆 {@code https://github.com/{owner}/{repo}.wiki.git}
 * 并从 git 对象库读取全部 Markdown 页面。</p>
 * <p>GitHub 未提供 Wiki 的读取 API，Wiki 本身是一个独立 git 仓库，因此走 git 克隆；
 * 私有 Wiki 通过 PAT 令牌认证。克隆为阻塞操作，调度到 boundedElastic 线程池执行。</p>
 * <p>克隆使用 {@code setNoCheckout(true)} 并直接读取 HEAD 树中的 blob：GitHub Wiki 页面名
 * 可包含 Windows 工作区不允许的字符（如 {@code :}），跳过工作区 checkout 可在任何
 * 服务器操作系统上正确读取这类页面。</p>
 *
 * @author tsdaer
 * @since 1.3.0
 */
@Component
public class GithubWikiRepositoryFetcher {

    private static final Pattern REPO_INPUT_PATTERN = Pattern.compile(
        "^(?:https?://(?:www\\.)?github\\.com/)?([\\w.-]+)/([\\w.-]+?)(?:\\.wiki)?(?:\\.git)?/?$",
        Pattern.CASE_INSENSITIVE);
    private static final int CLONE_TIMEOUT_SECONDS = 120;
    private static final int MAX_PAGE_BYTES = 5 * 1024 * 1024;

    /**
     * 拉取指定仓库的 Wiki。
     *
     * @param repoInput 仓库标识，支持 {@code owner/repo}、{@code https://github.com/owner/repo}
     *                  与带 {@code .wiki[.git]} 后缀的完整地址
     * @param token     可选的 GitHub 访问令牌（PAT），私有 Wiki 必需
     * @return 以页面文件基本名（不含扩展名）为 key 的 Markdown 内容表
     */
    public Mono<Map<String, String>> fetch(String repoInput, String token) {
        return Mono.fromCallable(() -> doFetch(repoInput, token))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, String> doFetch(String repoInput, String token)
        throws GitAPIException, IOException {
        var repo = parseRepo(repoInput);
        Path tempDir = Files.createTempDirectory("my-docs-wiki-");
        try {
            CloneCommand command = Git.cloneRepository()
                .setURI("https://github.com/" + repo.owner() + "/" + repo.repo() + ".wiki.git")
                .setDirectory(tempDir.toFile())
                .setDepth(1)
                .setNoCheckout(true)
                .setTimeout(CLONE_TIMEOUT_SECONDS);
            if (StringUtils.hasText(token)) {
                command.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider(token.strip(), ""));
            }
            try (Git git = command.call()) {
                return readMarkdownPages(git.getRepository());
            }
        } catch (InvalidRemoteException | TransportException e) {
            throw new ServerWebInputException(friendlyCloneError(repo, e));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private RepoRef parseRepo(String repoInput) {
        String input = repoInput == null ? "" : repoInput.strip();
        var matcher = REPO_INPUT_PATTERN.matcher(input);
        if (!matcher.matches()) {
            throw new ServerWebInputException(
                "无法识别的仓库地址：" + input + "，请使用 owner/repo 或完整 GitHub 地址。");
        }
        return new RepoRef(matcher.group(1), matcher.group(2));
    }

    private String friendlyCloneError(RepoRef repo, Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("not found") || message.contains("does not appear to be a git")) {
            return "未找到 " + repo.owner() + "/" + repo.repo()
                + " 的 Wiki：请确认仓库存在且已启用（Wiki 无任何页面时 GitHub 不会创建 Wiki 仓库）。";
        }
        if (message.contains("403") || message.contains("401")
            || message.contains("authentication") || message.contains("authorization")) {
            return "无权限访问 " + repo.owner() + "/" + repo.repo()
                + " 的 Wiki：私有仓库需要填写具有读取权限的访问令牌。";
        }
        return "拉取 " + repo.owner() + "/" + repo.repo()
            + " 的 Wiki 失败（网络不通或超时）：" + e.getMessage();
    }

    /**
     * 直接遍历 HEAD 树读取 Markdown blob，不依赖工作区文件。
     */
    private Map<String, String> readMarkdownPages(Repository repository) throws IOException {
        Map<String, String> files = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ObjectId treeId = repository.resolve(Constants.HEAD + "^{tree}");
        if (treeId == null) {
            return files;
        }
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(treeId);
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) {
                    continue;
                }
                String baseName = lower.endsWith(".markdown")
                    ? fileName.substring(0, fileName.length() - ".markdown".length())
                    : fileName.substring(0, fileName.length() - ".md".length());
                var loader = repository.open(treeWalk.getObjectId(0));
                if (loader.getSize() > MAX_PAGE_BYTES) {
                    // 超大页面跳过，不中断整体导入。
                    continue;
                }
                files.putIfAbsent(baseName,
                    new String(loader.getBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响导入结果。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }

    private record RepoRef(String owner, String repo) {
    }
}
