$file = "d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
$content = Get-Content $file -Encoding UTF8 -Raw
$toReplace = @"
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt();
            String userPrompt = buildStoryboardUserPrompt(script.getContent());
            String storyboardJson = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 70,
"@
$replacement = @"
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt();
            StringBuilder simulatedBuffer = new StringBuilder();
            generateStoryboardPreviewByScenes(textProvider, systemPrompt, script.getContent(), request, chunk -> {
                simulatedBuffer.append(chunk);
            });
            String storyboardJson = simulatedBuffer.toString();

            updateTask(task, "RUNNING", 70,
"@
$newContent = $content.Replace($toReplace, $replacement)
Set-Content $file -Value $newContent -Encoding UTF8
