<template>
  <t-head-menu class="topbar-menu" :style="{ '--theme': theme }" :value="activeMenu" expand-type="popup" :theme="isDark ? 'dark' : 'light'">
    <sidebar-item :key="route.path + index" v-for="(route, index) in topMenus" :item="route" :base-path="route.path" />

    <t-submenu v-if="moreRoutes.length > 0" value="more" class="topbar-menu__more">
      <template #title>更多菜单</template>
      <sidebar-item :key="route.path + index" v-for="(route, index) in moreRoutes" :item="route" :base-path="route.path" />
    </t-submenu>
  </t-head-menu>
</template>

<script setup lang="ts">
import SidebarItem from '../Sidebar/SidebarItem.vue';
import type { RouteRecordRaw } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';

const route = useRoute();
const appStore = useAppStore();
const settingsStore = useSettingsStore();
const permissionStore = usePermissionStore();

// 深色模式自动响应
const checkDark = () => document.documentElement.classList.contains('dark') || document.documentElement.getAttribute('theme-mode') === 'dark';
const isDark = ref(checkDark());
const darkObserver = new MutationObserver(() => {
  isDark.value = checkDark();
});

const sidebarRouters = computed<RouteRecordRaw[]>(() => permissionStore.getSidebarRoutes());
const theme = computed(() => settingsStore.theme);
const device = computed(() => appStore.device);
const activeMenu = computed(() => {
  const { meta, path } = route;
  if (meta.activeMenu) {
    return meta.activeMenu;
  }
  return path;
});

const visibleNumber = ref(5);
const topMenus = computed(() => {
  return sidebarRouters.value.filter((f) => !f.hidden).slice(0, visibleNumber.value);
});
const moreRoutes = computed(() => {
  return sidebarRouters.value.filter((f) => !f.hidden).slice(visibleNumber.value, sidebarRouters.value.length - visibleNumber.value);
});
function setVisibleNumber() {
  let width = document.body.getBoundingClientRect().width;
  if (width >= 1000) {
    width -= 500;
  }
  visibleNumber.value = Math.trunc(width / 3 / 85);
}

onMounted(() => {
  window.addEventListener('resize', setVisibleNumber);
  setVisibleNumber();
  darkObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'theme-mode'] });
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', setVisibleNumber);
  darkObserver.disconnect();
});
</script>

<style lang="scss" scoped>
/* 水平顶部菜单：TDesign t-head-menu 根默认白底 + 64px 行高，压回 Navbar 的 50px 顶栏 */
.topbar-menu.t-head-menu {
  width: auto;
  height: 50px;
  line-height: 50px;
  background-color: transparent;

  :deep(.t-head-menu__inner) {
    height: 50px;
  }

  :deep(.t-head-menu__inner > .t-menu) {
    margin-left: 0;
  }
}

/* 一级菜单项与「更多菜单」触发器沿用旧顶栏的 50px 行高、自适应字色与左右间距 */
.topbar-menu.t-head-menu :deep(.t-menu__item),
.topbar-menu.t-head-menu :deep(.t-submenu) {
  float: left;
  height: 50px !important;
  line-height: 50px !important;
  color: var(--td-text-color-primary) !important;
  padding: 0 5px !important;
  margin: 0 10px !important;
}

/* 激活项文字与图标用主题色（--theme 由根元素内联注入） */
.topbar-menu.t-head-menu :deep(.t-menu__item.t-is-active),
.topbar-menu.t-head-menu :deep(.t-menu__item.t-is-active .menu-title),
.topbar-menu.t-head-menu :deep(.t-menu__item.t-is-active .svg-icon) {
  color: var(--theme) !important;
}

/* 「更多菜单」下拉箭头静态定位，保持与顶栏同一行 */
.topbar-menu.t-head-menu :deep(.topbar-menu__more .t-fake-arrow) {
  position: static;
  margin-left: 8px;
  display: block;
}
</style>
