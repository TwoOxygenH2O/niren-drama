import codecs
import re

fpath = 'src/views/synthesis/SynthesisView.vue'
with codecs.open(fpath, 'r', 'utf-8') as f:
    text = f.read()

# Add dialog HTML
end_div_idx = text.rfind('  </div>\n</template>')
if "<!-- Shot Selection Dialog -->" not in text:
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

with codecs.open(fpath, 'w', 'utf-8') as f:
    f.write(text)
print("Added HTML!")
