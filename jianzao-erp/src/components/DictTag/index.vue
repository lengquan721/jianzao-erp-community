<template>
  <div>
    <template v-for="(item, index) in options">
      <template v-if="isValueMatch(item.value)">
        <span
          v-if="(item.elTagType === 'default' || item.elTagType === '') && (item.elTagClass === '' || item.elTagClass == null)"
          :key="item.value"
          :index="index"
          :class="item.elTagClass"
        >
          {{ item.label + ' ' }}
        </span>
        <t-tag v-else :key="item.value + ''" :index="index" :theme="tagTheme(item.elTagType)" :class="item.elTagClass">
          {{ item.label + ' ' }}
        </t-tag>
      </template>
    </template>
    <template v-if="unmatch && showValue">
      {{ unmatchArray }}
    </template>
  </div>
</template>

<script setup lang="ts">
interface Props {
  options: Array<DictDataOption>;
  value: number | string | Array<number | string>;
  showValue?: boolean;
  separator?: string;
}
const props = withDefaults(defineProps<Props>(), {
  showValue: true,
  separator: ','
});

// 字典 elTagType 沿用 EP 五值命名；TDesign 无 info 主题，统一落到 default（灰），其余原样，未登记兜底 primary
const TAG_THEME: Record<string, 'default' | 'primary' | 'success' | 'warning' | 'danger'> = {
  primary: 'primary',
  success: 'success',
  info: 'default',
  warning: 'warning',
  danger: 'danger'
};
const tagTheme = (type?: string) => TAG_THEME[type] ?? 'primary';

const values = computed(() => {
  if (props.value === '' || props.value === null || typeof props.value === 'undefined') return [];
  if (typeof props.value === 'number' || typeof props.value === 'boolean') return [props.value];
  return Array.isArray(props.value) ? props.value.map((item) => '' + item) : String(props.value).split(props.separator);
});

const unmatch = computed(() => {
  if (props.options?.length == 0 || props.value === '' || props.value === null || typeof props.value === 'undefined') return false;
  // 传入值为非数组
  let unmatch = false; // 添加一个标志来判断是否有未匹配项
  values.value.forEach((item) => {
    if (!props.options.some((v) => v.value == item)) {
      unmatch = true; // 如果有未匹配项，将标志设置为true
    }
  });
  return unmatch; // 返回标志的值
});

const unmatchArray = computed(() => {
  // 记录未匹配的项
  const itemUnmatchArray: Array<string | number> = [];
  if (props.value !== '' && props.value !== null && typeof props.value !== 'undefined') {
    values.value.forEach((item) => {
      if (!props.options.some((v) => v.value === item)) {
        itemUnmatchArray.push(item);
      }
    });
  }
  // 没有value不显示
  return handleArray(itemUnmatchArray);
});

const handleArray = (array: Array<string | number>) => {
  if (array.length === 0) return '';
  return array.reduce((pre, cur) => {
    return pre + ' ' + cur;
  });
};

const isValueMatch = (itemValue: any) => {
  return values.value.some((val) => val == itemValue);
};
</script>

<style lang="scss" scoped>
:deep(.t-tag + .t-tag) {
  margin-left: 10px;
}
</style>
