package com.github.mydocs.extensionpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DocContentHandlerChainTest {

    @Test
    void returnsInputUnchangedWhenNoHandlers() {
        var chain = new DocContentHandlerChain(new FakeExtensionGetter(List.of()));

        assertThat(chain.handle("original", null, null).block()).isEqualTo("original");
    }

    @Test
    void appliesHandlersInOrderFormingAChain() {
        // order 值故意乱序注册，验证按 getOrder 升序串联执行。
        DocContentHandler wrapB = handler(20, content -> "[B]" + content + "[/B]");
        DocContentHandler wrapA = handler(10, content -> "[A]" + content + "[/A]");

        var chain = new DocContentHandlerChain(new FakeExtensionGetter(List.of(wrapB, wrapA)));

        // A(order 10) 先执行，B(order 20) 后执行并包裹 A 的结果。
        assertThat(chain.handle("x", null, null).block()).isEqualTo("[B][A]x[/A][/B]");
    }

    private static DocContentHandler handler(int order, java.util.function.UnaryOperator<String> op) {
        return new DocContentHandler() {
            @Override
            public Mono<DocContentContext> handle(DocContentContext context) {
                context.setContent(op.apply(context.getContent()));
                return Mono.just(context);
            }

            @Override
            public int getOrder() {
                return order;
            }
        };
    }
}
