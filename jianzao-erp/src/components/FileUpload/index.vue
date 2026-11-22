<template>
  <div class="upload-file">
    <t-upload
      v-if="!disabled"
      theme="custom"
      multiple
      :action="uploadFileUrl"
      :before-upload="handleBeforeUpload"
      :max="limit"
      :accept="fileAccept"
      :auto-upload="true"
      :headers="headers"
      :show-upload-progress="false"
      class="upload-file-uploader"
      @validate="handleValidate"
      @one-file-success="handleUploadSuccess"
      @one-file-fail="handleUploadError"
    >
      <!-- 上传按钮 -->
      <t-button theme="primary">选取文件</t-button>
    </t-upload>
    <!-- 上传提示 -->
    <div v-if="showTip && !disabled" class="upload-file-tip">
      请上传
      <template v-if="fileSize">
        大小不超过 <b style="color: #f56c6c">{{ fileSize }}MB</b>
      </template>
      <template v-if="fileType">
        格式为 <b style="color: #f56c6c">{{ fileType.join('/') }}</b>
      </template>
      的文件
    </div>
    <!-- 文件列表 -->
    <transition-group class="upload-file-list" name="upload-fade" tag="ul">
      <li v-for="(file, index) in fileList" :key="file.uid" class="upload-file-list__item ele-upload-list__item-content">
        <t-link :href="`${file.url}`" :underline="false" target="_blank">
          <template #prefix-icon><FileIcon /></template>
          {{ getFileName(file.name) }}
        </t-link>
        <div class="ele-upload-list__item-content-action">
          <t-button v-if="!disabled" theme="danger" variant="text" size="small" @click="handleDelete(index)">删除</t-button>
        </div>
      </li>
    </transition-group>
  </div>
</template>

<script setup lang="ts">
import { FileIcon } from 'tdesign-icons-vue-next';
import { propTypes } from '@/utils/propTypes';
import { delOss, listByIds } from '@/api/system/oss';
import { globalHeaders } from '@/utils/request';

const props = defineProps({
  modelValue: {
    type: [String, Object, Array],
    default: () => []
  },
  // 数量限制
  limit: propTypes.number.def(5),
  // 大小限制(MB)
  fileSize: propTypes.number.def(5),
  // 文件类型, 例如['png', 'jpg', 'jpeg']
  fileType: propTypes.array.def(['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'pdf']),
  // 是否显示提示
  isShowTip: propTypes.bool.def(true),
  // 禁用组件（仅查看文件）
  disabled: propTypes.bool.def(false)
});

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const emit = defineEmits(['update:modelValue']);
const number = ref(0);
const uploadList = ref<any[]>([]);

const baseUrl = import.meta.env.VITE_APP_BASE_API;
const uploadFileUrl = ref(baseUrl + '/resource/oss/upload'); // 上传文件服务器地址
const headers = ref(globalHeaders());

const fileList = ref<any[]>([]);
const showTip = computed(() => props.isShowTip && (props.fileType || props.fileSize));

// 监听 fileType 变化，更新 fileAccept
const fileAccept = computed(() => props.fileType.map((type) => `.${type}`).join(','));

watch(
  () => props.modelValue,
  async (val) => {
    if (val) {
      let temp = 1;
      // 首先将值转为数组
      let list: any[] = [];
      if (Array.isArray(val)) {
        list = val;
      } else if (typeof val === 'object') {
        list = [val];
      } else {
        const res = await listByIds(val);
        list = res.data.map((oss) => {
          return {
            name: oss.originalName,
            url: oss.url,
            ossId: oss.ossId
          };
        });
      }
      // 然后将数组转为对象数组
      fileList.value = list.map((item) => {
        item = { name: item.name, url: item.url, ossId: item.ossId };
        item.uid = item.uid || new Date().getTime() + temp++;
        return item;
      });
    } else {
      fileList.value = [];
      return [];
    }
  },
  { deep: true, immediate: true }
);

// 上传前校检格式和大小
const handleBeforeUpload = (file: any) => {
  // 校检文件类型
  if (props.fileType.length) {
    const fileName = file.name.split('.');
    const fileExt = fileName[fileName.length - 1];
    const isTypeOk = props.fileType.indexOf(fileExt) >= 0;
    if (!isTypeOk) {
      proxy?.$modal.msgError(`文件格式不正确, 请上传${props.fileType.join('/')}格式文件!`);
      return false;
    }
  }
  // 校检文件名是否包含特殊字符
  if (file.name.includes(',')) {
    proxy?.$modal.msgError('文件名不正确，不能包含英文逗号!');
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
  proxy?.$modal.loading('正在上传文件，请稍候...');
  number.value++;
  return true;
};

// 文件个数超出：t-upload 的 :max 触发 FILES_OVER_LENGTH_LIMIT 校验
const handleValidate = (ctx: any) => {
  if (ctx.type === 'FILES_OVER_LENGTH_LIMIT') {
    proxy?.$modal.msgError(`上传文件数量不能超过 ${props.limit} 个!`);
  }
};

// 上传失败
const handleUploadError = () => {
  proxy?.$modal.msgError('上传文件失败');
};

// 上传成功回调（t-upload 每文件一次，后端响应体在 response 上）
const handleUploadSuccess = (ctx: any) => {
  const res = ctx?.response ?? {};
  if (res.code === 200) {
    uploadList.value.push({
      name: res.data.fileName,
      url: res.data.url,
      ossId: res.data.ossId
    });
    uploadedSuccessfully();
  } else {
    number.value--;
    proxy?.$modal.closeLoading();
    proxy?.$modal.msgError(res.msg);
    uploadedSuccessfully();
  }
};

// 删除文件
const handleDelete = (index: number) => {
  const ossId = fileList.value[index].ossId;
  delOss(ossId);
  fileList.value.splice(index, 1);
  emit('update:modelValue', listToString(fileList.value));
};

// 上传结束处理
const uploadedSuccessfully = () => {
  if (number.value > 0 && uploadList.value.length === number.value) {
    fileList.value = fileList.value.filter((f) => f.url !== undefined).concat(uploadList.value);
    uploadList.value = [];
    number.value = 0;
    emit('update:modelValue', listToString(fileList.value));
    proxy?.$modal.closeLoading();
  }
};

// 获取文件名称
const getFileName = (name: string) => {
  // 如果是url那么取最后的名字 如果不是直接返回
  if (name.lastIndexOf('/') > -1) {
    return name.slice(name.lastIndexOf('/') + 1);
  } else {
    return name;
  }
};

// 对象转成指定字符串分隔
const listToString = (list: any[], separator?: string) => {
  let strs = '';
  separator = separator || ',';
  list.forEach((item) => {
    if (item.ossId) {
      strs += item.ossId + separator;
    }
  });
  return strs != '' ? strs.substring(0, strs.length - 1) : '';
};
</script>

<style lang="scss" scoped>
.upload-file-uploader {
  margin-bottom: 5px;
}

.upload-fade-enter-active,
.upload-fade-leave-active {
  transition: opacity 0.2s;
}
.upload-fade-enter-from,
.upload-fade-leave-to {
  opacity: 0;
}

.upload-file-list .upload-file-list__item {
  border: 1px solid #e4e7ed;
  line-height: 2;
  margin-bottom: 10px;
  position: relative;
}

.upload-file-list .ele-upload-list__item-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: inherit;
}

.ele-upload-list__item-content-action .t-link {
  margin-right: 10px;
}
</style>
