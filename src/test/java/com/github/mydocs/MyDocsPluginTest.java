package com.github.mydocs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class MyDocsPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @InjectMocks
    MyDocsPlugin plugin;

    @Test
    void startRegistersExtensions() {
        plugin.start();
        verify(schemeManager).register(eq(DocLibrary.class), any(Consumer.class));
        verify(schemeManager).register(eq(Doc.class), any(Consumer.class));
    }

    @Test
    void stopUnregistersExtensions() {
        plugin.stop();
        // Doc 与 DocLibrary 各注销一次
        verify(schemeManager, org.mockito.Mockito.times(2)).unregister(any());
    }
}
