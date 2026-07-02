package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ComfyUiImageProviderTest {

    @Test
    void buildWorkflowRandomizesTemplateSamplerSeedsToAvoidEmptyComfyCacheReuse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode workflow = objectMapper.createObjectNode();

        ObjectNode sampler = workflow.putObject("5");
        sampler.put("class_type", "KSampler");
        ObjectNode samplerInputs = sampler.putObject("inputs");
        samplerInputs.put("seed", 0);
        samplerInputs.put("steps", 8);

        ObjectNode advancedSampler = workflow.putObject("6");
        advancedSampler.put("class_type", "KSamplerAdvanced");
        ObjectNode advancedInputs = advancedSampler.putObject("inputs");
        advancedInputs.put("noise_seed", 0);
        advancedInputs.put("steps", 8);

        ObjectNode latent = workflow.putObject("7");
        latent.put("class_type", "EmptySD3LatentImage");
        ObjectNode latentInputs = latent.putObject("inputs");
        latentInputs.put("width", 512);
        latentInputs.put("height", 512);
        latentInputs.put("batch_size", 1);

        ObjectNode positive = workflow.putObject("8");
        positive.put("class_type", "CLIPTextEncode");
        ObjectNode positiveInputs = positive.putObject("inputs");
        positiveInputs.put("text", "positive");
        positiveInputs.putArray("clip").add("2").add(0);

        ObjectNode zeroNegative = workflow.putObject("9");
        zeroNegative.put("class_type", "ConditioningZeroOut");
        ObjectNode zeroNegativeInputs = zeroNegative.putObject("inputs");
        zeroNegativeInputs.putArray("conditioning").add("8").add(0);

        ObjectNode extra = objectMapper.createObjectNode();
        extra.set("workflow", workflow);

        ComfyUiImageProvider provider = new ComfyUiImageProvider(
                "http://127.0.0.1:8188",
                "",
                "",
                objectMapper.writeValueAsString(extra),
                "",
                "");

        Method buildWorkflow = ComfyUiImageProvider.class.getDeclaredMethod(
                "buildWorkflow", String.class, String.class, String.class, String.class);
        buildWorkflow.setAccessible(true);

        ObjectNode result = (ObjectNode) buildWorkflow.invoke(provider, "prompt", "1024x1792", "vivid", "negative");

        assertThat(result.path("5").path("inputs").path("seed").asInt()).isGreaterThan(0);
        assertThat(result.path("6").path("inputs").path("noise_seed").asInt()).isGreaterThan(0);
        assertThat(result.path("7").path("inputs").path("width").asInt()).isEqualTo(1024);
        assertThat(result.path("7").path("inputs").path("height").asInt()).isEqualTo(1792);
        assertThat(result.path("9").path("class_type").asText()).isEqualTo("CLIPTextEncode");
        assertThat(result.path("9").path("inputs").path("text").asText()).isEqualTo("negative");
    }

    @Test
    void buildWorkflowUsesBundledImageTemplateWhenWorkflowFileIsNotConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/userdata", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            byte[] body;
            if (rawPath.contains("workflows%2Fbad_image_portrait_grid.json")
                    || rawPath.contains("workflows/bad_image_portrait_grid.json")) {
                body = """
                        {
                          "99": {
                            "class_type": "SaveImage",
                            "inputs": {
                              "filename_prefix": "bad_user_workflow"
                            }
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                body = "[\"bad_image_portrait_grid.json\"]".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ComfyUiImageProvider provider = new ComfyUiImageProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    "",
                    null,
                    "",
                    "");

            Method buildWorkflow = ComfyUiImageProvider.class.getDeclaredMethod(
                    "buildWorkflow", String.class, String.class, String.class, String.class);
            buildWorkflow.setAccessible(true);

            ObjectNode result = (ObjectNode) buildWorkflow.invoke(provider,
                    "short drama first frame",
                    "1024x1792",
                    "vivid",
                    "negative");

            assertThat(result.path("1").path("class_type").asText()).isEqualTo("UNETLoader");
            assertThat(result.path("10").path("inputs").path("filename_prefix").asText())
                    .isEqualTo("niren_z_image_turbo");
            assertThat(result.path("99").isMissingNode()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
