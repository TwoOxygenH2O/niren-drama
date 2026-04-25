package com.niren.drama.common;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ProjectStyleSupport {

    public static final String LIVE_ACTION = "真人短剧";
    public static final String COMIC = "漫画短剧";
    private static final String DEFAULT_GENRE = "都市言情";

    private ProjectStyleSupport() {
    }

    public static String resolveProjectType(String rawProjectType) {
        String normalized = StringUtils.trimToEmpty(rawProjectType);
        if (normalized.contains("漫画") || normalized.contains("动漫") || normalized.contains("二次元")) {
            return COMIC;
        }
        return LIVE_ACTION;
    }

    public static boolean isComicProjectType(String rawProjectType) {
        return COMIC.equals(resolveProjectType(rawProjectType));
    }

    public static String resolveGenre(String rawGenre) {
        String normalized = StringUtils.trimToEmpty(rawGenre);
        if (normalized.isBlank()) {
            return DEFAULT_GENRE;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "romance" -> "都市言情";
            case "fantasy" -> "玄幻奇幻";
            case "thriller" -> "悬疑惊悚";
            case "urban" -> "都市职场";
            case "historical" -> "古装历史";
            case "comedy" -> "喜剧搞笑";
            default -> normalized;
        };
    }

    public static String buildProjectIdentity(String projectType, String genre) {
        return "项目类型：" + resolveProjectType(projectType) + "\n题材：" + resolveGenre(genre);
    }

    public static String buildTextCreationRules(String projectType, String genre) {
        String resolvedType = resolveProjectType(projectType);
        String resolvedGenre = resolveGenre(genre);
        return String.format("""
                - 项目类型：%s
                - 题材：%s
                - 类型约束：%s
                - 题材约束：%s
                - 台词与旁白（短剧可演性，必须遵守）：
                %s
                """,
                resolvedType,
                resolvedGenre,
                buildTextTypeRule(resolvedType),
                buildGenreStoryRule(resolvedGenre),
                buildDramaDialogueRules());
    }

    /**
     * 写进分集大纲/剧本/分镜提示中，统一约束「真短剧」台词腔与字段职责，避免小说说明体与伪换行。
     */
    public static String buildDramaDialogueRules() {
        return """
                1) 禁止小说体：大段心理、环境散文、“他开口说道/冷冷地看着/心里一沉”等说明不要写进 dialogue。镜头调度与站位只写进 description 一句短提示。
                2) dialogue 只写角色嘴里的口语，短句、能一口气念完；一句台词一个镜头、整句时长约等于本镜 duration；单镜尽量不超过 5 秒、单句别超过约 20 个汉字（金句可略长）。
                3) 角色名与情绪/音色不写在会「上屏读出来」的旁白里；导演与情绪可写在 ttsText（更口语的念稿）或让系统用 characterName+演绎指令处理；默认上屏用 subtitleText 或干净对白（不要读角色名+情绪头）。
                4) 短剧优先少旁白；narration 仅用于极少数 VO/OS、系统提示类画外，不要拿 narration 当小说解说；不要与 dialogue 同句重复；若可合并则只留 dialogue。
                5) 两人/多人对话拆成多镜短句，一来一回，不要一镜堆长段台词。
                6) 不要在 dialogue 里写“角色名：”前缀；说话人用 characterName 字段，避免上屏/配音再念人名。
                7) 不要输出字面量换行符 \\n；停顿用短句与标点。
                """;
    }

    /**
     * 分集/单集剧作层面的竖屏短剧节拍，用于控节奏与成本（少拍废镜）。
     */
    public static String buildShortDramaBeatBlock() {
        return """
                ## 短剧黄金节拍与体量（每集须内化，数值为目标而非秒表硬卡）
                - 约 3 秒内要出现钩子/悬念/冲击点；约 10 秒内需出现可见冲突；约每 30 秒有爽点/反转或信息升级；单集建议 120-180 秒，节奏密、告别小说朗读体。
                - 开场 0-15 秒强钩子，不要长铺垫；集末 20-30 秒强悬念，利于续看。
                - 单场 2-5 个视觉节拍；整集镜头数 20-45 个区间较省成本，不堆无意义分镜；单镜时长默认 2-5 秒（与一句口语对齐）。
                """;
    }

    public static String buildScriptSelfCheckBlock() {
        return """
                ## 输出前自检（不满足则重写）
                - 台词是否口语短句，单句尽量 <= 20 字，禁止“他冷冷地看着/心里一沉/开口说道”等小说说明腔。
                - 单场推进是否围绕冲突，不做长段环境散文或心理描写。
                - 开场是否在前 3-10 秒建立钩子与冲突，结尾是否有强钩子。
                - 本集体量是否贴近 120-180 秒，不偏离本集大纲和项目通用设定。
                """;
    }

    public static String buildStoryboardSelfCheckBlock() {
        return """
                ## 输出前自检（不满足则重写）
                - duration 必须在 1-5 秒；一句台词对应一个镜头。
                - dialogue 仅口播台词，不写角色名前缀、不写心理或旁白解释。
                - subtitleText 若有值，不得含角色名和情绪头；ttsText 若有标签需可被后处理剥离。
                - narration 仅用于极少数 VO/OS，且不得与 dialogue 同句重复。
                - imagePrompt 与 videoPrompt 必须服务当前镜头，不得跑题或重复冗长描述。
                """;
    }

    public static String buildNovelToneBlacklistFewShot() {
        return """
                ## 小说腔禁用词与替代示例
                - 禁用表达：他冷冷地看着、心里一沉、开口说道、她在心中想、空气仿佛凝固、沉默了很久
                - 错误示例：林辰冷冷地看着苏晴，心里满是愤怒，他开口说道：你给我滚出去
                - 正确示例（对白）：你给我滚出去！
                - 正确示例（镜头提示）：近景，对峙，林辰压着怒气，苏晴后退半步
                """;
    }

    public static String buildVisualCreationRules(String projectType, String genre) {
        String resolvedType = resolveProjectType(projectType);
        String resolvedGenre = resolveGenre(genre);
        return String.format("""
                - 项目类型：%s
                - 题材：%s
                - 视觉基调：%s
                - 题材美术：%s
                - 统一要求：人物外貌、服装识别点、场景时代感和道具系统必须前后一致，不能混入其他题材的穿帮元素。
                """,
                resolvedType,
                resolvedGenre,
                buildVisualTypeRule(resolvedType),
                buildGenreVisualRule(resolvedGenre));
    }

    public static String buildAudioPerformanceRules(String projectType, String genre) {
        String resolvedType = resolveProjectType(projectType);
        String resolvedGenre = resolveGenre(genre);
        return String.format("""
                - 项目类型：%s
                - 题材：%s
                - 演绎方式：%s
                - 题材口吻：%s
                """,
                resolvedType,
                resolvedGenre,
                buildAudioTypeRule(resolvedType),
                buildGenreAudioRule(resolvedGenre));
    }

    public static String buildVisualNegativeTerms(String projectType, String genre) {
        Set<String> negativeTerms = new LinkedHashSet<>();
        if (isComicProjectType(projectType)) {
            negativeTerms.add("低幼卡通");
            negativeTerms.add("儿童绘本风");
            negativeTerms.add("粗糙涂鸦");
            negativeTerms.add("真人照片质感过强");
        } else {
            negativeTerms.add("动漫风");
            negativeTerms.add("二次元");
            negativeTerms.add("插画风");
            negativeTerms.add("游戏CG");
            negativeTerms.add("Q版");
            negativeTerms.add("塑料皮肤");
            negativeTerms.add("蜡像感");
        }

        String resolvedGenre = resolveGenre(genre);
        if (containsAny(resolvedGenre, "民国")) {
            addAll(negativeTerms, "现代写字楼", "卫衣", "牛仔裤", "运动鞋", "霓虹灯牌", "现代汽车");
        } else if (containsAny(resolvedGenre, "古装", "历史")) {
            addAll(negativeTerms, "西装", "衬衫领带", "汽车", "现代建筑", "电线杆", "现代发型");
        } else if (containsAny(resolvedGenre, "仙侠", "玄幻")) {
            addAll(negativeTerms, "办公室工位", "现代商场", "都市职业装", "现代街景");
        } else if (containsAny(resolvedGenre, "校园", "青春")) {
            addAll(negativeTerms, "商务酒会造型", "过度成熟妆容", "厚重宫廷服饰");
        } else if (containsAny(resolvedGenre, "都市", "职场", "言情", "家庭")) {
            addAll(negativeTerms, "古装", "盔甲", "仙侠法器", "宫廷陈设");
        }
        return String.join("，", negativeTerms);
    }

    private static String buildTextTypeRule(String resolvedType) {
        if (COMIC.equals(resolvedType)) {
            return "允许漫画改编式高张力、强反差和更利落的爽点节奏，但人物动机必须清楚，不能只剩夸张桥段。";
        }
        return "所有人物、冲突、对白和情绪都要以真人演员可落地表演为标准，避免二次元式夸张反应、悬浮台词和不真实肢体表现。";
    }

    private static String buildVisualTypeRule(String resolvedType) {
        if (COMIC.equals(resolvedType)) {
            return "允许使用高完成度漫画短剧/条漫改编风格，可半写实插画或国漫质感，但人物造型、服装轮廓和色彩识别点必须稳定，不要低幼卡通。";
        }
        return "画面必须尽量接近真人演员实拍短剧或影视剧照，人物五官、皮肤、服装材质、空间透视与光影都要真实可信，严禁动漫插画感和3D塑料感。";
    }

    private static String buildAudioTypeRule(String resolvedType) {
        if (COMIC.equals(resolvedType)) {
            return "配音可更有节奏感和情绪起伏，角色区分度可以更鲜明，但仍要保证中文短剧收听自然，不能尖锐聒噪。";
        }
        return "配音应贴近真人演员对白，语气自然、口语化、少播音腔，情绪真实但不过火，听感像短剧现场对白而不是广告配音。";
    }

    private static String buildGenreStoryRule(String resolvedGenre) {
        if (containsAny(resolvedGenre, "民国")) {
            return "强化民国时代关系秩序、家族门第、旧上海/旧城空间、服化礼仪和时代禁忌，矛盾与行为要有民国语境。";
        }
        if (containsAny(resolvedGenre, "古装", "历史")) {
            return "遵守古装历史语境，关系称谓、礼法、出行方式和冲突表达要贴合古代背景，避免现代口吻和现代价值表达生硬乱入。";
        }
        if (containsAny(resolvedGenre, "仙侠", "玄幻", "奇幻")) {
            return "突出世界规则、身份等级、法器/异能/宗门等设定，奇观与情绪要统一，不要在奇幻设定中突然转成现代生活流。";
        }
        if (containsAny(resolvedGenre, "悬疑", "惊悚", "刑侦", "犯罪")) {
            return "剧情推进要围绕线索、疑点、误导和压迫感展开，信息释放要克制，场景与角色行为要服务悬念。";
        }
        if (containsAny(resolvedGenre, "校园", "青春")) {
            return "人物状态、口吻和冲突要有青春感，空间以教室、操场、宿舍、社团等为主，避免成人化过度。";
        }
        if (containsAny(resolvedGenre, "家庭", "伦理")) {
            return "冲突要贴近日常家庭结构与代际关系，生活细节、空间动线和人物情绪都要真实接地气。";
        }
        if (containsAny(resolvedGenre, "职场")) {
            return "重点体现职场权力关系、公司环境和职业身份，人物对白与目标要更专业、现实。";
        }
        if (containsAny(resolvedGenre, "喜剧")) {
            return "节奏轻快、包袱密集，但人物逻辑和场景动机仍要成立，不能为了搞笑直接出戏。";
        }
        if (containsAny(resolvedGenre, "逆袭", "复仇", "豪门")) {
            return "强化身份落差、权力翻盘和情绪报偿，场景、服装、道具都要服务阶层反差和高压冲突。";
        }
        return "围绕所选题材稳定输出世界观、场景、人物关系、服化道和冲突逻辑，避免混入不属于该题材的时代背景和行为方式。";
    }

    private static String buildGenreVisualRule(String resolvedGenre) {
        if (containsAny(resolvedGenre, "民国")) {
            return "服装优先旗袍、长衫、军装、礼帽、民国学生装等，场景优先老洋房、旧街巷、商会、舞厅、报馆、黄包车、老上海灯光质感。";
        }
        if (containsAny(resolvedGenre, "古装", "历史")) {
            return "服装、发饰、建筑、家具和道具都要以古风/古代体系为主，突出庭院、府邸、宫墙、廊桥、古灯和传统器物。";
        }
        if (containsAny(resolvedGenre, "仙侠", "玄幻", "奇幻")) {
            return "场景和服化道要有仙侠/玄幻奇观感，可加入宗门、云海、阵法、法器、异象光效，但构图仍要服务人物和叙事。";
        }
        if (containsAny(resolvedGenre, "悬疑", "惊悚", "刑侦", "犯罪")) {
            return "以低饱和、冷色、局部强光、封闭空间、线索道具和压迫式构图为主，避免甜宠或喜剧式明快视觉。";
        }
        if (containsAny(resolvedGenre, "校园", "青春")) {
            return "优先校服、运动服、休闲穿搭以及教室、操场、走廊、宿舍等空间，整体清爽年轻，不要过度成熟奢华。";
        }
        if (containsAny(resolvedGenre, "职场")) {
            return "优先现代办公室、会议室、CBD、商务餐厅等场景，服装以职业装、西装、都市精英穿搭为主。";
        }
        if (containsAny(resolvedGenre, "家庭", "伦理")) {
            return "以住宅、厨房、客厅、医院、社区等生活化空间为主，服装朴素真实，强调家居细节。";
        }
        if (containsAny(resolvedGenre, "喜剧")) {
            return "允许更明快饱和的色彩和更清晰的表演性动作，但场景道具仍应服务真实剧情语境。";
        }
        return "服装、背景、道具和光影必须贴紧所选题材，优先呈现观众一眼能识别的题材标志性视觉元素。";
    }

    private static String buildGenreAudioRule(String resolvedGenre) {
        if (containsAny(resolvedGenre, "民国")) {
            return "对白和旁白要带一点民国语境中的克制、礼貌和时代感称谓，但不能故作文绉绉。";
        }
        if (containsAny(resolvedGenre, "古装", "历史", "仙侠")) {
            return "避免现代网络口头禅，语气要更含蓄、稳，必要时保留古风称谓和时代气口。";
        }
        if (containsAny(resolvedGenre, "悬疑", "惊悚", "刑侦", "犯罪")) {
            return "整体听感要更克制、紧绷，停顿和重音服务悬念，不要过分热闹。";
        }
        if (containsAny(resolvedGenre, "校园", "青春")) {
            return "语言节奏要年轻、轻快、自然，减少过度成熟和说教式表达。";
        }
        if (containsAny(resolvedGenre, "喜剧")) {
            return "节奏可以更脆、更灵动，但笑点表达仍要清晰，不要喊麦式夸张。";
        }
        return "对白、旁白和语气要贴合题材气质，让观众只听声音也能感到题材一致。";
    }

    private static boolean containsAny(String value, String... keywords) {
        if (StringUtils.isBlank(value) || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(Set<String> target, String... values) {
        if (target == null || values == null) {
            return;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                target.add(value);
            }
        }
    }
}