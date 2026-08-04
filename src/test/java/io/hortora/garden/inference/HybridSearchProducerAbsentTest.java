package io.hortora.garden.inference;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.properties.StringValueMatch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchProducerAbsentTest {

    @Test
    void producerMethodHasLookupIfPropertyGuard() throws Exception {
        Method method = Arrays.stream(HybridSearchProducer.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("multiModalEmbedder"))
                .findFirst()
                .orElseThrow();
        LookupIfProperty annotation = method.getAnnotation(LookupIfProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("casehub.inference.models.bge-m3.model-path");
        assertThat(annotation.stringValue()).isEqualTo(".+");
        assertThat(annotation.match()).isEqualTo(StringValueMatch.REGEX);
    }
}
