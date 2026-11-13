package com.freightfox.dispatch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void ordersPathsByTheRequestFlowAndKeepsUnknownPathsAfterThem() {
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/extra", item(OpenApiConfig.DISPATCH_TAG))
                .addPathItem("/api/dispatch/plan", item(OpenApiConfig.DISPATCH_TAG))
                .addPathItem("/api/dispatch/vehicles", item(OpenApiConfig.DISPATCH_TAG))
                .addPathItem("/api/dispatch/orders", item(OpenApiConfig.DISPATCH_TAG)));

        config.readableOrder().customise(openApi);

        assertThat(openApi.getPaths().keySet()).containsExactly(
                "/api/dispatch/orders", "/api/dispatch/vehicles", "/api/dispatch/plan", "/extra");
    }

    @Test
    void listsOnlyTheGroupsThatHaveOperationsInTheDefinedOrder() {
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/dispatch/orders", item(OpenApiConfig.RESET_TAG))
                .addPathItem("/api/dispatch/plan", item(OpenApiConfig.DISPATCH_TAG)));

        config.readableOrder().customise(openApi);

        assertThat(openApi.getTags()).extracting(Tag::getName)
                .containsExactly(OpenApiConfig.DISPATCH_TAG, OpenApiConfig.RESET_TAG);
    }

    @Test
    void sortsSchemasByNameAndDocumentsRequiredStringsAsNonEmpty() {
        Schema<?> request = new ObjectSchema()
                .addProperty("id", new StringSchema().minLength(0).maxLength(64))
                .addProperty("note", new StringSchema().minLength(0).maxLength(10))
                .required(List.of("id"));
        OpenAPI openApi = new OpenAPI().components(new Components()
                .addSchemas("ZetaRequest", request)
                .addSchemas("AlphaResponse", new ObjectSchema()));

        config.readableOrder().customise(openApi);

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        assertThat(schemas.keySet()).containsExactly("AlphaResponse", "ZetaRequest");
        assertThat(request.getProperties().get("id").getMinLength()).isEqualTo(1);
        assertThat(request.getProperties().get("note").getMinLength()).as("optional string unchanged").isZero();
    }

    @Test
    void toleratesADocumentWithoutPathsOrSchemas() {
        OpenAPI openApi = new OpenAPI();

        config.readableOrder().customise(openApi);

        assertThat(openApi.getTags()).isEmpty();
        assertThat(openApi.getPaths()).isNull();
    }

    private static PathItem item(String tag) {
        return new PathItem().get(new Operation().addTagsItem(tag));
    }
}
