package com.github.mydocs.extensionpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import reactor.core.publisher.Mono;

class DocDetailModelHandlerAggregatorTest {

    @Test
    void appliesHandlersInOrderIntoSharedModel() {
        var calls = new ArrayList<String>();
        DocDetailModelHandler second = handler(20, context -> {
            calls.add("second");
            context.getModel().addAttribute("b", 2);
        });
        DocDetailModelHandler first = handler(10, context -> {
            calls.add("first");
            context.getModel().addAttribute("a", 1);
        });

        var aggregator =
            new DocDetailModelHandlerAggregator(new FakeExtensionGetter(List.of(second, first)));
        var model = new ConcurrentModel();

        aggregator.apply(null, model, DocPageType.DETAIL, null, null).block();

        assertThat(calls).containsExactly("first", "second");
        assertThat(model.asMap()).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void isolatesFailingHandlerAndContinues() {
        DocDetailModelHandler failing = handler(10, context -> {
            throw new IllegalStateException("boom");
        });
        DocDetailModelHandler healthy = handler(20, context ->
            context.getModel().addAttribute("ok", true));

        var aggregator =
            new DocDetailModelHandlerAggregator(new FakeExtensionGetter(List.of(failing, healthy)));
        var model = new ConcurrentModel();

        aggregator.apply(null, model, DocPageType.INDEX, null, null).block();

        assertThat(model.asMap()).containsEntry("ok", true);
    }

    @Test
    void completesWhenNoHandlers() {
        var aggregator =
            new DocDetailModelHandlerAggregator(new FakeExtensionGetter(List.of()));

        // 无扩展时应正常完成、不抛异常。
        aggregator.apply(null, new ConcurrentModel(), DocPageType.LIBRARY, null, null).block();
    }

    private static DocDetailModelHandler handler(int order,
        java.util.function.Consumer<DocModelContext> action) {
        return new DocDetailModelHandler() {
            @Override
            public Mono<Void> handle(DocModelContext context) {
                action.accept(context);
                return Mono.empty();
            }

            @Override
            public int getOrder() {
                return order;
            }
        };
    }
}
