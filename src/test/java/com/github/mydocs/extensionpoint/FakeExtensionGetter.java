package com.github.mydocs.extensionpoint;

import java.util.List;
import org.pf4j.ExtensionPoint;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * 测试用的 {@link ExtensionGetter} 假实现：{@code getEnabledExtensions} 返回构造时给定的固定列表。
 */
class FakeExtensionGetter implements ExtensionGetter {

    private final List<? extends ExtensionPoint> extensions;

    FakeExtensionGetter(List<? extends ExtensionPoint> extensions) {
        this.extensions = extensions;
    }

    @Override
    public <T extends ExtensionPoint> Mono<T> getEnabledExtension(Class<T> extensionPoint) {
        return Mono.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ExtensionPoint> Flux<T> getEnabledExtensions(Class<T> extensionPoint) {
        return (Flux<T>) Flux.fromIterable(extensions);
    }

    @Override
    public <T extends ExtensionPoint> Flux<T> getExtensions(Class<T> extensionPointClass) {
        return Flux.empty();
    }

    @Override
    public <T extends ExtensionPoint> List<T> getExtensionList(Class<T> extensionPointClass) {
        return List.of();
    }
}
