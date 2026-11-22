<template>
  <div id="tags-view-container" class="tags-view-container">
    <scroll-pane ref="scrollPaneRef" class="tags-view-wrapper" @scroll="handleScroll">
      <router-link
        v-for="tag in visitedViews"
        :key="tag.path"
        :data-path="tag.path"
        :class="{ 'active': isActive(tag), 'has-icon': tagsIcon }"
        :to="tag.fullPath || { path: tag.path || '/', query: tag.query, hash: tag.hash }"
        class="tags-view-item"
        :style="activeStyle(tag)"
        @click.middle="!isAffix(tag) ? closeSelectedTag(tag) : ''"
        @contextmenu.prevent="openMenu(tag, $event)"
      >
        <svg-icon v-if="tagsIcon && tag.meta && tag.meta.icon && tag.meta.icon !== '#'" :icon-class="tag.meta.icon" />
        <span class="tags-view-item-title">{{ tag.title }}</span>
        <span v-if="!isAffix(tag)" @click.prevent.stop="closeSelectedTag(tag)">
          <close style="width: 1em; height: 1em; vertical-align: middle" />
        </span>
      </router-link>
    </scroll-pane>
    <ul v-show="visible" :style="{ left: left + 'px', top: top + 'px' }" class="contextmenu">
      <li @click="refreshSelectedTag(selectedTag)"><refresh-right style="width: 1em; height: 1em" /> 刷新页面</li>
      <li v-if="!isAffix(selectedTag)" @click="closeSelectedTag(selectedTag)"><close style="width: 1em; height: 1em" /> 关闭当前</li>
      <li @click="closeOthersTags"><circle-close style="width: 1em; height: 1em" /> 关闭其他</li>
      <li v-if="!isFirstView()" @click="closeLeftTags"><back style="width: 1em; height: 1em" /> 关闭左侧</li>
      <li v-if="!isLastView()" @click="closeRightTags"><right style="width: 1em; height: 1em" /> 关闭右侧</li>
      <li @click="closeAllTags(selectedTag)"><circle-close style="width: 1em; height: 1em" /> 全部关闭</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import ScrollPane from './ScrollPane.vue';
import { getNormalPath } from '@/utils/ruoyi';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { useTagsViewStore } from '@/store/modules/tagsView';
import { RouteRecordRaw, RouteLocationNormalized } from 'vue-router';

const visible = ref(false);
const top = ref(0);
const left = ref(0);
const selectedTag = ref<RouteLocationNormalized>();
const affixTags = ref<RouteLocationNormalized[]>([]);
const scrollPaneRef = ref<InstanceType<typeof ScrollPane>>();

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const route = useRoute();
const router = useRouter();

const visitedViews = computed(() => useTagsViewStore().getVisitedViews());
const routes = computed(() => usePermissionStore().getRoutes());
const theme = computed(() => useSettingsStore().theme);
const tagsIcon = computed(() => useSettingsStore().tagsIcon);

watch(route, () => {
  addTags();
  moveToCurrentTag();
});
watch(visible, (value) => {
  if (value) {
    document.body.addEventListener('click', closeMenu);
  } else {
    document.body.removeEventListener('click', closeMenu);
  }
});

const isActive = (r: RouteLocationNormalized): boolean => {
  return r.path === route.path;
};
const activeStyle = (tag: RouteLocationNormalized) => {
  if (!isActive(tag)) return {};
  return {
    'background-color': 'var(--tags-view-active-bg)',
    'border-color': 'var(--tags-view-active-border-color)'
  };
};
const isAffix = (tag: RouteLocationNormalized) => {
  return tag?.meta && tag?.meta?.affix;
};
const isFirstView = () => {
  try {
    return selectedTag.value.fullPath === '/index' || selectedTag.value.fullPath === visitedViews.value[1].fullPath;
  } catch (err) {
    return false;
  }
};
const isLastView = () => {
  try {
    return selectedTag.value.fullPath === visitedViews.value[visitedViews.value.length - 1].fullPath;
  } catch (err) {
    return false;
  }
};
const filterAffixTags = (routes: RouteRecordRaw[], basePath = '') => {
  let tags: RouteLocationNormalized[] = [];

  routes.forEach((route) => {
    if (route.meta && route.meta.affix) {
      const tagPath = getNormalPath(basePath + '/' + route.path);
      tags.push({
        hash: '',
        matched: [],
        params: undefined,
        query: undefined,
        redirectedFrom: undefined,
        fullPath: tagPath,
        path: tagPath,
        name: route.name as string,
        meta: { ...route.meta }
      });
    }
    if (route.children) {
      const tempTags = filterAffixTags(route.children, route.path);
      if (tempTags.length >= 1) {
        tags = [...tags, ...tempTags];
      }
    }
  });
  return tags;
};
const initTags = () => {
  const res = filterAffixTags(routes.value);
  affixTags.value = res;
  for (const tag of res) {
    // Must have tag name
    if (tag.name) {
      useTagsViewStore().addVisitedView(tag);
    }
  }
};
const addTags = () => {
  const { name } = route;
  if (route.query.title) {
    route.meta.title = route.query.title as string;
  }
  if (name) {
    useTagsViewStore().addView(route as any);
  }
};
const moveToCurrentTag = () => {
  nextTick(() => {
    for (const r of visitedViews.value) {
      if (r.path === route.path) {
        scrollPaneRef.value?.moveToTarget(r);
        // when query is different then update
        if (r.fullPath !== route.fullPath) {
          useTagsViewStore().updateVisitedView(route);
        }
      }
    }
  });
};
const refreshSelectedTag = (view: RouteLocationNormalized) => {
  proxy?.$tab.refreshPage(view);
  if (route.meta.link) {
    useTagsViewStore().delIframeView(route);
  }
};
const closeSelectedTag = (view: RouteLocationNormalized) => {
  proxy?.$tab.closePage(view).then(({ visitedViews }: any) => {
    if (isActive(view)) {
      toLastView(visitedViews, view);
    }
  });
};
const closeRightTags = () => {
  proxy?.$tab.closeRightPage(selectedTag.value).then((visitedViews: RouteLocationNormalized[]) => {
    if (!visitedViews.find((i: RouteLocationNormalized) => i.fullPath === route.fullPath)) {
      toLastView(visitedViews);
    }
  });
};
const closeLeftTags = () => {
  proxy?.$tab.closeLeftPage(selectedTag.value).then((visitedViews: RouteLocationNormalized[]) => {
    if (!visitedViews.find((i: RouteLocationNormalized) => i.fullPath === route.fullPath)) {
      toLastView(visitedViews);
    }
  });
};
const closeOthersTags = () => {
  if (selectedTag.value) router.push(selectedTag.value.fullPath || selectedTag.value.path || '/').catch(() => {});
  proxy?.$tab.closeOtherPage(selectedTag.value).then(() => {
    moveToCurrentTag();
  });
};
const closeAllTags = (view: RouteLocationNormalized) => {
  proxy?.$tab.closeAllPage().then(({ visitedViews }) => {
    if (affixTags.value.some((tag) => tag.path === route.path)) {
      return;
    }
    toLastView(visitedViews, view);
  });
};
const toLastView = (visitedViews: RouteLocationNormalized[], view?: RouteLocationNormalized) => {
  const latestView = visitedViews.slice(-1)[0];
  if (latestView) {
    router.push(latestView.fullPath as string);
  } else {
    // now the default is to redirect to the home page if there is no tags-view,
    // you can adjust it according to your needs.
    if (view?.name === 'Dashboard') {
      // to reload home page
      router.replace({ path: '/redirect' + view?.fullPath });
    } else {
      router.push('/');
    }
  }
};
const openMenu = (tag: RouteLocationNormalized, e: MouseEvent) => {
  const menuMinWidth = 105;
  const offsetLeft = proxy?.$el.getBoundingClientRect().left; // container margin left
  const offsetWidth = proxy?.$el.offsetWidth; // container width
  const maxLeft = offsetWidth - menuMinWidth; // left boundary
  const l = e.clientX - offsetLeft + 15; // 15: margin right

  if (l > maxLeft) {
    left.value = maxLeft;
  } else {
    left.value = l;
  }

  top.value = e.clientY;
  visible.value = true;
  selectedTag.value = tag;
};
const closeMenu = () => {
  visible.value = false;
};
const handleScroll = () => {
  closeMenu();
};

onMounted(() => {
  initTags();
  addTags();
});
</script>

<style lang="scss" scoped>
.tags-view-container {
  height: 34px;
  width: 100%;
  background-color: var(--td-bg-color-container);
  border-top: none;
  border-bottom: 1px solid var(--td-border-level-1-color);
  box-shadow: none;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;

  .tags-view-wrapper {
    .tags-view-item {
      display: inline-block;
      position: relative;
      cursor: pointer;
      height: 26px;
      line-height: 24px;
      background-color: var(--td-bg-color-secondarycontainer);
      border: 1px solid var(--td-border-level-1-color);
      color: var(--td-text-color-primary);
      padding: 0 10px;
      font-size: 12px;
      margin-left: 6px;
      margin-top: 4px;
      border-radius: var(--app-radius-md);
      transition: all 0.2s ease;

      &:hover {
        color: var(--td-brand-color);
        border-color: var(--td-brand-color);
        background-color: var(--td-bg-color-container-hover);
        box-shadow: var(--app-shadow-sm);
        transform: translateY(-1px);
      }

      &:first-of-type {
        margin-left: 12px;
      }

      &:last-of-type {
        margin-right: 12px;
      }

      &.active {
        background-color: var(--tags-view-active-bg, var(--td-brand-color)) !important;
        color: #fff !important;
        border-color: var(--tags-view-active-border-color, var(--td-brand-color)) !important;

        &::before {
          content: '';
          background: #fff;
          display: inline-block;
          width: 6px;
          height: 6px;
          border-radius: 50%;
          position: relative;
          margin-right: 6px;
          vertical-align: middle;
        }
      }
    }
  }

  .tags-view-item.active.has-icon::before {
    content: none !important;
  }

  .tags-view-item-title {
    margin-left: 4px;
    margin-right: 3px;
  }

  .contextmenu {
    margin: 0;
    background: var(--td-bg-color-container);
    border: 1px solid var(--td-border-level-2-color);
    color: var(--td-text-color-primary);
    z-index: 3000;
    position: absolute;
    list-style-type: none;
    padding: 6px 0;
    border-radius: var(--app-radius-md);
    font-size: 13px;
    font-weight: 400;
    box-shadow: var(--app-shadow-md);

    li {
      margin: 0;
      padding: 8px 16px;
      cursor: pointer;

      &:hover {
        background: var(--td-bg-color-container-hover);
        color: var(--td-brand-color);
      }
    }
  }
}

// 暗色主题样式强化承接（兼容 html.dark 与 theme-mode='dark'）
:global(html.dark) .tags-view-container,
:global(html[theme-mode='dark']) .tags-view-container,
:global(:root[theme-mode='dark']) .tags-view-container {
  background-color: var(--td-bg-color-container, #181818) !important;
  border-bottom: 1px solid var(--td-border-level-1-color, #2d3036) !important;

  .tags-view-wrapper .tags-view-item {
    background-color: var(--td-bg-color-secondarycontainer, #242424) !important;
    border-color: var(--td-border-level-1-color, #383838) !important;
    color: var(--td-text-color-primary, #e6e6e6) !important;

    &:hover {
      color: var(--td-brand-color, #0052d9) !important;
      border-color: var(--td-brand-color, #0052d9) !important;
    }

    &.active {
      background-color: var(--tags-view-active-bg, var(--td-brand-color)) !important;
      border-color: var(--tags-view-active-border-color, var(--td-brand-color)) !important;
      color: #fff !important;
    }
  }

  .contextmenu {
    background: var(--td-bg-color-container, #1f1f1f) !important;
    border-color: var(--td-border-level-2-color, #37373a) !important;
    color: var(--td-text-color-primary, #e5eaf3) !important;

    li:hover {
      background: var(--td-bg-color-container-hover, #2c2c2c) !important;
    }
  }
}
</style>
