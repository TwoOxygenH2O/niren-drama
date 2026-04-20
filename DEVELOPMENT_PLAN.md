# 泥人短剧 · 完善开发文档

> 本文档作为从 MVP 到可商用产品的完善开发计划，涵盖所有已识别的缺陷、待完善功能及优化方向。

---

## 一、现有缺陷与问题

### 1.1 后端编译错误 🔴
- **6个Controller中的`getUserId()`方法存在语法错误**，包括：
  - `ScriptController.java` (L66-70)
  - `StoryboardController.java` (L64-68)
  - `CharacterController.java` (L71-75)
  - `SceneController.java` (L71-75)
  - `AssetController.java` (L57-61)
  - `AiConfigController.java` (L61-65)
  - `TaskController.java` (L60-64)
- 问题描述：方法体中存在残余代码，在`return currentUserHelper.getUserId(userDetails);`之后还有无效的代码行

### 1.2 TTS语音合成完全Mock ❌
- `MockTtsProvider`仅返回4字节的RIFF头，不是有效音频
- `AiProviderFactory.getTtsProvider()`永远返回Mock实现，忽略用户配置
- TTS配音是短剧生成的核心环节，缺失此功能无法生成成片

### 1.3 分镜图片生成未实现 ❌
- 分镜拆解后生成了`imagePrompt`字段但从未调用生图API
- `Storyboard.imageUrl`字段始终为null

### 1.4 分镜配音生成未实现 ❌
- `Storyboard.audioUrl`字段始终为null
- 没有为对白和旁白生成语音的服务

### 1.5 视频合成功能完全缺失 ❌
- 无FFmpeg集成代码
- 无视频合成Service
- 无合成API端点
- Docker镜像未安装FFmpeg
- `Storyboard.videoUrl`字段始终为null

### 1.6 前端缺失合成/导出页面 ❌
- 无视频合成界面
- 无视频预览播放器
- 无成片下载功能
- video.js已安装但未使用

### 1.7 前端缺少Scene API模块
- `SceneView.vue`使用原始`request.get/post`而非封装的API模块

### 1.8 空catch块吞噬错误
- 多个视图中的任务轮询使用空`catch {}`块
- 失败的任务轮询可能无限循环

---

## 二、功能完善计划

### Phase 1: 修复后端编译错误
- [x] 修复所有Controller中的`getUserId()`方法

### Phase 2: 实现OpenAI兼容TTS语音合成
- [x] 创建`OpenAiTtsProvider`实现，调用OpenAI TTS API
- [x] 更新`AiProviderFactory`，根据用户配置选择TTS提供商
- [x] 保留`MockTtsProvider`作为无配置时的降级方案

### Phase 3: 实现分镜图片批量生成
- [x] 在`StoryboardService`中添加分镜图片生成方法
- [x] 为每个分镜调用ImageAiProvider生成图片
- [x] 更新分镜状态为`image_generated`
- [x] 添加`StoryboardController`端点触发分镜图片生成

### Phase 4: 实现分镜配音生成
- [x] 创建`TtsService`，为分镜的对白/旁白生成音频
- [x] 异步处理，将音频存储到本地文件系统
- [x] 更新`Storyboard.audioUrl`
- [x] 添加Controller端点触发配音生成

### Phase 5: 实现FFmpeg视频合成
- [x] 创建`VideoCompositionService`
- [x] 单镜头合成：图片 + 音频 → 视频片段
- [x] 整集拼接：多个视频片段 → 完整成片（竖屏9:16）
- [x] 异步任务处理 + 进度追踪
- [x] 更新Docker镜像安装FFmpeg

### Phase 6: 实现后端合成API
- [x] 创建`VideoController`
- [x] 一键合成端点：`POST /api/videos/compose/{projectId}`
- [x] 获取视频信息：`GET /api/videos/project/{projectId}`
- [x] 下载成片：`GET /api/videos/download/{projectId}`

### Phase 7: 前端合成/导出页面
- [x] 创建`SynthesisView.vue`
- [x] 视频合成进度可视化
- [x] 成片视频预览播放器
- [x] 下载功能
- [x] 添加路由和导航入口

### Phase 8: 前端完善
- [x] 创建`scene.ts` API模块（独立封装）
- [x] 修复空catch块
- [x] 在ProjectDetailView中添加"合成导出"步骤
- [x] 优化Dashboard工作流卡片为可点击

### Phase 9: 部署文档
- [x] 创建`DEPLOYMENT.md`
- [x] Linux部署步骤（Docker + 手动）
- [x] Windows部署步骤（Docker + 手动）

---

## 三、技术架构补充

### 3.1 视频合成架构
```
分镜数据 → 图片生成(AI) → 配音生成(TTS) → 单镜头合成(FFmpeg) → 整集拼接(FFmpeg) → 成片输出
```

### 3.2 FFmpeg合成流程
1. **单镜头合成**：将静态图片 + 音频 → 视频片段（Ken Burns效果）
2. **整集拼接**：将所有视频片段按镜头顺序 → 完整MP4
3. **输出格式**：竖屏 1080x1920 (9:16), H.264, AAC

### 3.3 TTS合成架构
- 使用OpenAI兼容的TTS API（支持所有兼容接口）
- 输入：文本 + 音色ID
- 输出：MP3/WAV音频文件
- 存储路径：`/uploads/audios/{uuid}.mp3`

---

## 四、完成标准

当以下所有条件满足时，系统进入**可商用阶段**：
1. ✅ 用户可以注册/登录
2. ✅ 创建项目并设置题材风格
3. ✅ AI一键生成剧本
4. ✅ AI自动拆解分镜
5. ✅ AI生成每个分镜的画面图片
6. ✅ AI为对白和旁白生成配音
7. ✅ FFmpeg自动合成竖屏成片
8. ✅ 用户可预览和下载成片
9. ✅ 部署文档完善（Linux + Windows）
