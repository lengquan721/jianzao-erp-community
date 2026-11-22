<template>
  <div>
    <t-upload
      v-if="type === 'url'"
      ref="uploadRef"
      theme="custom"
      :action="upload.url"
      :before-upload="handleBeforeUpload"
      :headers="upload.headers"
      class="editor-img-uploader"
      name="file"
      @success="handleUploadSuccess"
      @fail="handleUploadError"
    />
  </div>
  <div class="editor">
    <quill-editor
      ref="quillEditorRef"
      v-model:content="content"
      content-type="html"
      :options="options"
      :style="styles"
      @ready="handleEditorReady"
      @selection-change="handleSelectionChange"
      @text-change="handleTextChange"
    />
  </div>
</template>

<script setup lang="ts">
import '@vueup/vue-quill/dist/vue-quill.snow.css';

import { QuillEditor, Quill } from '@vueup/vue-quill';
import { propTypes } from '@/utils/propTypes';
import { globalHeaders } from '@/utils/request';
import { compressImage, syncUploadFile } from '@/utils/image';
import type { UploadInstanceFunctions } from 'tdesign-vue-next';
import { isSafeNoticeUrl, sanitizeNoticeHtml } from './sanitizeHtml';

const emit = defineEmits(['update:modelValue']);

const props = defineProps({
  /* 编辑器的内容 */
  modelValue: propTypes.string,
  /* 高度 */
  height: propTypes.number.def(400),
  /* 最小高度 */
  minHeight: propTypes.number.def(400),
  /* 只读 */
  readOnly: propTypes.bool.def(false),
  /* 上传文件大小限制(MB) */
  fileSize: propTypes.number.def(5),
  /* 类型（base64格式、url格式） */
  type: propTypes.string.def('url')
});

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const upload = reactive<UploadOption>({
  headers: globalHeaders(),
  url: import.meta.env.VITE_APP_BASE_API + '/resource/oss/upload'
});
const quillEditorRef = ref();
const uploadRef = ref<UploadInstanceFunctions>();

const options = ref<any>({
  theme: 'snow',
  bounds: document.body,
  debug: 'warn',
  modules: {
    // 工具栏配置
    toolbar: {
      container: [
        ['bold', 'italic', 'underline', 'strike'], // 加粗 斜体 下划线 删除线
        ['blockquote', 'code-block'], // 引用  代码块
        [{ list: 'ordered' }, { list: 'bullet' }], // 有序、无序列表
        [{ indent: '-1' }, { indent: '+1' }], // 缩进
        [{ size: ['small', false, 'large', 'huge'] }], // 字体大小
        [{ header: [1, 2, 3, 4, 5, 6, false] }], // 标题
        [{ color: [] }, { background: [] }], // 字体颜色、字体背景颜色
        [{ align: [] }], // 对齐方式
        ['clean'], // 清除文本格式
        ['link', 'image'] // 链接、图片；视频/iframe 不能进入公告白名单
      ],
      handlers: {
        image: (value: boolean) => {
          if (value) {
            // 触发 t-upload 的文件选择（theme=custom 下由实例 triggerUpload 打开）
            uploadRef.value?.triggerUpload();
          } else {
            activeQuill?.format('image', true);
          }
        }
      }
    }
  },
  placeholder: '请输入内容',
  readOnly: props.readOnly
});

const styles = computed(() => {
  const style: any = {};
  if (props.minHeight) {
    style.minHeight = `${props.minHeight}px`;
  }
  if (props.height) {
    style.height = `${props.height}px`;
  }
  return style;
});

const content = ref('');
let activeQuill: any;
let lastSelection: { index: number; length: number } | null = null;
const handlePaste = (event: ClipboardEvent) => {
  const dirtyHtml = event.clipboardData?.getData('text/html');
  if (!dirtyHtml || !activeQuill) return;
  event.preventDefault();
  const cleanHtml = sanitizeNoticeHtml(dirtyHtml);
  const range = activeQuill.getSelection(true) || lastSelection || { index: Math.max(0, activeQuill.getLength() - 1), length: 0 };
  if (range.length) activeQuill.deleteText(range.index, range.length, 'user');
  if (cleanHtml) activeQuill.clipboard.dangerouslyPasteHTML(range.index, cleanHtml, 'user');
};

const handleEditorReady = (quill: any) => {
  activeQuill = quill;
  activeQuill.root.addEventListener('paste', handlePaste);
  const selection = activeQuill.getSelection();
  if (selection) lastSelection = selection;
};

const handleSelectionChange = (range: { index: number; length: number } | null) => {
  if (range) lastSelection = range;
};

const handleTextChange = () => {
  const dirtyHtml = activeQuill?.root?.innerHTML ?? content.value;
  const cleanHtml = sanitizeNoticeHtml(dirtyHtml);
  if (cleanHtml !== dirtyHtml) {
    const selection = activeQuill?.getSelection();
    content.value = cleanHtml;
    if (activeQuill) {
      activeQuill.clipboard.dangerouslyPasteHTML(cleanHtml, 'silent');
      if (selection) activeQuill.setSelection(selection.index, selection.length, 'silent');
    }
  }
  emit('update:modelValue', cleanHtml);
};

watch(
  () => props.modelValue,
  (v: string) => {
    const cleanHtml = sanitizeNoticeHtml(v || '');
    if (cleanHtml !== content.value) content.value = cleanHtml || '<p></p>';
  },
  { immediate: true }
);

watch(
  () => props.readOnly,
  (readOnly) => activeQuill?.enable(!readOnly)
);

onBeforeUnmount(() => activeQuill?.root?.removeEventListener('paste', handlePaste));

// 图片上传成功返回图片地址（t-upload 的 success 回传上下文对象，后端响应体在 response 上）
const handleUploadSuccess = (ctx: any) => {
  const res = ctx?.response ?? {};
  // 如果上传成功
  if (res.code === 200) {
    const imageUrl = res.data?.url;
    if (typeof imageUrl !== 'string' || !isSafeNoticeUrl(imageUrl, true)) {
      proxy?.$modal.msgError('图片地址不安全，无法插入');
      proxy?.$modal.closeLoading();
      return;
    }
    const quill = toRaw(quillEditorRef.value).getQuill();
    const range = quill.getSelection() || lastSelection || { index: Math.max(0, quill.getLength() - 1), length: 0 };
    const index = Math.max(0, Math.min(range.index, quill.getLength() - 1));
    quill.insertEmbed(index, 'image', imageUrl, 'user');
    quill.setSelection(index + 1, 0, 'user');
    proxy?.$modal.closeLoading();
  } else {
    proxy?.$modal.msgError('图片插入失败');
    proxy?.$modal.closeLoading();
  }
};

// 图片上传前拦截：格式/大小校验通过后先压缩，压缩结果原地替换给 t-upload 的 XHR
const handleBeforeUpload = async (file: any) => {
  const allowedTypes = ['image/jpeg', 'image/jpg', 'image/png'];
  //检验文件格式
  if (!allowedTypes.includes(file.type)) {
    proxy?.$modal.msgError(`图片格式错误!`);
    return false;
  }
  // 校检文件大小
  if (props.fileSize) {
    const isLt = file.size / 1024 / 1024 < props.fileSize;
    if (!isLt) {
      proxy?.$modal.msgError(`上传文件大小不能超过 ${props.fileSize} MB!`);
      return false;
    }
  }
  proxy?.$modal.loading('正在上传图片，请稍候...');
  try {
    // 富文本图片展示在白色内容区，透明底直接补白，保证大图一定能压到目标体积
    const compressed = await compressImage(file.raw ?? file, { preserveAlpha: false });
    syncUploadFile(file, compressed);
  } catch (error) {
    // compressImage 内部已兜底，这里只为避免上传流程被意外异常打断
    console.warn('[editor] 图片压缩失败，使用原图上传', error);
  }
  return true;
};

// 图片失败拦截
const handleUploadError = (ctx: any) => {
  proxy?.$modal.msgError(ctx?.response?.msg || '上传文件失败');
  proxy?.$modal.closeLoading();
};
</script>

<style>
.editor-img-uploader {
  display: none;
}
.editor,
.ql-toolbar {
  white-space: pre-wrap !important;
  line-height: normal !important;
}
.quill-img {
  display: none;
}
.ql-snow .ql-tooltip[data-mode='link']::before {
  content: '请输入链接地址:';
}
.ql-snow .ql-tooltip.ql-editing a.ql-action::after {
  border-right: 0;
  content: '保存';
  padding-right: 0;
}
.ql-snow .ql-tooltip[data-mode='video']::before {
  content: '请输入视频地址:';
}
.ql-snow .ql-picker.ql-size .ql-picker-label::before,
.ql-snow .ql-picker.ql-size .ql-picker-item::before {
  content: '14px';
}
.ql-snow .ql-picker.ql-size .ql-picker-label[data-value='small']::before,
.ql-snow .ql-picker.ql-size .ql-picker-item[data-value='small']::before {
  content: '10px';
}
.ql-snow .ql-picker.ql-size .ql-picker-label[data-value='large']::before,
.ql-snow .ql-picker.ql-size .ql-picker-item[data-value='large']::before {
  content: '18px';
}
.ql-snow .ql-picker.ql-size .ql-picker-label[data-value='huge']::before,
.ql-snow .ql-picker.ql-size .ql-picker-item[data-value='huge']::before {
  content: '32px';
}
.ql-snow .ql-picker.ql-header .ql-picker-label::before,
.ql-snow .ql-picker.ql-header .ql-picker-item::before {
  content: '文本';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='1']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='1']::before {
  content: '标题1';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='2']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='2']::before {
  content: '标题2';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='3']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='3']::before {
  content: '标题3';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='4']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='4']::before {
  content: '标题4';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='5']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='5']::before {
  content: '标题5';
}
.ql-snow .ql-picker.ql-header .ql-picker-label[data-value='6']::before,
.ql-snow .ql-picker.ql-header .ql-picker-item[data-value='6']::before {
  content: '标题6';
}
.ql-snow .ql-picker.ql-font .ql-picker-label::before,
.ql-snow .ql-picker.ql-font .ql-picker-item::before {
  content: '标准字体';
}
.ql-snow .ql-picker.ql-font .ql-picker-label[data-value='serif']::before,
.ql-snow .ql-picker.ql-font .ql-picker-item[data-value='serif']::before {
  content: '衬线字体';
}
.ql-snow .ql-picker.ql-font .ql-picker-label[data-value='monospace']::before,
.ql-snow .ql-picker.ql-font .ql-picker-item[data-value='monospace']::before {
  content: '等宽字体';
}
</style>
