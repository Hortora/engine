package io.hortora.garden.index;

import io.hortora.garden.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class QdrantClientProducer {

    @Inject
    QdrantConfig config;

    @Produces
    @ApplicationScoped
    QdrantClient qdrantClient() {
        var grpcClient = QdrantGrpcClient.newBuilder(config.host(), config.port(), false).build();
        return new QdrantClient(grpcClient);
    }

    void close(@Disposes QdrantClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
