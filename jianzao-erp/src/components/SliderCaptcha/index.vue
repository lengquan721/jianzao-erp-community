<template>
  <!-- 滑块验证：tac SDK 负责拼图与轨迹采集，外层采用全屏模态遮罩居中（复刻 code.html 原型） -->
  <teleport to="body">
    <transition name="sc-modal-fade">
      <div v-if="visible" class="sc-modal-overlay" @click.self="close">
        <div ref="cardRef" class="sc-card" :style="{ '--sc-track-text': `'${t('slider.trackHint')}'` }" @click.stop>
          <header class="sc-head">
            <span class="sc-head-icon">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path
                  d="M10 2.2 4 4.6v4.6c0 3.4 2.4 6.3 6 7.4 3.6-1.1 6-4 6-7.4V4.6L10 2.2Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linejoin="round"
                />
                <path
                  d="M7.4 9.9l1.9 1.9 3.5-3.7"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </span>
            <div class="sc-head-text">
              <h3>{{ t('slider.title') }}</h3>
              <p>{{ t('slider.subtitle') }}</p>
            </div>
            <button type="button" class="sc-icon-btn" :title="t('slider.refresh')" @click="reload">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path
                  d="M16.2 8.4A6.3 6.3 0 0 0 4.6 6.2M3.8 11.6A6.3 6.3 0 0 0 15.4 13.8"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
                <path
                  d="M16.4 4.2v4.4h-4.4M3.6 15.8v-4.4h4.4"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
            <button type="button" class="sc-icon-btn" :title="t('slider.close')" @click="close">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path d="M5.6 5.6l8.8 8.8M14.4 5.6l-8.8 8.8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
            </button>
          </header>

          <div class="sc-stage">
            <div ref="boxRef" class="sc-box"></div>
          </div>

          <footer class="sc-foot">
            <span class="sc-foot-text">
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path
                  d="M8 1.8 3.2 3.7v3.7c0 2.7 1.9 5 4.8 5.9 2.9-.9 4.8-3.2 4.8-5.9V3.7L8 1.8Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.2"
                  stroke-linejoin="round"
                />
              </svg>
              {{ t('slider.footer') }}
            </span>
            <button type="button" class="sc-foot-link" @click="reload">{{ t('slider.changeOne') }}</button>
          </footer>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

/** SDK 脚本与样式的静态资源路径（public/tac） */
const TAC_JS = '/tac/js/tac.min.js';
const TAC_CSS = '/tac/css/tac.css';
/** SDK 全局构造函数 */
declare global {
  interface Window {
    TAC: new (
      config: Record<string, any>,
      style: Record<string, any>
    ) => {
      init: () => void;
      destroyWindow: () => void;
      reloadCaptcha: () => void;
    };
  }
}

const boxRef = ref<HTMLElement>();
const cardRef = ref<HTMLElement>();
const visible = ref(false);
const emit = defineEmits<{
  /** 服务端校验通过，返回登录票据 {uuid, code} */
  (e: 'success', payload: { uuid: string; code: string }): void;
  /** 用户关闭或验证失败 */
  (e: 'close'): void;
}>();

let instance: InstanceType<Window['TAC']> | null = null;
/** 实例的绑定容器 id：SDK 用 id 选择器挂载，每次打开重新分配 */
let boxId = '';

/**
 * 按需加载 SDK 脚本与样式，幂等：重复调用不会插入重复标签。
 * @returns SDK 构造函数就绪
 */
const loadSdk = async (): Promise<void> => {
  if (!document.getElementById('tac-sdk-css')) {
    const link = document.createElement('link');
    link.id = 'tac-sdk-css';
    link.rel = 'stylesheet';
    link.href = TAC_CSS;
    document.head.appendChild(link);
  }
  if (!window.TAC) {
    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = TAC_JS;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('滑块验证资源加载失败'));
      document.head.appendChild(script);
    });
  }
};

/**
 * 初始化滑块：验证通过后把票据交给父组件，失败自动重载拼图。
 * 卡片贴着验证条展开，容器必须已经渲染出来，SDK 才能按 id 取到绑定元素。
 */
const open = async () => {
  visible.value = true;
  await nextTick();
  const box = boxRef.value;
  if (!box) return;
  boxId = `tac-box-${Math.random().toString(36).slice(2, 9)}`;
  box.id = boxId;
  await loadSdk();
  destroy();
  const config = {
    requestCaptchaDataUrl: `${import.meta.env.VITE_APP_BASE_API}/auth/code/slider`,
    validCaptchaUrl: `${import.meta.env.VITE_APP_BASE_API}/auth/code/check`,
    // SDK 走原生 XHR，不会经过 axios 拦截器，客户端标识必须显式带上
    requestHeaders: { clientid: import.meta.env.VITE_APP_CLIENT_ID },
    bindEl: `#${boxId}`,
    validSuccess: (res: any, _c: any, tac: any) => {
      const payload = { uuid: res?.data?.uuid || '', code: res?.data?.code || '' };
      tac.destroyWindow();
      instance = null;
      // 留一帧展示 SDK 的成功提示，再收窗
      window.setTimeout(() => {
        visible.value = false;
        emit('success', payload);
      }, 420);
    },
    validFail: (_res: any, _c: any, tac: any) => tac.reloadCaptcha(),
    btnCloseFun: (_e: any, tac: any) => {
      tac.destroyWindow();
      instance = null;
      visible.value = false;
      emit('close');
    }
  };
  const style = {
    logoUrl: null,
    moveTrackMaskBgColor: '#e6efff',
    moveTrackMaskBorderColor: '#0052d9',
    i18n: {
      tips_success: t('slider.tipsSuccess'),
      tips_error: t('slider.tipsError'),
      slider_title: t('slider.title')
    }
  };
  instance = new window.TAC(config, style);
  instance.init();
};

/** 换一张拼图：票据作废，需重新滑动 */
const reload = () => {
  instance?.reloadCaptcha();
};

/** 销毁 SDK 实例，释放 DOM 与事件监听 */
const destroy = () => {
  instance?.destroyWindow();
  instance = null;
};

/** 关闭弹窗：父组件据此保持未验证状态 */
const close = () => {
  destroy();
  if (!visible.value) return;
  visible.value = false;
  emit('close');
};

/** ESC 关闭：模态层支持键盘退出 */
const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && visible.value) close();
};

defineExpose({ open, destroy });

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  destroy();
});
</script>

<style lang="scss" scoped>
/* 全屏模态居中遮罩层：复刻原型 code.html，不影响页面流与排版 */
.sc-modal-overlay {
  position: fixed !important;
  inset: 0 !important;
  z-index: 2500 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 16px;
  background: rgba(24, 28, 35, 0.45);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

/* 居中验证卡片：独立于表单流，零挤压、零位移 */
.sc-card {
  position: relative;
  width: 360px;
  padding: 20px;
  border-radius: 16px;
  background: #fff;
  color: #181c23;
  box-shadow:
    0 24px 48px -12px rgba(15, 31, 71, 0.28),
    0 0 0 1px rgba(15, 31, 71, 0.08);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  user-select: none;
}

.sc-modal-fade-enter-active,
.sc-modal-fade-leave-active {
  transition: opacity 180ms ease;

  .sc-card {
    transition:
      transform 180ms cubic-bezier(0.16, 1, 0.3, 1),
      opacity 180ms ease;
  }
}

.sc-modal-fade-enter-from,
.sc-modal-fade-leave-to {
  opacity: 0;

  .sc-card {
    opacity: 0;
    transform: scale(0.96) translateY(-8px);
  }
}

.sc-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.sc-head-icon {
  display: inline-flex;
  width: 18px;
  height: 18px;
  margin-top: 2px;
  color: #0052d9;
}

.sc-head-icon svg {
  width: 100%;
  height: 100%;
}

.sc-head-text {
  flex: 1;
  min-width: 0;
}

.sc-head-text h3 {
  margin: 0;
  font-size: 15px;
  line-height: 20px;
  font-weight: 600;
}

.sc-head-text p {
  margin: 4px 0 0 0;
  color: #7b7f8c;
  font-size: 12px;
  line-height: 18px;
}

.sc-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8b8f99;
  cursor: pointer;
  transition:
    background 150ms ease,
    color 150ms ease;
}

.sc-icon-btn svg {
  width: 16px;
  height: 16px;
}

.sc-icon-btn:hover {
  background: #f2f3f5;
  color: #181c23;
}

.sc-stage {
  display: flex;
  justify-content: center;
  overflow: hidden;
  /* 拼图加载完成前也保持卡片高度稳定，避免弹层忽高忽低 */
  min-height: 240px;
}

.sc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f1f4;
  color: #9aa0ac;
  font-size: 11px;
}

.sc-foot-text {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.sc-foot-text svg {
  width: 12px;
  height: 12px;
}

.sc-foot-link {
  padding: 0;
  border: 0;
  background: none;
  color: #0052d9;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
}

.sc-foot-link:hover {
  text-decoration: underline;
}

/* ---------- tac SDK 换皮：去掉官方荧光绿/灰底，改成「浅底 + 品牌蓝」 ---------- */
:global(.sc-card #tianai-captcha-parent) {
  width: 300px !important;
  height: auto !important;
  padding: 0 !important;
  border-radius: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  overflow: visible !important;
  z-index: auto !important;
}

:global(.sc-card #tianai-captcha-parent #tianai-captcha-box) {
  position: relative !important;
  width: 300px !important;
  height: 240px !important;
  overflow: hidden !important;
}

/* 标题与「logo + 关闭 + 刷新」底栏由弹窗外壳承担，SDK 自带的整条隐藏 */
:global(.sc-card #tianai-captcha-parent .slider-bottom) {
  display: none !important;
}

:global(.sc-card #tianai-captcha.tianai-captcha-slider) {
  position: relative !important;
  width: 300px !important;
  height: auto !important;
}

/* SDK 用 translateX(-300px) → 0 做入场动画，openCaptcha 的定时器偶尔不执行会把拼图留在卡片外，
   卡片本身就是一次性浮层，直接取消位移动画更稳 */
:global(.sc-card #tianai-captcha) {
  transform: none !important;
  transition-duration: 0s !important;
}

:global(.sc-card #tianai-captcha .slider-tip) {
  display: none !important;
}

:global(.sc-card #tianai-captcha .content) {
  width: 300px !important;
  height: 180px !important;
  border-radius: 12px;
  background: #0f2c56;
  box-shadow: inset 0 0 0 1px rgba(15, 44, 86, 0.12);
}

:global(.sc-card #tianai-captcha-slider-bg-img),
:global(.sc-card #tianai-captcha .bg-img-div img) {
  border-radius: 12px !important;
}

/* 滑块条：46px 高的浅灰槽，文案由 --sc-track-text 注入，箭头按钮换成品牌蓝方块 */
:global(.sc-card #tianai-captcha .slider-move) {
  height: 46px !important;
  margin: 14px 0 0 0 !important;
}

:global(.sc-card #tianai-captcha .slider-move-track) {
  height: 46px !important;
  line-height: 46px !important;
  border: 0 !important;
  border-radius: 10px !important;
  background: #f5f6f8 !important;
  box-shadow: inset 0 0 0 1px #e7e8ec;
  color: #8b8f99 !important;
  font-size: 13px !important;
}

:global(.sc-card #tianai-captcha .slider-move-track::after) {
  content: var(--sc-track-text);
}

:global(.sc-card #tianai-captcha .slider-move-shadow) {
  display: none !important;
}

:global(.sc-card #tianai-captcha #tianai-captcha-slider-move-track-mask) {
  top: 0 !important;
  left: 0 !important;
  height: 46px !important;
  border: 0 !important;
  border-radius: 10px !important;
  background: rgba(0, 82, 217, 0.1) !important;
  opacity: 1 !important;
}

:global(.sc-card #tianai-captcha .slider-move .slider-move-btn) {
  top: 0 !important;
  width: 46px !important;
  height: 46px !important;
  border: 0 !important;
  border-radius: 10px !important;
  background-color: #0052d9 !important;
  background-image: none !important;
  box-shadow: 0 2px 10px rgba(0, 82, 217, 0.32);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

:global(.sc-card #tianai-captcha .slider-move .slider-move-btn::after) {
  content: '»';
  color: #fff;
  font-size: 20px;
  line-height: 1;
  letter-spacing: -3px;
  margin-right: -3px;
}

:global(.sc-card #tianai-captcha .content .tianai-captcha-tips) {
  bottom: -26px;
  height: 26px;
  line-height: 26px;
  font-size: 12px;
  border-radius: 0;
}

:global(.sc-card #tianai-captcha .content .tianai-captcha-tips.tianai-captcha-tips-success) {
  background-color: #00a870 !important;
}

:global(.sc-card #tianai-captcha .content .tianai-captcha-tips.tianai-captcha-tips-error) {
  background-color: #d54941 !important;
}

:global(.sc-card #tianai-captcha-loading) {
  position: absolute !important;
  top: 0 !important;
  left: 0 !important;
  width: 300px !important;
  height: 180px !important;
  margin: 0 !important;
  border-radius: 12px !important;
  background: rgba(248, 250, 252, 0.92) !important;
  backdrop-filter: blur(4px) !important;
  z-index: 20 !important;
  -webkit-mask: none !important;
  mask: none !important;
  animation: none !important;
}

:global(.sc-card #tianai-captcha-loading[style*='display: none']) {
  display: none !important;
}

:global(.sc-card #tianai-captcha-loading:not([style*='display: none'])) {
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
}

:global(.sc-card #tianai-captcha-loading::after) {
  content: '';
  width: 32px;
  height: 32px;
  border: 3px solid rgba(0, 82, 217, 0.16);
  border-top-color: #0052d9;
  border-radius: 50%;
  animation: sc-spin 0.8s linear infinite;
  box-sizing: border-box;
}

@keyframes sc-spin {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}
</style>

/* 暗色档：scoped 里的 :global(html.dark) 会被编译器丢弃，单独用非 scoped 块 */
<style lang="scss">
/* 暗色适配：模态遮罩与卡片跟随全局 html.dark */
html.dark .sc-modal-overlay {
  background: rgba(0, 0, 0, 0.65);
}

html.dark .sc-card #tianai-captcha-loading {
  background: rgba(25, 31, 43, 0.92) !important;
}

html.dark .sc-card #tianai-captcha-loading::after {
  border-color: rgba(91, 147, 255, 0.2);
  border-top-color: #5b93ff;
}

html.dark .sc-card {
  background: #191f2b;
  color: #edf0f7;
  box-shadow:
    0 24px 48px -12px rgba(0, 0, 0, 0.7),
    0 0 0 1px rgba(255, 255, 255, 0.08);
}

html.dark .sc-head-text p {
  color: #8b93a5;
}

html.dark .sc-icon-btn:hover {
  background: #232a3a;
  color: #edf0f7;
}

html.dark .sc-foot {
  border-top-color: #232a3a;
  color: #7d869b;
}

html.dark .sc-foot-link {
  color: #5b93ff;
}
</style>
