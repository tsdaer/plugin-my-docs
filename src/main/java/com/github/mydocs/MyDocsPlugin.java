package com.github.mydocs;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.search.event.HaloDocumentRebuildRequestEvent;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class MyDocsPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final ApplicationEventPublisher eventPublisher;

    public MyDocsPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        ApplicationEventPublisher eventPublisher) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void start() {
        schemeManager.register(DocLibrary.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<DocLibrary, String>single("spec.slug", String.class)
                .unique(true)
                .nullable(false)
                .indexFunc(lib -> lib.getSpec().getSlug()));
            indexSpecs.add(IndexSpecs.<DocLibrary, Integer>single("spec.priority", Integer.class)
                .indexFunc(lib -> lib.getSpec().getPriority()));
        });

        schemeManager.register(Doc.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<Doc, String>single("spec.slug", String.class)
                .indexFunc(doc -> doc.getSpec().getSlug()));
            indexSpecs.add(IndexSpecs.<Doc, String>single("spec.libraryName", String.class)
                .indexFunc(doc -> doc.getSpec().getLibraryName()));
            indexSpecs.add(IndexSpecs.<Doc, String>single("spec.parent", String.class)
                .indexFunc(doc -> doc.getSpec().getParent()));
            indexSpecs.add(IndexSpecs.<Doc, Integer>single("spec.priority", Integer.class)
                .indexFunc(doc -> doc.getSpec().getPriority()));
        });
        eventPublisher.publishEvent(new HaloDocumentRebuildRequestEvent(this));
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(Doc.class));
        schemeManager.unregister(Scheme.buildFromType(DocLibrary.class));
    }
}
