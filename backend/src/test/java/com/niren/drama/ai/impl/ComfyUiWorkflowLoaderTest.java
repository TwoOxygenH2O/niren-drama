package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComfyUiWorkflowLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertUiWorkflowSkipsMarkdownNotesAndMapsInputWidgets() throws Exception {
        ObjectNode workflow = (ObjectNode) mapper.readTree("""
                {
                  "nodes": [
                    {"id": 35, "type": "MarkdownNote", "inputs": [], "widgets_values": ["note"]},
                    {
                      "id": 57,
                      "type": "ExamplePromptNode",
                      "inputs": [
                        {"name": "text", "widget": {"name": "text"}},
                        {"name": "width", "widget": {"name": "width"}},
                        {"name": "height", "widget": {"name": "height"}}
                      ],
                      "widgets_values": ["old prompt", 1024, 768]
                    }
                  ],
                  "links": []
                }
                """);

        ObjectNode apiWorkflow = ComfyUiWorkflowLoader.convertUiToApiFormat(workflow);

        assertThat(apiWorkflow.has("35")).isFalse();
        assertThat(apiWorkflow.path("57").path("class_type").asText()).isEqualTo("ExamplePromptNode");
        assertThat(apiWorkflow.path("57").path("inputs").path("text").asText()).isEqualTo("old prompt");
        assertThat(apiWorkflow.path("57").path("inputs").path("width").asInt()).isEqualTo(1024);
        assertThat(apiWorkflow.path("57").path("inputs").path("height").asInt()).isEqualTo(768);
    }

    @Test
    void convertUiWorkflowExpandsSubgraphNodes() throws Exception {
        ObjectNode workflow = (ObjectNode) mapper.readTree("""
                {
                  "nodes": [
                    {
                      "id": 9,
                      "type": "SaveImage",
                      "inputs": [{"name": "images", "link": 62}],
                      "widgets_values": ["z-image-turbo"]
                    },
                    {
                      "id": 57,
                      "type": "subgraph-id",
                      "inputs": [
                        {"name": "text", "widget": {"name": "text"}},
                        {"name": "width", "widget": {"name": "width"}}
                      ],
                      "outputs": [{"name": "IMAGE", "links": [62]}]
                    }
                  ],
                  "links": [[62, 57, 0, 9, 0, "IMAGE"]],
                  "definitions": {
                    "subgraphs": [
                      {
                        "id": "subgraph-id",
                        "inputs": [
                          {"name": "text", "linkIds": [34]},
                          {"name": "width", "linkIds": [35]}
                        ],
                        "outputs": [{"name": "IMAGE", "linkIds": [16]}],
                        "nodes": [
                          {
                            "id": 27,
                            "type": "CLIPTextEncode",
                            "inputs": [
                              {"name": "text", "link": 34, "widget": {"name": "text"}}
                            ],
                            "widgets_values": ["old prompt"]
                          },
                          {
                            "id": 13,
                            "type": "EmptyLatentImage",
                            "inputs": [
                              {"name": "width", "link": 35, "widget": {"name": "width"}},
                              {"name": "height", "widget": {"name": "height"}}
                            ],
                            "widgets_values": [1024, 768, 1]
                          },
                          {
                            "id": 8,
                            "type": "VAEDecode",
                            "outputs": [{"name": "IMAGE"}],
                            "inputs": []
                          }
                        ],
                        "links": [
                          {"id": 34, "origin_id": -10, "origin_slot": 0, "target_id": 27, "target_slot": 0, "type": "STRING"},
                          {"id": 35, "origin_id": -10, "origin_slot": 1, "target_id": 13, "target_slot": 0, "type": "INT"},
                          {"id": 16, "origin_id": 8, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "IMAGE"}
                        ]
                      }
                    ]
                  }
                }
                """);

        ObjectNode apiWorkflow = ComfyUiWorkflowLoader.convertUiToApiFormat(workflow);

        assertThat(apiWorkflow.has("57")).isFalse();
        assertThat(apiWorkflow.path("57_27").path("class_type").asText()).isEqualTo("CLIPTextEncode");
        assertThat(apiWorkflow.path("57_27").path("inputs").path("text").asText()).isEqualTo("old prompt");
        assertThat(apiWorkflow.path("57_13").path("inputs").path("width").asInt()).isEqualTo(1024);
        assertThat(apiWorkflow.path("9").path("inputs").path("images").get(0).asText()).isEqualTo("57_8");
    }

    @Test
    void convertUiWorkflowMapsHunyuanVideoWidgets() throws Exception {
        ObjectNode workflow = (ObjectNode) mapper.readTree("""
                {
                  "nodes": [
                    {"id": 78, "type": "HunyuanVideo15ImageToVideo", "inputs": [], "widgets_values": [720, 1280, 1, 40]},
                    {"id": 79, "type": "CLIPVisionEncode", "inputs": [], "widgets_values": ["center"]},
                    {"id": 102, "type": "SaveVideo", "inputs": [], "widgets_values": ["h264", "ComfyUI"]},
                    {"id": 105, "type": "EasyCache", "inputs": [], "widgets_values": [true, 0.0, 0.15, 1.0]},
                    {"id": 126, "type": "BasicScheduler", "inputs": [], "widgets_values": ["simple", 20, 1.0]},
                    {"id": 129, "type": "CFGGuider", "inputs": [], "widgets_values": [6.5]},
                    {"id": 130, "type": "ModelSamplingSD3", "inputs": [], "widgets_values": [3.0]},
                    {"id": 101, "type": "CreateVideo", "inputs": [], "widgets_values": [16]},
                    {"id": 109, "type": "HunyuanVideo15LatentUpscaleWithModel", "inputs": [], "widgets_values": ["lanczos", 1080, 1920, "center"]},
                    {"id": 113, "type": "HunyuanVideo15SuperResolution", "inputs": [], "widgets_values": [0.05]},
                    {"id": 135, "type": "SplitSigmas", "inputs": [], "widgets_values": [8]}
                  ],
                  "links": []
                }
                """);

        ObjectNode apiWorkflow = ComfyUiWorkflowLoader.convertUiToApiFormat(workflow);

        assertThat(apiWorkflow.path("78").path("inputs").path("width").asInt()).isEqualTo(720);
        assertThat(apiWorkflow.path("78").path("inputs").path("height").asInt()).isEqualTo(1280);
        assertThat(apiWorkflow.path("78").path("inputs").path("batch_size").asInt()).isEqualTo(1);
        assertThat(apiWorkflow.path("78").path("inputs").path("length").asInt()).isEqualTo(40);
        assertThat(apiWorkflow.path("79").path("inputs").path("crop").asText()).isEqualTo("center");
        assertThat(apiWorkflow.path("102").path("inputs").path("format").asText()).isEqualTo("mp4");
        assertThat(apiWorkflow.path("102").path("inputs").path("codec").asText()).isEqualTo("h264");
        assertThat(apiWorkflow.path("102").path("inputs").path("filename_prefix").asText()).isEqualTo("ComfyUI");
        assertThat(apiWorkflow.path("105").path("inputs").path("verbose").asBoolean()).isTrue();
        assertThat(apiWorkflow.path("105").path("inputs").path("start_percent").asDouble()).isEqualTo(0.0);
        assertThat(apiWorkflow.path("105").path("inputs").path("reuse_threshold").asDouble()).isEqualTo(0.15);
        assertThat(apiWorkflow.path("105").path("inputs").path("end_percent").asDouble()).isEqualTo(1.0);
        assertThat(apiWorkflow.path("126").path("inputs").path("scheduler").asText()).isEqualTo("simple");
        assertThat(apiWorkflow.path("126").path("inputs").path("steps").asInt()).isEqualTo(20);
        assertThat(apiWorkflow.path("126").path("inputs").path("denoise").asDouble()).isEqualTo(1.0);
        assertThat(apiWorkflow.path("129").path("inputs").path("cfg").asDouble()).isEqualTo(6.5);
        assertThat(apiWorkflow.path("130").path("inputs").path("shift").asDouble()).isEqualTo(3.0);
        assertThat(apiWorkflow.path("101").path("inputs").path("fps").asInt()).isEqualTo(16);
        assertThat(apiWorkflow.path("109").path("inputs").path("upscale_method").asText()).isEqualTo("lanczos");
        assertThat(apiWorkflow.path("109").path("inputs").path("width").asInt()).isEqualTo(1080);
        assertThat(apiWorkflow.path("109").path("inputs").path("height").asInt()).isEqualTo(1920);
        assertThat(apiWorkflow.path("109").path("inputs").path("crop").asText()).isEqualTo("center");
        assertThat(apiWorkflow.path("113").path("inputs").path("noise_augmentation").asDouble()).isEqualTo(0.05);
        assertThat(apiWorkflow.path("135").path("inputs").path("step").asInt()).isEqualTo(8);
    }
}
