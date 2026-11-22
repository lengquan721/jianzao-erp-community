<template>
  <div class="navbar" :class="'nav' + navType">
    <hamburger id="hamburger-container" :is-active="appStore.sidebar.opened" class="hamburger-container" @toggle-click="toggleSideBar" />

    <breadcrumb v-if="navType == NavTypeEnum.LEFT" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="navType == NavTypeEnum.MIX" id="topmenu-container" class="topmenu-container" />

    <template v-if="navType == NavTypeEnum.TOP">
      <logo v-show="showLogo" :collapse="false"></logo>
      <top-bar id="topbar-container" class="topbar-container" />
    </template>
    <div class="right-menu flex align-center">
      <template v-if="appStore.device !== 'mobile'">
        <t-select
          v-if="userId === 1 && tenantEnabled"
          v-model="companyName"
          class="min-w-244px mr-2 navbar-tenant-select"
          clearable
          filterable
          :reserve-keyword="true"
          :options="tenantOptions"
          :placeholder="proxy.$t('navbar.selectTenant')"
          @change="dynamicTenantEvent"
          @clear="dynamicClearEvent"
        >
          <template #prefixIcon>
            <svg-icon icon-class="company" class="input-icon" />
          </template>
        </t-select>

        <search-menu ref="searchMenuRef" />
        <t-tooltip content="搜索" placement="bottom">
          <div class="right-menu-item hover-effect" @click="openSearchMenu">
            <svg-icon class-name="search-icon" icon-class="search" />
          </div>
        </t-tooltip>
        <!-- 消息 -->
        <t-tooltip :content="proxy.$t('navbar.message')" placement="bottom">
          <div style="display: flex; align-items: center">
            <t-popup placement="bottom" trigger="click" :overlay-inner-style="{ width: '300px' }">
              <t-badge :count="newNotice" :max-count="99">
                <div class="right-menu-item hover-effect"><svg-icon icon-class="message" /></div>
              </t-badge>
              <template #content>
                <notice></notice>
              </template>
            </t-popup>
          </div>
        </t-tooltip>
        <t-tooltip content="刷新页面" placement="bottom">
          <div class="right-menu-item hover-effect" @click="refreshPage">
            <RefreshIcon />
          </div>
        </t-tooltip>
        <t-tooltip :content="proxy.$t('navbar.full')" placement="bottom">
          <screenfull id="screenfull" class="right-menu-item hover-effect" />
        </t-tooltip>

        <t-tooltip :content="proxy.$t('navbar.language')" placement="bottom">
          <lang-select id="lang-select" class="right-menu-item hover-effect" />
        </t-tooltip>

        <t-tooltip :content="proxy.$t('navbar.layoutSize')" placement="bottom">
          <size-select id="size-select" class="right-menu-item hover-effect" />
        </t-tooltip>
      </template>
      <div class="avatar-container">
        <t-dropdown
          :options="avatarDropdownOptions"
          :min-column-width="120"
          trigger="click"
          class="right-menu-item hover-effect"
          @click="handleCommand"
        >
          <div class="avatar-wrapper">
            <img :src="userStore.avatar" class="user-avatar" />
            <CaretDownIcon />
          </div>
        </t-dropdown>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { CaretDownIcon, RefreshIcon } from 'tdesign-icons-vue-next';
import SearchMenu from './TopBar/search.vue';
import { useAppStore } from '@/store/modules/app';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { useNoticeStore } from '@/store/modules/notice';
import { getTenantList } from '@/api/login';
import { dynamicClear, dynamicTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/types';
import notice from './notice/index.vue';
import router from '@/router';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import Logo from '@/layout/components/Sidebar/Logo.vue';
import TopBar from './TopBar/index.vue';

const appStore = useAppStore();
const userStore = useUserStore();
const settingsStore = useSettingsStore();
const noticeStore = storeToRefs(useNoticeStore());
const newNotice = ref(<number>0);

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const userId = ref(userStore.userId);
const navType = computed(() => settingsStore.navType);
const showLogo = computed(() => settingsStore.sidebarLogo);

const companyName = ref(undefined);
const tenantList = ref<TenantVO[]>([]);
const tenantOptions = computed(() => tenantList.value.map((item) => ({ label: item.companyName, value: item.tenantId })));
// 是否切换了租户
const dynamic = ref(false);
// 租户开关
const tenantEnabled = ref(true);
// 搜索菜单
const searchMenuRef = ref<InstanceType<typeof SearchMenu>>();

const openSearchMenu = () => {
  searchMenuRef.value?.openSearch();
};

// 动态切换
const dynamicTenantEvent = async (tenantId: string) => {
  if (companyName.value != null && companyName.value !== '') {
    await dynamicTenant(tenantId);
    dynamic.value = true;
    await proxy?.$router.push('/');
    await proxy?.$tab.closeAllPage();
    await proxy?.$tab.refreshPage();
  }
};

const dynamicClearEvent = async () => {
  await dynamicClear();
  dynamic.value = false;
  await proxy?.$router.push('/');
  await proxy?.$tab.closeAllPage();
  await proxy?.$tab.refreshPage();
};

/** 租户列表 */
const initTenantList = async () => {
  const { data } = await getTenantList(true);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    tenantList.value = data.voList;
  }
};

defineExpose({
  initTenantList
});

const toggleSideBar = () => {
  appStore.toggleSideBar(false);
};

/**
 * 整页刷新：重新拉取入口 HTML 与静态资源。
 * 保留 hash 路由，入口地址追加时间戳，避免 index.html 命中缓存导致桌面端拿不到新版本。
 */
const refreshPage = () => {
  const { pathname, search, hash } = window.location;
  const query = `${search ? `${search}&` : '?'}_t=${Date.now()}`;
  window.location.replace(`${pathname}${query}${hash}`);
};

const logout = async () => {
  await proxy?.$modal.confirm('确定注销并退出系统吗？');
  userStore.logout().then(() => {
    router.replace({
      path: '/login',
      query: {
        redirect: encodeURIComponent(router.currentRoute.value.fullPath || '/')
      }
    });
    proxy?.$tab.closeAllPage();
  });
};

const emits = defineEmits(['setLayout']);
const setLayout = () => {
  emits('setLayout');
};
const toProfile = () => {
  router.push('/user/profile');
};
// 头像下拉：options 数据驱动，避免插槽里嵌 router-link 被 TDesign 误判为二级子菜单
const avatarDropdownOptions = computed(() => {
  const options: Array<{ content: string; value: string; divider?: boolean }> = [];
  if (!dynamic.value) {
    options.push({ content: proxy?.$t('navbar.personalCenter') || '个人中心', value: 'profile' });
  }
  if (settingsStore.showSettings) {
    options.push({ content: proxy?.$t('navbar.layoutSetting') || '布局设置', value: 'setLayout' });
  }
  options.push({ content: proxy?.$t('navbar.logout') || '退出登录', value: 'logout', divider: true });
  return options;
});
// 定义Command方法对象 通过key直接调用方法
const commandMap: { [key: string]: any } = {
  profile: toProfile,
  setLayout,
  logout
};
// t-dropdown 的 @click 回调给整个 option 对象，命令值在 dropdownItem.value
const handleCommand = (dropdownItem: { value?: string | number }) => {
  // 判断是否存在该方法
  if (dropdownItem?.value && commandMap[dropdownItem.value]) {
    commandMap[dropdownItem.value]();
  }
};
//用深度监听 消息
watch(
  () => noticeStore.state.value.notices,
  (newVal) => {
    newNotice.value = newVal.filter((item: any) => !item.read).length;
  },
  { deep: true }
);
</script>

<style lang="scss" scoped>
.navbar.navtop {
  .hamburger-container {
    display: none !important;
  }
}

:deep(.navbar-tenant-select .t-input) {
  height: 30px;
}

:deep(.t-badge .t-badge--circle) {
  top: 12px;
}

.flex {
  display: flex;
}

.align-center {
  align-items: center;
}

.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: var(--td-bg-color-container);
  border-bottom: 1px solid var(--td-border-level-1-color);
  box-shadow: none;
  display: flex;
  align-items: center;
  // padding: 0 8px;
  box-sizing: border-box;

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    //float: left;
    cursor: pointer;
    transition: background 0.3s;
    -webkit-tap-highlight-color: transparent;
    display: flex;
    align-items: center;
    flex-shrink: 0;
    margin-right: 8px;

    &:hover {
      background: var(--td-bg-color-container-hover);
    }
  }

  .breadcrumb-container {
    //float: left;
    flex-shrink: 0;
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .topbar-container {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    margin-left: 8px;
  }

  .errLog-container {
    display: inline-block;
    vertical-align: top;
  }

  .right-menu {
    height: 100%;
    display: flex;
    align-items: center;
    margin-left: auto;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 8px;
      height: 32px;
      font-size: 18px;
      color: var(--td-text-color-secondary);
      border-radius: var(--app-radius-md);

      &.hover-effect {
        cursor: pointer;
        transition:
          background 0.2s ease,
          color 0.2s ease;

        &:hover {
          background: var(--td-bg-color-container-hover);
          color: var(--td-brand-color);
        }
      }
    }

    .avatar-container {
      margin-right: 40px;

      .avatar-wrapper {
        margin-top: 0;
        position: relative;

        .user-avatar {
          cursor: pointer;
          width: 40px;
          height: 40px;
          border-radius: var(--app-radius-md);
          margin-top: 0;
          display: block;
        }

        i {
          cursor: pointer;
          position: absolute;
          right: -20px;
          top: 25px;
          font-size: 12px;
        }
      }
    }
  }
}
</style>
