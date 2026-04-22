import codecs
import re

fpath = 'src/views/synthesis/SynthesisView.vue'
with codecs.open(fpath, 'r', 'utf-8') as f:
    text = f.read()

# Add standard Vue imports
text = re.sub(r"import \{ ref, onMounted, onUnmounted \} from 'vue'", "import { ref, computed, onMounted, onUnmounted } from 'vue'", text)

state_vars = """
const showSelectDialog = ref(false)
const dialogType = ref<'images'|'dynamic'|'audio'|'compose'>('images')
const selectedShots = ref<any[]>([])
const submitLoading = ref(false)

const dialogTitle = computed(() => {
  switch(dialogType.value) {
    case 'images': return '选择需要生成图片的分镜'
    case 'dynamic': return '选择需要生成动态的分镜'
    case 'audio': return '选择需要生成配音的分镜'
    case 'compose': return '选择参与合成的分镜'
    default: return '选择分镜'
  }
})

const selectableMethod = (row: any) => {
  if (dialogType.value === 'dynamic') return row.imageUrl != null;
  if (dialogType.value === 'compose') return row.imageUrl != null;
  return true;
}

const handleSelectionChange = (val: any[]) => {
  selectedShots.value = val
}

const openDialog = (type: 'images'|'dynamic'|'audio'|'compose') => {
  dialogType.value = type;
  showSelectDialog.value = true;
  selectedShots.value = [];
}

const confirmGenerate = async () => {
  const shotIds = selectedShots.value.map(s => s.id);
  showSelectDialog.value = false;
  submitLoading.value = true;
  
  try {
    let res;
    if (dialogType.value === 'images') {
      imageLoading.value = true;
      res = await videoApi.generateImages(projectId, shotIds);
      imageLoading.value = false;
      ElMessage.success('分镜图片生成任务已提交');
    } else if (dialogType.value === 'dynamic') {
      dynamicLoading.value = true;
      res = await videoApi.generateDynamic(projectId, shotIds);
      dynamicLoading.value = false;
      ElMessage.success('分镜视频生成任务已提交');
    } else if (dialogType.value === 'audio') {
      audioLoading.value = true;
      res = await videoApi.generateAudio(projectId, shotIds);
      audioLoading.value = false;
      ElMessage.success('分镜配音生成任务已提交');
    } else if (dialogType.value === 'compose') {
      composeLoading.value = true;
      res = await videoApi.compose(projectId, shotIds);
      composeLoading.value = false;
      ElMessage.success('视频合成任务已提交');
    }
    if (res && res.data && res.data.data) {
      currentTask.value = res.data.data;
      startPolling(currentTask.value.id);
      setTimeout(loadOverview, 2000);
    }
  } catch(e: any) {
     ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
     submitLoading.value = false;
     imageLoading.value = false;
     dynamicLoading.value = false;
     audioLoading.value = false;
     composeLoading.value = false;
  }
}
"""

text = re.sub(r'const downloadLoading = ref\(false\)\r?\n', 'const downloadLoading = ref(false)\n' + state_vars, text)

# Clean up unused old methods
text = re.sub(r'async function handleGenerateImages\(\) \{.*?(?=\nasync function|\nfunction|\nlet)', '', text, flags=re.DOTALL)
text = re.sub(r'async function handleGenerateAudio\(\) \{.*?(?=\nasync function|\nfunction|\nlet)', '', text, flags=re.DOTALL)
text = re.sub(r'async function handleGenerateDynamic\(\) \{.*?(?=\nasync function|\nfunction|\nlet)', '', text, flags=re.DOTALL)
text = re.sub(r'async function handleCompose\(\) \{.*?(?=\nasync function|\nfunction|\nlet)', '', text, flags=re.DOTALL)

with codecs.open(fpath, 'w', 'utf-8') as f:
    f.write(text)

print("Vars injected!")
