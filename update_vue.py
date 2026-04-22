import codecs
import re

fpath = 'frontend/src/views/synthesis/SynthesisView.vue'
with codecs.open(fpath, 'r', 'utf-8') as f:
    text = f.read()

end_div_idx = text.rfind('  </div>\n</template>')

dialog_html = """
    <!-- Shot Selection Dialog -->
    <el-dialog v-model="showSelectDialog" :title="dialogTitle" width="800px">
      <el-table 
        :data="shots" 
        @selection-change="handleSelectionChange" 
        ref="shotTableRef"
        height="500px"
      >
        <el-table-column type="selection" width="55" :selectable="selectableMethod" />
        <el-table-column prop="shotNo" label="镜头号" width="80" />
        <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态 (图/音/动)" width="150" align="center">
          <template #default="{ row }">
            <span v-if="row.imageUrl" title="图片就绪">🖼️ </span>
            <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
            <span v-if="row.videoUrl" title="动态就绪">🎬 </span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="showSelectDialog = false">取消</el-button>
          <el-button type="primary" @click="confirmGenerate" :loading="submitLoading" :disabled="selectedShots.length === 0">
            确定 (已选 {{ selectedShots.length }})
          </el-button>
        </div>
      </template>
    </el-dialog>
"""

text = text[:end_div_idx] + dialog_html + text[end_div_idx:]

text = text.replace('@click="handleGenerateImages"', '@click="openDialog(\'images\')"')
text = text.replace('@click="handleGenerateDynamic"', '@click="openDialog(\'dynamic\')"')
text = text.replace('@click="handleGenerateAudio"', '@click="openDialog(\'audio\')"')
text = text.replace('@click="handleCompose"', '@click="openDialog(\'compose\')"')

state_vars = """const composeLoading = ref(false)
const downloadLoading = ref(false)

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

text = text.replace("import { ref, onMounted, onUnmounted } from 'vue'", "import { ref, computed, onMounted, onUnmounted } from 'vue'")
text = text.replace('const composeLoading = ref(false)\nconst downloadLoading = ref(false)', state_vars)

# also replace existing handlers so they don't break linter or if they are unused, just let it be (they are overwritten)

with codecs.open(fpath, 'w', 'utf-8') as f:
    f.write(text)

print("Vue file updated")
